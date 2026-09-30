package io.github.ioannes78.voica.database

data class RecordingAsset(
    val assetId: String,
    val role: String,
    val relativePath: String,
    val container: String,
    val codec: String?,
    val sampleFormat: String?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val sizeBytes: Long,
    val sha256: String,
    val integrityState: String,
    val formatValidationState: String,
)

data class RecordingLibraryItem(
    val id: String,
    val sourceRemoteIdentity: String,
    val sourceDeviceAddress: String,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long,
    val state: String,
    val assets: List<RecordingAsset>,
)

internal fun RecordingWithAssets.toLibraryItem(): RecordingLibraryItem =
    RecordingLibraryItem(
        id = recording.id,
        sourceRemoteIdentity = recording.sourceRemoteIdentity,
        sourceDeviceAddress = recording.sourceDeviceAddress,
        originalFilename = recording.originalFilename,
        displayName = recording.displayName,
        recordedAtLocalIso = recording.recordedAtLocalIso,
        deviceReportedDurationMs = recording.deviceReportedDurationMs,
        downloadedAtMs = recording.downloadedAtMs,
        state = recording.state,
        assets = assets.map { asset ->
            RecordingAsset(
                assetId = asset.assetId,
                role = asset.role,
                relativePath = asset.relativePath,
                container = asset.container,
                codec = asset.codec,
                sampleFormat = asset.sampleFormat,
                sampleRateHz = asset.sampleRateHz,
                channelCount = asset.channelCount,
                sizeBytes = asset.sizeBytes,
                sha256 = asset.sha256,
                integrityState = asset.integrityState,
                formatValidationState = asset.formatValidationState,
            )
        },
    )
