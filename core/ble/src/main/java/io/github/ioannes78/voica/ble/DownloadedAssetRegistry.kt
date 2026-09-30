package io.github.ioannes78.voica.ble

interface DownloadedAssetRegistry {
    suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean

    suspend fun register(artifact: LocalRecordingArtifact): Result<Unit>
}

object NoOpDownloadedAssetRegistry : DownloadedAssetRegistry {
    override suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean = false

    override suspend fun register(artifact: LocalRecordingArtifact): Result<Unit> =
        Result.success(Unit)
}
