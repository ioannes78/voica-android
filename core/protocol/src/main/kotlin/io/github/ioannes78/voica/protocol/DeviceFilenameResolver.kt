package io.github.ioannes78.voica.protocol

enum class FilenameResolution {
    DeviceFullName,
    RecoveredStandardOpusName,
    RawCompleteName,
    Unresolved,
    Malformed,
}

data class FilenameResolutionResult(
    val rawFilename: String,
    val resolvedFilename: String?,
    val resolution: FilenameResolution,
)

object DeviceFilenameResolver {
    private val standardFullName =
        Regex("^note\\d{8}-\\d{6}\\.(?:opus|wav)$", RegexOption.IGNORE_CASE)
    private val standardTruncatedOpus =
        Regex("^note\\d{8}-\\d{6}\\.$")

    fun resolve(rawFilename: String): FilenameResolutionResult {
        if (rawFilename.isBlank() || rawFilename.any { it.code < 0x20 }) {
            return FilenameResolutionResult(
                rawFilename = rawFilename,
                resolvedFilename = null,
                resolution = FilenameResolution.Malformed,
            )
        }

        if (standardFullName.matches(rawFilename)) {
            return FilenameResolutionResult(
                rawFilename = rawFilename,
                resolvedFilename = rawFilename,
                resolution = FilenameResolution.DeviceFullName,
            )
        }

        if (standardTruncatedOpus.matches(rawFilename)) {
            return FilenameResolutionResult(
                rawFilename = rawFilename,
                resolvedFilename = rawFilename + "opus",
                resolution = FilenameResolution.RecoveredStandardOpusName,
            )
        }

        if (!rawFilename.endsWith('.')) {
            return FilenameResolutionResult(
                rawFilename = rawFilename,
                resolvedFilename = rawFilename,
                resolution = FilenameResolution.RawCompleteName,
            )
        }

        return FilenameResolutionResult(
            rawFilename = rawFilename,
            resolvedFilename = null,
            resolution = FilenameResolution.Unresolved,
        )
    }
}
