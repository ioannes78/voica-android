package io.github.ioannes78.voica.ble

import java.io.File

data class RegisteredDownloadedAsset(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

interface DownloadedAssetRegistry {
    suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean

    suspend fun resolve(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): RegisteredDownloadedAsset?

    suspend fun register(artifact: LocalRecordingArtifact): Result<Unit>
}

object NoOpDownloadedAssetRegistry : DownloadedAssetRegistry {
    override suspend fun isAvailable(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): Boolean = false

    override suspend fun resolve(
        remoteIdentity: String,
        format: DeviceAudioFormat,
    ): RegisteredDownloadedAsset? = null

    override suspend fun register(artifact: LocalRecordingArtifact): Result<Unit> =
        Result.success(Unit)
}
