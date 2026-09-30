package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LegacyImportReport(
    val skippedBecauseAlreadyImported: Boolean,
    val importedRecordings: Int,
    val importedAssets: Int,
    val diagnostics: Int,
)

data class LibraryDeleteResult(
    val deleted: Boolean,
    val failedPaths: List<String> = emptyList(),
)

data class DownloadedDeviceAsset(
    val sourceRemoteIdentity: String,
    val sourceDeviceAddress: String,
    val sourceFormat: LegacyAssetFormat,
    val displayFilename: String,
    val relativePath: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val container: String,
)

class RecordingLibraryRepository(
    private val database: VoicaDatabase,
    private val recordingsRoot: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.recordingDao()

    val recordings: Flow<List<RecordingLibraryItem>> =
        dao.observeAll().map { rows -> rows.map(RecordingWithAssets::toLibraryItem) }

    suspend fun importLegacyStage5IfNeeded(): LegacyImportReport {
        if (dao.readMeta(LEGACY_IMPORT_META_KEY) == LEGACY_IMPORT_VERSION) {
            return LegacyImportReport(
                skippedBecauseAlreadyImported = true,
                importedRecordings = 0,
                importedAssets = 0,
                diagnostics = 0,
            )
        }

        val scan = LegacyStage5Scanner(recordingsRoot).scan()
        var insertedRecordings = 0
        var insertedAssets = 0
        val now = nowMs()

        database.withTransaction {
            scan.candidates
                .groupBy { it.recordingId }
                .forEach { (_, assets) ->
                    val preferred = assets.firstOrNull {
                        it.sourceFormat == LegacyAssetFormat.OPUS
                    } ?: assets.first()
                    val recording = preferred.toRecordingEntity(now)
                    if (dao.insertRecordingIgnore(recording) != -1L) {
                        insertedRecordings += 1
                    }
                    assets.forEach { candidate ->
                        if (dao.insertAssetIgnore(candidate.toAssetEntity(now)) != -1L) {
                            insertedAssets += 1
                        }
                    }
                }

            scan.issues.forEach { issue ->
                dao.insertMigrationDiagnostic(
                    MigrationDiagnosticEntity(
                        sourcePath = issue.sourcePath,
                        code = issue.code,
                        detail = issue.detail,
                        observedAtMs = now,
                    ),
                )
            }

            dao.putMeta(
                LibraryMetaEntity(
                    key = LEGACY_IMPORT_META_KEY,
                    value = LEGACY_IMPORT_VERSION,
                    updatedAtMs = now,
                ),
            )
        }

        return LegacyImportReport(
            skippedBecauseAlreadyImported = false,
            importedRecordings = insertedRecordings,
            importedAssets = insertedAssets,
            diagnostics = scan.issues.size,
        )
    }

    suspend fun registerDownloadedDeviceAsset(asset: DownloadedDeviceAsset) {
        require(asset.sizeBytes >= 0L) { "sizeBytes must be non-negative" }
        require(SHA256.matches(asset.sha256.lowercase())) { "invalid SHA-256" }
        val recordingId = stableRecordingId(asset.sourceRemoteIdentity)
        val role = when (asset.sourceFormat) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        }
        val assetId = when (asset.sourceFormat) {
            LegacyAssetFormat.OPUS -> recordingId
            LegacyAssetFormat.WAV -> stableRecordingId(asset.sourceRemoteIdentity + "|format=WAV")
        }
        val now = nowMs()

        database.withTransaction {
            dao.insertRecordingIgnore(
                RecordingEntity(
                    id = recordingId,
                    sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                    sourceRemoteIdentity = asset.sourceRemoteIdentity,
                    sourceDeviceAddress = asset.sourceDeviceAddress,
                    originalFilename = asset.displayFilename,
                    displayName = asset.displayFilename,
                    recordedAtLocalIso = asset.recordedAtLocalIso,
                    deviceReportedDurationMs = asset.deviceReportedDurationMs,
                    downloadedAtMs = asset.downloadedAtMs,
                    createdAtMs = asset.downloadedAtMs,
                    updatedAtMs = now,
                ),
            )
            dao.insertAssetIgnore(
                AudioAssetEntity(
                    assetId = assetId,
                    recordingId = recordingId,
                    role = role,
                    relativePath = asset.relativePath,
                    container = asset.container,
                    codec = if (asset.sourceFormat == LegacyAssetFormat.OPUS) "OPUS" else null,
                    sampleFormat = null,
                    sampleRateHz = null,
                    channelCount = null,
                    sizeBytes = asset.sizeBytes,
                    sha256 = asset.sha256.lowercase(),
                    integrityState = AudioIntegrityState.VERIFIED,
                    formatValidationState = AudioValidationState.UNVERIFIED,
                    createdAtMs = asset.downloadedAtMs,
                    verifiedAtMs = now,
                ),
            )
        }
    }

    suspend fun isDeviceAssetAvailable(
        remoteIdentity: String,
        format: LegacyAssetFormat,
    ): Boolean {
        val recordingId = stableRecordingId(remoteIdentity)
        val role = when (format) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        }
        val asset = dao.findAsset(recordingId, role) ?: return false
        val file = File(recordingsRoot, asset.relativePath)
        return asset.integrityState == AudioIntegrityState.VERIFIED &&
            file.isFile &&
            file.length() == asset.sizeBytes
    }

    suspend fun rename(recordingId: String, requestedName: String): Boolean {
        val displayName = sanitizeDisplayName(requestedName) ?: return false
        return dao.rename(recordingId, displayName, nowMs()) == 1
    }

    suspend fun deleteLocalRecording(recordingId: String): LibraryDeleteResult {
        val recording = dao.findWithAssets(recordingId)
            ?: return LibraryDeleteResult(deleted = true)
        dao.updateState(recordingId, RecordingState.DELETING, nowMs())

        val failed = deleteManagedFiles(recording)
        return if (failed.isEmpty()) {
            dao.deleteRecording(recordingId)
            LibraryDeleteResult(deleted = true)
        } else {
            LibraryDeleteResult(deleted = false, failedPaths = failed)
        }
    }

    suspend fun reconcilePendingDeletes() {
        dao.findByState(RecordingState.DELETING).forEach { recording ->
            val failed = deleteManagedFiles(recording)
            if (failed.isEmpty()) {
                dao.deleteRecording(recording.recording.id)
            }
        }
    }

    private fun deleteManagedFiles(recording: RecordingWithAssets): List<String> {
        val candidates = buildList {
            recording.assets.forEach { asset ->
                add(File(recordingsRoot, asset.relativePath))
                if (asset.relativePath.startsWith("completed/")) {
                    add(File(recordingsRoot, "completed/${asset.assetId}.properties"))
                    add(File(recordingsRoot, "completed/${asset.assetId}.properties.part"))
                }
            }
        }.distinctBy { it.path }

        return candidates.mapNotNull { file ->
            if (!isManagedPath(file)) {
                file.absolutePath
            } else if (!file.exists() || file.delete()) {
                null
            } else {
                file.absolutePath
            }
        }
    }

    suspend fun migrationDiagnostics(): List<MigrationDiagnosticEntity> =
        dao.migrationDiagnostics()

    private fun sanitizeDisplayName(value: String): String? {
        val cleaned = value
            .replace(' ', '_')
            .map { ch -> if (ch.isISOControl()) '_' else ch }
            .joinToString("")
            .trim()
            .take(MAX_DISPLAY_NAME)
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun isManagedPath(file: File): Boolean {
        val root = recordingsRoot.canonicalFile
        val candidate = file.canonicalFile
        return candidate.path == root.path ||
            candidate.path.startsWith(root.path + File.separator)
    }

    private fun stableRecordingId(remoteIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(remoteIdentity.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val LEGACY_IMPORT_META_KEY = "legacy_stage5_import"
        const val LEGACY_IMPORT_VERSION = "1"
        const val MAX_DISPLAY_NAME = 160
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun LegacyStage5Candidate.toRecordingEntity(now: Long): RecordingEntity =
    RecordingEntity(
        id = recordingId,
        sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
        sourceRemoteIdentity = sourceRemoteIdentity,
        sourceDeviceAddress = sourceDeviceAddress,
        originalFilename = displayFilename,
        displayName = displayFilename,
        recordedAtLocalIso = recordedAtLocalIso,
        deviceReportedDurationMs = deviceReportedDurationMs,
        downloadedAtMs = downloadedAtMs,
        createdAtMs = downloadedAtMs,
        updatedAtMs = now,
    )

private fun LegacyStage5Candidate.toAssetEntity(now: Long): AudioAssetEntity =
    AudioAssetEntity(
        assetId = legacyAssetId,
        recordingId = recordingId,
        role = when (sourceFormat) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        },
        relativePath = relativePath,
        container = legacyContainerHint,
        codec = if (sourceFormat == LegacyAssetFormat.OPUS) "OPUS" else null,
        sampleFormat = null,
        sampleRateHz = null,
        channelCount = null,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        integrityState = AudioIntegrityState.VERIFIED,
        formatValidationState = AudioValidationState.LEGACY_HINT,
        createdAtMs = downloadedAtMs,
        verifiedAtMs = now,
    )
