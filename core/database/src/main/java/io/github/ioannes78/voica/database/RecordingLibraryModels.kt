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

data class RecordingDerivation(
    val profileId: String,
    val sourceSha256: String,
    val sourceAssetId: String,
    val outputAssetId: String?,
    val pipelineVersion: Int,
    val state: String,
    val errorCode: String?,
    val errorDetail: String?,
)

data class RecordingLibraryItem(
    val id: String,
    val sourceRemoteIdentity: String?,
    val sourceDeviceAddress: String?,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long?,
    val state: String,
    val assets: List<RecordingAsset>,
    val derivations: List<RecordingDerivation>,
    val sourceType: String = RecordingSourceType.DEVICE_DOWNLOAD,
    val mediaDurationMs: Long? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
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
        sourceType = recording.sourceType,
        mediaDurationMs = recording.mediaDurationMs,
        createdAtMs = recording.createdAtMs,
        updatedAtMs = recording.updatedAtMs,
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
        derivations = derivations.map { derivation ->
            RecordingDerivation(
                profileId = derivation.profileId,
                sourceSha256 = derivation.sourceSha256,
                sourceAssetId = derivation.sourceAssetId,
                outputAssetId = derivation.outputAssetId,
                pipelineVersion = derivation.pipelineVersion,
                state = derivation.state,
                errorCode = derivation.errorCode,
                errorDetail = derivation.errorDetail,
            )
        },
    )
