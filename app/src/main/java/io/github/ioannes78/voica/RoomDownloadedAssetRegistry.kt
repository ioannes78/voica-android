package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DownloadedAssetRegistry
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.database.DownloadedDeviceAsset
import io.github.ioannes78.voica.database.LegacyAssetFormat
import io.github.ioannes78.voica.database.RecordingLibraryRepository

class RoomDownloadedAssetRegistry(
    private val repository: RecordingLibraryRepository,
) : DownloadedAssetRegistry {
    override suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean = repository.isDeviceAssetAvailable(
        remoteIdentity = remoteIdentity,
        format = format.toDatabaseFormat(),
    )

    override suspend fun register(artifact: LocalRecordingArtifact): Result<Unit> =
        runCatching {
            repository.registerDownloadedDeviceAsset(
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
        }
}

private fun DeviceAudioFormat.toDatabaseFormat(): LegacyAssetFormat =
    when (this) {
        DeviceAudioFormat.OPUS -> LegacyAssetFormat.OPUS
        DeviceAudioFormat.WAV -> LegacyAssetFormat.WAV
    }
