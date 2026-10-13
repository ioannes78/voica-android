package io.github.ioannes78.voica

import androidx.room.withTransaction
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingDisplayNamePolicy
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.RecordingUserMetadataEntity
import io.github.ioannes78.voica.database.RealtimeCanonicalCapture
import io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity
import io.github.ioannes78.voica.database.RealtimeMediaSegmentStateValue
import io.github.ioannes78.voica.database.RealtimeRecordingIntegrationRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    /** Retained so older QA/source contracts continue to compile; new code uses explicit states below. */
    COMPLETED,
    COMPLETED_LOCAL_CANONICAL,
    REMOTE_SOURCE_DOWNLOAD_PENDING,
    COMPLETED_DEVICE_SOURCE,
    REMOTE_ONLY_PENDING_DOWNLOAD,
    ANCHOR_UNCONFIRMED,
    DEVICE_FILE_UNCONFIRMED,
    LOCAL_MEDIA_UNAVAILABLE,
    FAILED,
}

enum class Stage16ARemoteFallbackState {
    NOT_REQUIRED,
    PENDING,
    DOWNLOADED,
    FAILED,
}

data class Stage16ATerminalReconciliationOutcome(
    val recordingId: String?,
    val remoteIdentity: String?,
    val completeLocalWavRegistered: Boolean,
    val realtimeTranscriptionId: String? = null,
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
    val realtimeTranscriptionId: String? = null,
    val localSegmentCount: Int = 0,
    val completeLocalWavRegistered: Boolean = false,
    val remoteFallbackState: Stage16ARemoteFallbackState = Stage16ARemoteFallbackState.NOT_REQUIRED,
    val repairedCanonicalLineages: Long = 0,
    val repairedRealtimeTranscripts: Long = 0,
    val libraryReconciliationResult: Stage16ALibraryReconciliationResult =
        Stage16ALibraryReconciliationResult.NOT_RUN,
    val lastError: String? = null,
)

private data class Stage16ADirectCanonicalResult(
    val assetId: String,
    val sha256: String,
)

/**
 * Stage 16A product-level bridge between one physical recorder-card recording and the durable
 * Recording Library. It deliberately stays above Stage 15 transport/canonical media and never sends
 * realtime START/RESUME commands by itself.
 */
