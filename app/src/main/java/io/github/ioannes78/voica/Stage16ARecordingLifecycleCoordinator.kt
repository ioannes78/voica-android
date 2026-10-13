package io.github.ioannes78.voica

import androidx.room.withTransaction
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.database.AudioAssetEntity
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingDisplayNamePolicy
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.RecordingUserMetadataEntity
import io.github.ioannes78.voica.database.RealtimeMediaSegmentStateValue
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class Stage16APhysicalRecordingAnchor(
    val runId: String,
    val deviceIdentity: String,
    val recordingIdentity: String,
    val transportGeneration: Long,
    val establishedAtMs: Long,
)

enum class Stage16ALibraryReconciliationResult {
    NOT_RUN,
    COMPLETED,
    ANCHOR_UNCONFIRMED,
    DEVICE_FILE_UNCONFIRMED,
    LOCAL_MEDIA_UNAVAILABLE,
    FAILED,
}

data class Stage16ATerminalReconciliationOutcome(
    val recordingId: String?,
    val remoteIdentity: String?,
    val completeLocalWavRegistered: Boolean,
    val result: Stage16ALibraryReconciliationResult,
    val error: String? = null,
)

data class Stage16ARecordingLifecycleSnapshot(
    val runId: String? = null,
    val anchorDeviceIdentity: String? = null,
    val anchorRecordingIdentity: String? = null,
    val anchorTransportGeneration: Long? = null,
    val anchorSyncRequests: Long = 0,
    val anchorSyncFailures: Long = 0,
    val terminalSyncRequests: Long = 0,
    val terminalRefreshRequests: Long = 0,
    val resolvedRemoteIdentity: String? = null,
    val resolvedRecordingId: String? = null,
    val localSegmentCount: Int = 0,
    val completeLocalWavRegistered: Boolean = false,
    val libraryReconciliationResult: Stage16ALibraryReconciliationResult =
        Stage16ALibraryReconciliationResult.NOT_RUN,
    val lastError: String? = null,
)

/**
 * Stage 16A product-level bridge between one physical recorder-card recording and the durable
 * Recording Library. It deliberately stays above Stage 15 transport/canonical media and never sends
 * realtime START/RESUME commands by itself.
 */
