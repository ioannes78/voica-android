package io.github.ioannes78.voica.database

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Properties

enum class LegacyAssetFormat {
    OPUS,
    WAV,
}

data class LegacyStage5Candidate(
    val legacyAssetId: String,
    val recordingId: String,
    val sourceRemoteIdentity: String,
    val sourceDeviceAddress: String,
    val sourceFormat: LegacyAssetFormat,
    val displayFilename: String,
    val physicalFileName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val legacyContainerHint: String,
    val relativePath: String,
)

data class LegacyScanIssue(
    val sourcePath: String,
    val code: String,
    val detail: String? = null,
)

data class LegacyScanResult(
    val candidates: List<LegacyStage5Candidate>,
    val issues: List<LegacyScanIssue>,
)

class LegacyStage5Scanner(
    private val recordingsRoot: File,
) {
    private val completedDir = File(recordingsRoot, "completed")

    fun scan(): LegacyScanResult {
        if (!completedDir.isDirectory) return LegacyScanResult(emptyList(), emptyList())

        val candidates = mutableListOf<LegacyStage5Candidate>()
        val issues = mutableListOf<LegacyScanIssue>()
        val metadataFiles = completedDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
            .sortedBy { it.name }
        val metadataReferencedAudioNames = metadataFiles.mapNotNullTo(mutableSetOf()) { metadata ->
            runCatching {
                Properties().also { props ->
                    FileInputStream(metadata).use(props::load)
                }.getProperty("physicalFileName")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        metadataFiles.forEach { metadata ->
            when (val result = readCandidate(metadata)) {
                is CandidateResult.Valid -> candidates += result.candidate
                is CandidateResult.Invalid -> issues += result.issue
            }
        }

        val referenced = candidates.mapTo(mutableSetOf()) { File(it.relativePath).name }
        completedDir.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(METADATA_SUFFIX) &&
                    !file.name.endsWith(METADATA_TEMP_SUFFIX) &&
                    file.name !in referenced &&
                    file.name !in metadataReferencedAudioNames
            }
            .forEach { orphan ->
                issues += LegacyScanIssue(
                    sourcePath = orphan.absolutePath,
                    code = "LEGACY_ORPHAN_AUDIO",
                    detail = "audio file has no valid Stage 5 metadata",
                )
            }

        return LegacyScanResult(candidates, issues)
    }

    private fun readCandidate(metadataFile: File): CandidateResult {
        val properties = runCatching {
            Properties().also { props ->
                FileInputStream(metadataFile).use(props::load)
            }
        }.getOrElse { error ->
            return invalid(metadataFile, "LEGACY_METADATA_INVALID", error.message)
        }

        fun required(key: String): String? =
            properties.getProperty(key)?.takeIf { it.isNotBlank() }

        val legacyId = required("id")
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing id")
        val remoteIdentity = required("sourceRemoteIdentity")
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing sourceRemoteIdentity")
        val deviceAddress = required("sourceDeviceAddress")
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing sourceDeviceAddress")
        val displayFilename = required("displayFilename")
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing displayFilename")
        val physicalFileName = required("physicalFileName")
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing physicalFileName")
        val downloadedAtMs = required("downloadedAtMs")?.toLongOrNull()
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "invalid downloadedAtMs")
        val sizeBytes = required("sizeBytes")?.toLongOrNull()
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "invalid sizeBytes")
        val expectedSha = required("sha256")?.lowercase()
            ?: return invalid(metadataFile, "LEGACY_METADATA_INVALID", "missing sha256")
        val legacyContainer = required("container") ?: "UNKNOWN"
        val recordedAt = properties.getProperty("recordedAt")
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching { LocalDateTime.parse(value).toString() }.getOrElse {
                    return invalid(metadataFile, "LEGACY_METADATA_INVALID", "invalid recordedAt")
                }
            }

        val sourceFormat = properties.getProperty("sourceFormat")
            ?.let { runCatching { LegacyAssetFormat.valueOf(it) }.getOrNull() }
            ?: when {
                displayFilename.endsWith(".wav", ignoreCase = true) -> LegacyAssetFormat.WAV
                else -> LegacyAssetFormat.OPUS
            }

        val audioFile = File(completedDir, physicalFileName)
        if (!audioFile.isFile) {
            return invalid(metadataFile, "RAW_FILE_MISSING", physicalFileName)
        }
        if (audioFile.length() != sizeBytes) {
            return invalid(
                metadataFile,
                "RAW_SIZE_MISMATCH",
                "expected=$sizeBytes actual=${audioFile.length()}",
            )
        }

        val actualSha = runCatching { sha256(audioFile) }.getOrElse { error ->
            return invalid(metadataFile, "LEGACY_MIGRATION_FAILED", error.message)
        }
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            return invalid(
                metadataFile,
                "RAW_SHA_MISMATCH",
                "expected=$expectedSha actual=$actualSha",
            )
        }

        val recordingId = stableRecordingId(remoteIdentity)
        if (sourceFormat == LegacyAssetFormat.OPUS && legacyId != recordingId) {
            return invalid(
                metadataFile,
                "LEGACY_ID_MISMATCH",
                "metadata=$legacyId computed=$recordingId",
            )
        }

        return CandidateResult.Valid(
            LegacyStage5Candidate(
                legacyAssetId = legacyId,
                recordingId = recordingId,
                sourceRemoteIdentity = remoteIdentity,
                sourceDeviceAddress = deviceAddress,
                sourceFormat = sourceFormat,
                displayFilename = displayFilename,
                physicalFileName = physicalFileName,
                recordedAtLocalIso = recordedAt,
                deviceReportedDurationMs = durationFromIdentity(remoteIdentity),
                downloadedAtMs = downloadedAtMs,
                sizeBytes = sizeBytes,
                sha256 = actualSha,
                legacyContainerHint = legacyContainer,
                relativePath = "completed/$physicalFileName",
            ),
        )
    }

    private fun durationFromIdentity(remoteIdentity: String): Long? =
        remoteIdentity.substringAfterLast('|', missingDelimiterValue = "")
            .toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.times(1_000L)

    private fun stableRecordingId(remoteIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(remoteIdentity.encodeToByteArray())
            .toHex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun invalid(file: File, code: String, detail: String?) =
        CandidateResult.Invalid(
            LegacyScanIssue(file.absolutePath, code, detail),
        )

    private sealed interface CandidateResult {
        data class Valid(val candidate: LegacyStage5Candidate) : CandidateResult
        data class Invalid(val issue: LegacyScanIssue) : CandidateResult
    }

    private companion object {
        const val METADATA_SUFFIX = ".properties"
        const val METADATA_TEMP_SUFFIX = ".properties.part"
        const val BUFFER_SIZE = 64 * 1024
    }
}