class Stage16ARecordingLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val realtimeState: StateFlow<Stage16ARealtimeUiState>,
    private val deviceRepository: DeviceRepository,
    private val database: VoicaDatabase,
    private val recordingsRoot: File,
    private val searchIndexRebuilder: SearchIndexRebuilder,
    private val transportGeneration: () -> Long?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    startCollectors: Boolean = true,
) {
    private val realtimeRepository = RealtimeTranscriptPersistenceRepository(database)
    private val integrationRepository = RealtimeRecordingIntegrationRepository(database, clockMs)
    private val stateLock = Any()

    @Volatile
    private var activeAnchor: Stage16APhysicalRecordingAnchor? = null

    @Volatile
    private var latestSnapshot = Stage16ARecordingLifecycleSnapshot()

    private var anchorAttemptRunId: String? = null
    private var terminalAttemptRunId: String? = null

    init {
        if (startCollectors) {
            Stage16ALifecycleDiagnosticsRegistry.installLifecycle(::snapshot)
            scope.launch {
                runCatching { repairExistingRealtimeArtifacts() }
                    .onFailure { failure ->
                        latestSnapshot =
                            latestSnapshot.copy(
                                lastError = failure.message ?: failure.javaClass.simpleName,
                            )
                    }
            }
            scope.launch {
                realtimeState
                    .map { state -> state.runId to state.phase }
                    .distinctUntilChanged()
                    .collect { (runId, phase) ->
                        if (runId == null) return@collect
                        when (phase) {
                            Stage16ARealtimeUiPhase.ACTIVE -> establishAnchorOnce(runId)
                            Stage16ARealtimeUiPhase.COMPLETED -> reconcileTerminalOnce(runId)
                            else -> Unit
                        }
                    }
            }
        }
    }

    fun snapshot(): Stage16ARecordingLifecycleSnapshot = latestSnapshot

    fun anchorForRun(runId: String): Stage16APhysicalRecordingAnchor? =
        activeAnchor?.takeIf { it.runId == runId }

    private suspend fun establishAnchorOnce(runId: String) {
        val shouldAttempt =
            synchronized(stateLock) {
                if (anchorAttemptRunId == runId || activeAnchor?.runId == runId) {
                    false
                } else {
                    anchorAttemptRunId = runId
                    true
                }
            }
        if (!shouldAttempt) return

        repeat(ANCHOR_MAX_ATTEMPTS) { attempt ->
            if (realtimeState.value.runId != runId) return
            if (establishAnchor(runId) != null) return
            if (attempt + 1 < ANCHOR_MAX_ATTEMPTS) delay(ANCHOR_RETRY_MS)
        }
    }

    private suspend fun reconcileTerminalOnce(runId: String) {
        val shouldAttempt =
            synchronized(stateLock) {
                if (terminalAttemptRunId == runId) {
                    false
                } else {
                    terminalAttemptRunId = runId
                    true
                }
            }
        if (!shouldAttempt) return
        reconcileTerminal(runId)
    }

    /**
     * TYPE1 realtime START does not itself populate DeviceRepository recording truth. Explicitly
     * converge TYPE3 recording state as soon as the Stage 16 run becomes ACTIVE so reconnect and
     * terminal reconciliation retain a stable device + filename identity while the recording exists.
     */
    internal suspend fun establishAnchor(runId: String): Stage16APhysicalRecordingAnchor? {
        if (latestSnapshot.runId != runId) {
            latestSnapshot = Stage16ARecordingLifecycleSnapshot(runId = runId)
        }
        latestSnapshot =
            latestSnapshot.copy(
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

        val anchor =
            Stage16APhysicalRecordingAnchor(
                runId = runId,
                deviceIdentity = ready.address,
                recordingIdentity = filename,
                transportGeneration = generation,
                establishedAtMs = clockMs(),
            )
        activeAnchor = anchor
        latestSnapshot =
            latestSnapshot.copy(
                runId = runId,
                anchorDeviceIdentity = anchor.deviceIdentity,
                anchorRecordingIdentity = anchor.recordingIdentity,
                anchorTransportGeneration = anchor.transportGeneration,
                lastError = null,
            )

        // This identity is deliberately durable before disconnect so later terminal reconciliation
        // does not depend on an Idle-state filename read, which the frozen Stage 3 policy omits.
        runCatching {
            realtimeRepository.getRun(runId)?.let { run ->
                if (run.deviceRecordingIdentity != filename) {
                    realtimeRepository.updateRun(
                        run.copy(
                            deviceRecordingIdentity = filename,
                            updatedAtMs = clockMs(),
                        ),
                    )
                }
            }
        }.onFailure { failure ->
            latestSnapshot = latestSnapshot.copy(lastError = failure.message)
        }
        return anchor
    }

    internal suspend fun reconcileTerminal(runId: String): Stage16ATerminalReconciliationOutcome {
        val anchor = anchorForRun(runId)
            ?: realtimeRepository.getRun(runId)
                ?.deviceRecordingIdentity
                ?.let { filename ->
                    val ready = deviceRepository.connectionState.value as? DeviceConnectionState.Ready
                    val generation = transportGeneration()
                    if (ready != null && generation != null) {
                        Stage16APhysicalRecordingAnchor(
                            runId = runId,
                            deviceIdentity = ready.address,
                            recordingIdentity = filename,
                            transportGeneration = generation,
                            establishedAtMs = clockMs(),
                        )
                    } else {
                        null
                    }
                }
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
            .onFailure { failure -> latestSnapshot = latestSnapshot.copy(lastError = failure.message) }

        var resolvedRemote: RemoteDeviceFile? = null
        var lastRefreshFailure: Throwable? = null
        for (attempt in 0 until TERMINAL_FILE_REFRESH_ATTEMPTS) {
            latestSnapshot =
                latestSnapshot.copy(
                    terminalRefreshRequests = latestSnapshot.terminalRefreshRequests + 1,
                )
            lastRefreshFailure = runCatching { deviceRepository.refreshDeviceFiles() }.exceptionOrNull()
            if (lastRefreshFailure == null) {
                val state = awaitFreshFileList()
                resolvedRemote = state?.files?.findMatching(anchor.recordingIdentity)
                if (resolvedRemote != null) break
            }
            if (attempt + 1 < TERMINAL_FILE_REFRESH_ATTEMPTS) {
                delay(TERMINAL_FILE_REFRESH_RETRY_MS)
            }
        }

        val remote = resolvedRemote
            ?: return publishTerminal(
                Stage16ATerminalReconciliationOutcome(
                    recordingId = null,
                    remoteIdentity = null,
                    completeLocalWavRegistered = false,
                    result =
                        if (lastRefreshFailure != null) {
                            Stage16ALibraryReconciliationResult.FAILED
                        } else {
                            Stage16ALibraryReconciliationResult.DEVICE_FILE_UNCONFIRMED
                        },
                    error =
                        lastRefreshFailure?.let { it.message ?: it.javaClass.simpleName }
                            ?: "finished device recording was not found in refreshed file list",
                ),
            )

        return try {
            val recordingId = ensureRecording(remote)
            check(realtimeRepository.attachRecording(runId, recordingId, clockMs())) {
                "failed to attach realtime run to recording"
            }
            val remoteFilename = remote.resolvedFilename ?: remote.displayFilename
            val segments = realtimeRepository.getSegments(runId)
            segments.forEach { segment ->
                if (segment.deviceFilename != remoteFilename) {
                    realtimeRepository.updateSegment(segment.copy(deviceFilename = remoteFilename))
                }
            }
            val reconciledSegments = realtimeRepository.getSegments(runId)
            val directCanonical = registerCompleteLocalWav(recordingId, remote, reconciledSegments)

            if (directCanonical == null) {
                integrationRepository.ensureRemotePendingAsset(
                    recordingId = recordingId,
                    remoteIdentity = remote.identity,
                    filename = remoteFilename,
                    sizeBytes = remote.sizeBytes,
                )
            }

            val projected =
                integrationRepository.projectCompletedRealtimeRun(
                    runId = runId,
                    recordingId = recordingId,
                    canonicalAssetId = directCanonical?.assetId,
                    canonicalSha256 = directCanonical?.sha256,
                    canonicalProfileId = CanonicalPcmProfile.PROFILE_ID,
                    canonicalPipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
                )
            searchIndexRebuilder.reindexRecording(recordingId)
            activeAnchor = null

            val initialResult =
                if (directCanonical != null) {
                    Stage16ALibraryReconciliationResult.COMPLETED_LOCAL_CANONICAL
                } else {
                    Stage16ALibraryReconciliationResult.REMOTE_SOURCE_DOWNLOAD_PENDING
                }
            val outcome =
                publishTerminal(
                    Stage16ATerminalReconciliationOutcome(
                        recordingId = recordingId,
                        remoteIdentity = remote.identity,
                        completeLocalWavRegistered = directCanonical != null,
                        realtimeTranscriptionId = projected.transcriptionId,
                        result = initialResult,
                    ),
                    segmentCount = reconciledSegments.size,
                    remoteFallbackState =
                        if (directCanonical != null) {
                            Stage16ARemoteFallbackState.NOT_REQUIRED
                        } else {
                            Stage16ARemoteFallbackState.PENDING
                        },
                )
            AppRecordingNavigation.publish(recordingId, RecordingDetailDestination.PLAYBACK)

            if (directCanonical == null) {
                scope.launch {
                    downloadRemoteFallback(recordingId, remote)
                }
            }
            outcome
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

    private suspend fun downloadRemoteFallback(
        recordingId: String,
        remote: RemoteDeviceFile,
    ) {
        if (integrationRepository.hasVerifiedDeviceOpus(recordingId)) {
            integrationRepository.clearRemotePendingAsset(recordingId)
            publishRemoteFallback(
                state = Stage16ARemoteFallbackState.DOWNLOADED,
                result = Stage16ALibraryReconciliationResult.COMPLETED_DEVICE_SOURCE,
                error = null,
            )
            return
        }
        val failure =
            runCatching {
                deviceRepository.downloadDeviceFile(remote, DeviceAudioFormat.OPUS)
            }.exceptionOrNull()
        val downloaded = integrationRepository.hasVerifiedDeviceOpus(recordingId)
        if (downloaded) {
            integrationRepository.clearRemotePendingAsset(recordingId)
            searchIndexRebuilder.reindexRecording(recordingId)
            publishRemoteFallback(
                state = Stage16ARemoteFallbackState.DOWNLOADED,
                result = Stage16ALibraryReconciliationResult.COMPLETED_DEVICE_SOURCE,
                error = null,
            )
        } else {
            publishRemoteFallback(
                state = Stage16ARemoteFallbackState.FAILED,
                result = Stage16ALibraryReconciliationResult.REMOTE_ONLY_PENDING_DOWNLOAD,
                error =
                    failure?.let { it.message ?: it.javaClass.simpleName }
                        ?: "device OPUS fallback download did not complete",
            )
        }
    }

    private fun publishRemoteFallback(
        state: Stage16ARemoteFallbackState,
        result: Stage16ALibraryReconciliationResult,
        error: String?,
    ) {
        latestSnapshot =
            latestSnapshot.copy(
                remoteFallbackState = state,
                libraryReconciliationResult = result,
                lastError = error,
            )
    }

    private suspend fun repairExistingRealtimeArtifacts() {
        var repairedLineages = 0L
        var repairedTranscripts = 0L
        integrationRepository.completedAttachedRuns().forEach { run ->
            val recordingId = run.recordingId ?: return@forEach
            val segments = realtimeRepository.getSegments(run.id)
            val recording = database.recordingDao().findWithAssets(recordingId) ?: return@forEach
            val canonical =
                recording.assets.firstOrNull { asset ->
                    asset.role == AudioAssetRole.CANONICAL_WAV &&
                        asset.integrityState == AudioIntegrityState.VERIFIED &&
                        asset.formatValidationState == AudioValidationState.VALID &&
                        asset.relativePath.startsWith("realtime/")
                }
            var canonicalResult: Stage16ADirectCanonicalResult? = null
            if (segments.size == 1 && canonical != null) {
                val file = managedRecordingFile(canonical.relativePath)
                if (file?.isFile == true && file.length() == canonical.sizeBytes) {
                    val existingLineage =
                        database.recordingDao().findDerivation(
                            recordingId = recordingId,
                            profileId = CanonicalPcmProfile.PROFILE_ID,
                            sourceSha256 = canonical.sha256,
                        )
                    integrationRepository.commitDirectCanonicalCapture(
                        RealtimeCanonicalCapture(
                            recordingId = recordingId,
                            relativePath = canonical.relativePath,
                            sizeBytes = canonical.sizeBytes,
                            sha256 = canonical.sha256,
                            profileId = CanonicalPcmProfile.PROFILE_ID,
                            pipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
                            sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                            channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                            verifiedAtMs = canonical.verifiedAtMs ?: clockMs(),
                        ),
                    )
                    if (existingLineage == null) repairedLineages += 1
                    canonicalResult = Stage16ADirectCanonicalResult(canonical.assetId, canonical.sha256)
                }
            }

            if (canonicalResult == null && segments.size > 1) {
                val remoteIdentity = recording.recording.sourceRemoteIdentity
                val remoteSize = remoteIdentity.remoteSizeBytesOrNull()
                if (remoteIdentity != null && remoteSize != null) {
                    integrationRepository.ensureRemotePendingAsset(
                        recordingId = recordingId,
                        remoteIdentity = remoteIdentity,
                        filename = recording.recording.originalFilename,
                        sizeBytes = remoteSize,
                    )
                }
            }

            val beforeId = "stage16a-realtime-${run.id}"
            val existed = database.transcriptionDao().findTranscription(beforeId) != null
            val projected =
                integrationRepository.projectCompletedRealtimeRun(
                    runId = run.id,
                    recordingId = recordingId,
                    canonicalAssetId = canonicalResult?.assetId,
                    canonicalSha256 = canonicalResult?.sha256,
                    canonicalProfileId = CanonicalPcmProfile.PROFILE_ID,
                    canonicalPipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
                )
            if (!existed && projected.transcriptionId != null) repairedTranscripts += 1
        }
        if (repairedLineages > 0 || repairedTranscripts > 0) {
            latestSnapshot =
                latestSnapshot.copy(
                    repairedCanonicalLineages =
                        latestSnapshot.repairedCanonicalLineages + repairedLineages,
                    repairedRealtimeTranscripts =
                        latestSnapshot.repairedRealtimeTranscripts + repairedTranscripts,
                )
        }
    }

    private fun managedRecordingFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate = runCatching { File(recordingsRoot, relativePath).canonicalFile }.getOrNull()
            ?: return null
        return candidate.takeIf {
            it.path == root.path || it.path.startsWith(root.path + File.separator)
        }
    }

    private suspend fun awaitFreshFileList(): DeviceFileListState? {
        val current = deviceRepository.deviceFileListState.value
        if (current.freshness.isTerminalFileListState()) return current
        return withTimeoutOrNull(FILE_LIST_WAIT_MS) {
            deviceRepository.deviceFileListState
                .filter { it.freshness.isTerminalFileListState() }
                .first()
        }
    }

    private fun FileListFreshness.isTerminalFileListState(): Boolean =
        this == FileListFreshness.FRESH ||
            this == FileListFreshness.EMPTY ||
            this == FileListFreshness.FAILED

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
                        displayName = RecordingDisplayNamePolicy.defaultDisplayName(remote.displayFilename),
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
        remote: RemoteDeviceFile,
        segments: List<RealtimeMediaSegmentEntity>,
    ): Stage16ADirectCanonicalResult? {
        if (segments.size != 1) return null
        val segment = segments.single()
        if (segment.state != RealtimeMediaSegmentStateValue.FINALIZED) return null
        val storedPath = segment.localRelativePath ?: return null
        val expectedSize = segment.sizeBytes ?: return null
        val expectedSha = segment.sha256 ?: return null
        val realtimeRoot = File(recordingsRoot, "realtime").canonicalFile
        val file = runCatching { File(realtimeRoot, storedPath).canonicalFile }.getOrNull() ?: return null
        if (file.path != realtimeRoot.path && !file.path.startsWith(realtimeRoot.path + File.separator)) {
            return null
        }
        if (!file.isFile || file.length() != expectedSize) return null
        val relativeToRecordings = file.relativeTo(recordingsRoot.canonicalFile).path
        val assetId =
            integrationRepository.commitDirectCanonicalCapture(
                RealtimeCanonicalCapture(
                    recordingId = recordingId,
                    relativePath = relativeToRecordings,
                    sizeBytes = expectedSize,
                    sha256 = expectedSha,
                    profileId = CanonicalPcmProfile.PROFILE_ID,
                    pipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
                    sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                    verifiedAtMs = clockMs(),
                ),
            )
        realtimeRepository.updateSegment(
            segment.copy(
                deviceFilename = remote.resolvedFilename ?: remote.displayFilename,
                isCompleteDeviceRecording = true,
            ),
        )
        return Stage16ADirectCanonicalResult(assetId = assetId, sha256 = expectedSha.lowercase())
    }

    private fun publishTerminal(
        outcome: Stage16ATerminalReconciliationOutcome,
        segmentCount: Int = latestSnapshot.localSegmentCount,
        remoteFallbackState: Stage16ARemoteFallbackState = latestSnapshot.remoteFallbackState,
    ): Stage16ATerminalReconciliationOutcome {
        latestSnapshot =
            latestSnapshot.copy(
                resolvedRemoteIdentity = outcome.remoteIdentity,
                resolvedRecordingId = outcome.recordingId,
                realtimeTranscriptionId = outcome.realtimeTranscriptionId,
                localSegmentCount = segmentCount,
                completeLocalWavRegistered = outcome.completeLocalWavRegistered,
                remoteFallbackState = remoteFallbackState,
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

    private fun String?.remoteSizeBytesOrNull(): Long? {
        val parts = this?.split('|') ?: return null
        if (parts.size < 4) return null
        return parts[parts.lastIndex - 1].toLongOrNull()?.takeIf { it > 0L }
    }

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val FILE_LIST_WAIT_MS = 5_000L
        const val ANCHOR_MAX_ATTEMPTS = 4
        const val ANCHOR_RETRY_MS = 250L
        const val TERMINAL_FILE_REFRESH_ATTEMPTS = 4
        const val TERMINAL_FILE_REFRESH_RETRY_MS = 350L
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
            "remoteFallback=${remoteFallbackState.name} libraryReconciliation=${libraryReconciliationResult.name}",
    )
    appendLine(
        "realtimeTranscriptionId=${realtimeTranscriptionId ?: "n/a"} " +
            "repairedLineages=$repairedCanonicalLineages repairedRealtimeTranscripts=$repairedRealtimeTranscripts",
    )
    append("lifecycleLastError=${lastError ?: "n/a"}")
}