class Stage16ARecordingLifecycleCoordinator(
    private val deviceRepository: DeviceRepository,
    private val database: VoicaDatabase,
    private val recordingsRoot: File,
    private val searchIndexRebuilder: SearchIndexRebuilder,
    private val transportGeneration: () -> Long?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val realtimeRepository = RealtimeTranscriptPersistenceRepository(database)

    @Volatile
    private var activeAnchor: Stage16APhysicalRecordingAnchor? = null

    @Volatile
    private var latestSnapshot = Stage16ARecordingLifecycleSnapshot()

    fun snapshot(): Stage16ARecordingLifecycleSnapshot = latestSnapshot

    fun anchorForRun(runId: String): Stage16APhysicalRecordingAnchor? =
        activeAnchor?.takeIf { it.runId == runId }

    /**
     * TYPE1 realtime START does not itself populate DeviceRepository recording truth. Explicitly
     * converge TYPE3 recording state immediately after START so reconnect/terminal reconciliation
     * has a stable device + filename identity while the recording is still active.
     */
    suspend fun establishAnchor(runId: String): Stage16APhysicalRecordingAnchor? {
        latestSnapshot =
            latestSnapshot.copy(
                runId = runId,
                anchorSyncRequests = latestSnapshot.anchorSyncRequests + 1,
                lastError = null,
            )
        val syncFailure = runCatching { deviceRepository.syncRecordingState() }.exceptionOrNull()
        if (syncFailure != null) {
            latestSnapshot =
                latestSnapshot.copy(
                    anchorSyncFailures = latestSnapshot.anchorSyncFailures + 1,
                    lastError = syncFailure.message ?: syncFailure.javaClass.simpleName,
                )
            return null
        }

        val ready = deviceRepository.connectionState.value as? DeviceConnectionState.Ready
        val recording = deviceRepository.recordingState.value
        val generation = transportGeneration()
        val filename = recording.trustedActiveFilename()
        if (ready == null || generation == null || filename == null) {
            latestSnapshot =
                latestSnapshot.copy(
                    anchorSyncFailures = latestSnapshot.anchorSyncFailures + 1,
                    lastError = "recording anchor was not confirmed after realtime START",
                )
            return null
        }

        return Stage16APhysicalRecordingAnchor(
            runId = runId,
            deviceIdentity = ready.address,
            recordingIdentity = filename,
            transportGeneration = generation,
            establishedAtMs = clockMs(),
        ).also { anchor ->
            activeAnchor = anchor
            latestSnapshot =
                latestSnapshot.copy(
                    runId = runId,
                    anchorDeviceIdentity = anchor.deviceIdentity,
                    anchorRecordingIdentity = anchor.recordingIdentity,
                    anchorTransportGeneration = anchor.transportGeneration,
                    lastError = null,
                )
        }
    }

    suspend fun reconcileTerminal(
        runId: String,
        persistence: RealtimeTranscriptPersistenceCoordinator,
    ): Stage16ATerminalReconciliationOutcome {
        val anchor = anchorForRun(runId)
        if (anchor == null) {
            return publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = null,
                    remoteIdentity = null,
                    completeLocalWavRegistered = false,
                    result = Stage16ALibraryReconciliationResult.ANCHOR_UNCONFIRMED,
                    error = "physical recording anchor is unavailable",
                ),
            )
        }

        latestSnapshot =
            latestSnapshot.copy(
                terminalSyncRequests = latestSnapshot.terminalSyncRequests + 1,
                lastError = null,
            )
        runCatching { deviceRepository.syncRecordingState() }
            .onFailure { failure ->
                latestSnapshot = latestSnapshot.copy(lastError = failure.message)
            }

        latestSnapshot =
            latestSnapshot.copy(
                terminalRefreshRequests = latestSnapshot.terminalRefreshRequests + 1,
            )
        val refreshFailure = runCatching { deviceRepository.refreshDeviceFiles() }.exceptionOrNull()
        if (refreshFailure != null) {
            return publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = null,
                    remoteIdentity = null,
                    completeLocalWavRegistered = false,
                    result = Stage16ALibraryReconciliationResult.FAILED,
                    error = refreshFailure.message ?: refreshFailure.javaClass.simpleName,
                ),
            )
        }

        val fileState = awaitFreshFileList()
        val remote = fileState?.files?.findMatching(anchor.recordingIdentity)
        if (remote == null) {
            return publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = null,
                    remoteIdentity = null,
                    completeLocalWavRegistered = false,
                    result = Stage16ALibraryReconciliationResult.DEVICE_FILE_UNCONFIRMED,
                    error = "finished device recording was not found in refreshed file list",
                ),
            )
        }

        return try {
            val recordingId = ensureRecording(remote)
            check(persistence.attachRecording(recordingId)) {
                "failed to attach realtime run to recording"
            }
            val segments = realtimeRepository.getSegments(runId)
            val completeLocal = registerCompleteLocalWav(recordingId, segments)
            searchIndexRebuilder.reindexRecording(recordingId)
            AppRecordingNavigation.publish(recordingId, RecordingDetailDestination.PLAYBACK)
            activeAnchor = null
            publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = recordingId,
                    remoteIdentity = remote.identity,
                    completeLocalWavRegistered = completeLocal,
                    result =
                        if (completeLocal || segments.isNotEmpty()) {
                            Stage16ALibraryReconciliationResult.COMPLETED
                        } else {
                            Stage16ALibraryReconciliationResult.LOCAL_MEDIA_UNAVAILABLE
                        },
                ),
                segmentCount = segments.size,
            )
        } catch (failure: Throwable) {
            publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = null,
                    remoteIdentity = remote.identity,
                    completeLocalWavRegistered = false,
                    result = Stage16ALibraryReconciliationResult.FAILED,
                    error = failure.message ?: failure.javaClass.simpleName,
                ),
            )
        }
    }

    private suspend fun awaitFreshFileList(): DeviceFileListState? {
        val current = deviceRepository.deviceFileListState.value
        if (current.freshness == FileListFreshness.FRESH || current.freshness == FileListFreshness.EMPTY) {
            return current
        }
        return withTimeoutOrNull(FILE_LIST_WAIT_MS) {
            deviceRepository.deviceFileListState
                .filter {
                    it.freshness == FileListFreshness.FRESH ||
                        it.freshness == FileListFreshness.EMPTY ||
                        it.freshness == FileListFreshness.FAILED
                }
                .first()
        }
    }

    private suspend fun ensureRecording(remote: RemoteDeviceFile): String {
        val recordingId = stableId(remote.identity)
        val now = clockMs()
        val durationMs =
            remote.durationSeconds?.let { Math.multiplyExact(it, 1_000L) }
                ?: runCatching { Math.multiplyExact(remote.rawTimeValue, 1_000L) }.getOrNull()
        database.withTransaction {
            val dao = database.recordingDao()
            val existing = dao.findByRemoteIdentity(remote.identity)
            if (existing == null) {
                dao.insertRecordingIgnore(
                    RecordingEntity(
                        id = recordingId,
                        sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                        sourceRemoteIdentity = remote.identity,
                        sourceDeviceAddress = remote.deviceAddress,
                        originalFilename = remote.displayFilename,
                        displayName =
                            RecordingDisplayNamePolicy.defaultDisplayName(remote.displayFilename),
                        recordedAtLocalIso = remote.recordedAt?.toString(),
                        deviceReportedDurationMs = durationMs,
                        mediaDurationMs = durationMs,
                        downloadedAtMs = null,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ),
                )
                dao.insertUserMetadataIgnore(
                    RecordingUserMetadataEntity(
                        recordingId = recordingId,
                        folderId = null,
                        isFavorite = false,
                        updatedAtMs = now,
                    ),
                )
            }
        }
        return database.recordingDao().findByRemoteIdentity(remote.identity)?.id ?: recordingId
    }

    private suspend fun registerCompleteLocalWav(
        recordingId: String,
        segments: List<io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity>,
    ): Boolean {
        if (segments.size != 1) return false
        val segment = segments.single()
        if (segment.state != RealtimeMediaSegmentStateValue.FINALIZED) return false
        val storedPath = segment.localRelativePath ?: return false
        val expectedSize = segment.sizeBytes ?: return false
        val expectedSha = segment.sha256 ?: return false
        val realtimeRoot = File(recordingsRoot, "realtime").canonicalFile
        val file = runCatching { File(realtimeRoot, storedPath).canonicalFile }.getOrNull() ?: return false
        if (file.path != realtimeRoot.path && !file.path.startsWith(realtimeRoot.path + File.separator)) {
            return false
        }
        if (!file.isFile || file.length() != expectedSize) return false
        val relativeToRecordings = file.relativeTo(recordingsRoot.canonicalFile).path
        val now = clockMs()
        database.recordingDao().upsertAsset(
            AudioAssetEntity(
                assetId = stableId("$recordingId|canonical=${CanonicalPcmProfile.PROFILE_ID}"),
                recordingId = recordingId,
                role = AudioAssetRole.CANONICAL_WAV,
                relativePath = relativeToRecordings,
                container = "WAV",
                codec = "PCM",
                sampleFormat = "PCM16_LE",
                sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                sizeBytes = expectedSize,
                sha256 = expectedSha.lowercase(),
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = AudioValidationState.VALID,
                createdAtMs = now,
                verifiedAtMs = now,
            ),
        )
        return true
    }

    private fun publishTerminal(
        outcome: Stage16ATerminalReconciliationOutcome,
        segmentCount: Int = latestSnapshot.localSegmentCount,
    ): Stage16ATerminalReconciliationOutcome {
        latestSnapshot =
            latestSnapshot.copy(
                resolvedRemoteIdentity = outcome.remoteIdentity,
                resolvedRecordingId = outcome.recordingId,
                localSegmentCount = segmentCount,
                completeLocalWavRegistered = outcome.completeLocalWavRegistered,
                libraryReconciliationResult = outcome.result,
                lastError = outcome.error,
            )
        return outcome
    }

    private fun RecordingDeviceState.trustedActiveFilename(): String? {
        if (freshness != RecordingFreshness.FRESH) return null
        if (status != RecordingStatus.Recording && status != RecordingStatus.Paused) return null
        return filename?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun List<RemoteDeviceFile>.findMatching(filename: String): RemoteDeviceFile? =
        firstOrNull { file ->
            file.resolvedFilename.equals(filename, ignoreCase = true) ||
                file.displayFilename.equals(filename, ignoreCase = true) ||
                file.rawFilename.equals(filename, ignoreCase = true) ||
                file.resolvedFilename?.substringBeforeLast('.')
                    ?.equals(filename.substringBeforeLast('.'), ignoreCase = true) == true
        }

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val FILE_LIST_WAIT_MS = 5_000L
    }
}

