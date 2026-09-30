package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DownloadedAssetRegistry
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.ble.RegisteredDownloadedAsset
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.DownloadedDeviceAsset
import io.github.ioannes78.voica.database.LegacyAssetFormat
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import java.io.File
import java.security.MessageDigest

class RoomDownloadedAssetRegistry(
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val assetValidator: DeviceAudioAssetValidator,
    private val onRegistered: (String) -> Unit = {},
) : DownloadedAssetRegistry {
    override suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean = resolve(remoteIdentity, format) != null

    override suspend fun resolve(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): RegisteredDownloadedAsset? {
        val asset = repository.findDeviceAsset(
            remoteIdentity = remoteIdentity,
            format = format.toDatabaseFormat(),
        ) ?: return null

        val file = managedFile(asset.relativePath)
        if (file == null || !file.isFile || file.length() != asset.sizeBytes) {
            repository.markAssetIntegrity(
                assetId = asset.assetId,
                integrityState = AudioIntegrityState.MISSING,
                validationState = AudioValidationState.CORRUPTED,
            )
            return null
        }

        val actualSha = sha256(file)
        if (!actualSha.equals(asset.sha256, ignoreCase = true)) {
            repository.markAssetIntegrity(
                assetId = asset.assetId,
                integrityState = AudioIntegrityState.CORRUPTED,
                validationState = AudioValidationState.CORRUPTED,
            )
            return null
        }

        return RegisteredDownloadedAsset(
            file = file,
            sizeBytes = asset.sizeBytes,
            sha256 = asset.sha256,
        )
    }

    override suspend fun register(artifact: LocalRecordingArtifact): Result<Unit> =
        runCatching {
            val recordingId = repository.registerDownloadedDeviceAsset(
                DownloadedDeviceAsset(
                    sourceRemoteIdentity = artifact.sourceRemoteIdentity,
                    sourceDeviceAddress = artifact.sourceDeviceAddress,
                    sourceFormat = artifact.sourceFormat.toDatabaseFormat(),
                    displayFilename = artifact.displayFilename,
                    relativePath = "completed/${artifact.physicalFileName}",
                    recordedAtLocalIso = artifact.recordedAt?.toString(),
                    deviceReportedDurationMs = artifact.deviceReportedDurationMs,
                    downloadedAtMs = artifact.downloadedAtMs,
                    sizeBytes = artifact.sizeBytes,
                    sha256 = artifact.sha256,
                    container = artifact.container.name,
                ),
            )
            assetValidator.validate(
                remoteIdentity = artifact.sourceRemoteIdentity,
                format = artifact.sourceFormat.toDatabaseFormat(),
            )
            onRegistered(recordingId)
        }

    private fun managedFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate = runCatching {
            File(recordingsRoot, relativePath).canonicalFile
        }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path == root.path ||
                it.path.startsWith(root.path + File.separator)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}

private fun DeviceAudioFormat.toDatabaseFormat(): LegacyAssetFormat =
    when (this) {
        DeviceAudioFormat.OPUS -> LegacyAssetFormat.OPUS
        DeviceAudioFormat.WAV -> LegacyAssetFormat.WAV
    }
