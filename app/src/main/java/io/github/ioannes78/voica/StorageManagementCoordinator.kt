package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.database.CanonicalCleanupResult
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.RecordingStorageUsage
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.model.DownloadedModelVersionInfo
import io.github.ioannes78.voica.model.ModelManager
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageManagementSnapshot(
    val originalAudioBytes: Long,
    val canonicalAudioBytes: Long,
    val reclaimableCanonicalBytes: Long,
    val downloadedModelsBytes: Long,
    val databaseBytes: Long,
    val temporaryBytes: Long,
    val downloadedModels: List<DownloadedModelVersionInfo>,
) {
    val totalManagedBytes: Long
        get() =
            originalAudioBytes +
                canonicalAudioBytes +
                downloadedModelsBytes +
                databaseBytes +
                temporaryBytes
}

data class StorageCleanupOutcome(
    val reclaimedBytes: Long,
    val detail: String,
)

class StorageManagementCoordinator(
    application: Application,
    private val repository: RecordingLibraryRepository,
    private val canonicalAudioCoordinator: CanonicalAudioCoordinator,
    private val importCoordinator: LocalAudioImportCoordinator,
    private val exportCoordinator: LocalAudioExportCoordinator,
    private val modelManager: ModelManager,
    private val playbackController: PlaybackController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val app = application
    private val recordingsRoot = File(app.noBackupFilesDir, "recordings")
    private val modelRoot = File(app.noBackupFilesDir, "models")
    private val modelPackages = File(app.cacheDir, "model-packages")
    private val shareRoot = File(app.cacheDir, "share")

    suspend fun snapshot(): StorageManagementSnapshot =
        withContext(ioDispatcher) {
            val recordingUsage: RecordingStorageUsage = repository.recordingStorageUsage()
            val downloadedModels = modelManager.downloadedVersions()
            StorageManagementSnapshot(
                originalAudioBytes = recordingUsage.originalAudioBytes,
                canonicalAudioBytes = recordingUsage.canonicalAudioBytes,
                reclaimableCanonicalBytes = recordingUsage.reclaimableCanonicalBytes,
                downloadedModelsBytes = downloadedModels.sumOf { it.sizeBytes },
                databaseBytes = databasePhysicalBytes(),
                temporaryBytes = temporaryPhysicalBytes(),
                downloadedModels = downloadedModels,
            )
        }

    suspend fun cleanupSafeTemporaryFiles(): StorageCleanupOutcome {
        val before = withContext(ioDispatcher) { temporaryPhysicalBytes() }

        importCoordinator.cleanupStaleStaging()
        exportCoordinator.cleanupStaleShareCache()
        canonicalAudioCoordinator.cleanupTransientFiles()
        modelManager.cleanupTransientStorage()
        withContext(ioDispatcher) {
            cleanupStaleDeviceDownloadParts()
        }

        val after = withContext(ioDispatcher) { temporaryPhysicalBytes() }
        val reclaimed = (before - after).coerceAtLeast(0L)
        return StorageCleanupOutcome(
            reclaimedBytes = reclaimed,
            detail = "已清理可安全删除的临时文件",
        )
    }

    suspend fun cleanupReclaimableCanonical(): StorageCleanupOutcome {
        val candidates = repository.reclaimableCanonicalCandidates()
        candidates
            .map { it.recordingId }
            .distinct()
            .forEach { recordingId ->
                if (playbackController.snapshot.value.recordingId == recordingId) {
                    playbackController.unload()
                }
                canonicalAudioCoordinator.cancelAndAwait(recordingId)
            }

        val cleanup: CanonicalCleanupResult =
            withContext(ioDispatcher) {
                repository.cleanupReclaimableCanonicalAudio()
            }
        val detail =
            if (cleanup.failedPaths.isEmpty()) {
                "已清理 ${cleanup.deletedAssets} 个可重新生成的标准化音频"
            } else {
                "已清理 ${cleanup.deletedAssets} 个；${cleanup.failedPaths.size} 个未能清理"
            }
        return StorageCleanupOutcome(
            reclaimedBytes = cleanup.reclaimedBytes,
            detail = detail,
        )
    }

    suspend fun removeDownloadedModel(
        modelId: String,
        version: String,
        revision: Long,
    ): StorageCleanupOutcome {
        val item =
            modelManager.downloadedVersions().firstOrNull {
                it.modelId == modelId &&
                    it.version == version &&
                    it.revision == revision
            } ?: return StorageCleanupOutcome(
                reclaimedBytes = 0L,
                detail = "模型版本已不存在",
            )

        check(!item.active) { "当前正在使用的模型不能从存储清理中删除" }
        check(!item.previous) { "用于回滚的模型版本不能从存储清理中删除" }
        check(!item.inUse) { "模型正在被转写或说话人任务使用" }

        modelManager.removeDownloadedVersion(
            modelId = modelId,
            version = version,
            revision = revision,
        )
        return StorageCleanupOutcome(
            reclaimedBytes = item.sizeBytes,
            detail = "已删除 ${item.displayName} ${item.version}",
        )
    }

    private fun databasePhysicalBytes(): Long {
        val db = app.getDatabasePath(VoicaDatabase.DATABASE_NAME)
        return sequenceOf(
            db,
            File(db.path + "-wal"),
            File(db.path + "-shm"),
        ).filter(File::isFile).sumOf(File::length)
    }

    private fun temporaryPhysicalBytes(): Long {
        val candidates =
            listOf(
                File(recordingsRoot, "temp"),
                File(recordingsRoot, "import-staging"),
                File(recordingsRoot, "canonical"),
                File(modelRoot, ".staging"),
                modelPackages,
                shareRoot,
            )
        return candidates.sumOf { root ->
            when (root.name) {
                "canonical" ->
                    root.listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.name.endsWith(".part") }
                        .sumOf(File::length)

                else -> physicalBytes(root)
            }
        }
    }

    private fun cleanupStaleDeviceDownloadParts() {
        val cutoff = nowMs() - STALE_TEMP_AGE_MS
        File(recordingsRoot, "temp")
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(".part") &&
                    it.lastModified() < cutoff
            }
            .forEach(File::delete)
    }

    private fun physicalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf(::physicalBytes)
    }

    private companion object {
        const val STALE_TEMP_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