fun Stage16ARecordingLifecycleSnapshot.toDiagnosticsText(
    reconnect: Stage16AReconnectRecoverySnapshot,
): String = buildString {
    appendLine("[Stage 16A lifecycle]")
    appendLine("runId=${runId ?: "n/a"}")
    appendLine(
        "anchor device=${anchorDeviceIdentity ?: "n/a"} filename=${anchorRecordingIdentity ?: "n/a"} " +
            "generation=${anchorTransportGeneration ?: "n/a"} syncRequests=$anchorSyncRequests syncFailures=$anchorSyncFailures",
    )
    appendLine(
        "reconnect pending=${reconnect.pendingRecovery} syncRequests=${reconnect.syncRequests} " +
            "syncFailures=${reconnect.syncFailures} attempts=${reconnect.recoveryAttempts} " +
            "attached=${reconnect.recoveryAttached} failures=${reconnect.recoveryFailures}",
    )
    appendLine(
        "reconnectDecision=${reconnect.lastDecision?.name ?: "n/a"} " +
            "attachFailure=${reconnect.lastAttachFailure?.name ?: "n/a"}",
    )
    appendLine(
        "terminal syncRequests=$terminalSyncRequests refreshRequests=$terminalRefreshRequests " +
            "remoteIdentity=${resolvedRemoteIdentity ?: "n/a"} recordingId=${resolvedRecordingId ?: "n/a"}",
    )
    appendLine(
        "localSegments=$localSegmentCount completeLocalWav=$completeLocalWavRegistered " +
            "libraryReconciliation=${libraryReconciliationResult.name}",
    )
    append("lifecycleLastError=${lastError ?: "n/a"}")
}
