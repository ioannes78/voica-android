package io.github.ioannes78.voica.database

data class AudioDuplicateMatch(
    val recordingId: String,
    val displayName: String,
    val role: String,
    val relativePath: String,
)

data class ImportedOriginalRegistration(
    val originalFilename: String,
    val displayName: String,
    val relativePath: String,
    val sourceMimeType: String?,
    val container: String,
    val codec: String?,
    val sampleFormat: String?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val mediaDurationMs: Long?,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtMs: Long,
    val providerAuthority: String?,
    val sourceLastModifiedMs: Long?,
)
