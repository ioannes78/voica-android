package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FilenameResolution
import java.time.LocalDateTime

enum class DeviceAudioFormat(val extension: String) {
    OPUS("opus"),
    WAV("wav"),
}

enum class WavSizeProbeState {
    NOT_PROBED,
    PROBING,
    AVAILABLE,
    UNAVAILABLE,
}

enum class FileListFreshness {
    NOT_LOADED,
    LOADING,
    FRESH,
    EMPTY,
    STALE,
    FAILED,
}

enum class FileListErrorCode {
    NOT_READY,
    RECORDING_ACTIVE,
    WRITE_FAILED,
    FIRST_RESPONSE_TIMEOUT,
    SESSION_TIMEOUT,
    MALFORMED_PAYLOAD,
    DISCONNECTED,
    SESSION_REPLACED,
}

data class FileListError(
    val code: FileListErrorCode,
    val detail: String? = null,
)

data class RemoteDeviceFile(
    val identity: String,
    val identityProvisional: Boolean,
    val deviceAddress: String,
    val rawTimeValue: Long,
    val durationSeconds: Long? = null,
    val sizeBytes: Long,
    val wavSizeBytes: Long? = null,
    val wavSizeProbeState: WavSizeProbeState = WavSizeProbeState.NOT_PROBED,
    val rawFilename: String,
    val resolvedFilename: String?,
    val filenameResolution: FilenameResolution,
    val filenameFieldLength: Int,
    val recordedAt: LocalDateTime?,
    val deviceOrder: Int,
    val rawListEntryBytes: ByteArray = byteArrayOf(),
) {
    val displayFilename: String
        get() = resolvedFilename ?: rawFilename

    fun downloadFilename(format: DeviceAudioFormat): String? {
        val sourceName = resolvedFilename ?: return null
        val currentExtension = sourceName.substringAfterLast('.', missingDelimiterValue = "")
        if (currentExtension.equals(format.extension, ignoreCase = true)) return sourceName

        val stem = sourceName.substringBeforeLast('.', missingDelimiterValue = "")
        val mayProjectSibling =
            filenameResolution == FilenameResolution.RecoveredStandardOpusName ||
                filenameResolution == FilenameResolution.DeviceFullName
        return if (mayProjectSibling && STANDARD_RECORDING_STEM.matches(stem)) {
            "$stem.${format.extension}"
        } else {
            null
        }
    }

    val availableDownloadFormats: List<DeviceAudioFormat>
        get() = DeviceAudioFormat.entries.filter { downloadFilename(it) != null }
}

private val STANDARD_RECORDING_STEM =
    Regex("^note\\d{8}-\\d{6}$", RegexOption.IGNORE_CASE)

data class DeviceFileListState(
    val deviceAddress: String? = null,
    val files: List<RemoteDeviceFile> = emptyList(),
    val freshness: FileListFreshness = FileListFreshness.NOT_LOADED,
    val lastUpdatedTimeMs: Long? = null,
    val activeSessionId: Long? = null,
    val lastError: FileListError? = null,
)

enum class FileListCompletionReason {
    LIST_DONE,
    WRITE_FAILED,
    FIRST_RESPONSE_TIMEOUT,
    SESSION_TIMEOUT,
    MALFORMED_PAYLOAD,
    DISCONNECTED,
    SESSION_REPLACED,
}

data class FileListDiagnostics(
    val sessionId: Long? = null,
    val transportSessionId: Long? = null,
    val requestSequence: Int? = null,
    val dataFrameCount: Int = 0,
    val declaredEntryCount: Int = 0,
    val parsedEntryCount: Int = 0,
    val lastDataNotificationSource: NotificationSource? = null,
    val listDoneNotificationSource: NotificationSource? = null,
    val lastDataBodySize: Int? = null,
    val listDoneBodySize: Int? = null,
    val lastFilenameFieldLength: Int? = null,
    val receivedListDone: Boolean = false,
    val newestRawFilename: String? = null,
    val newestResolvedFilename: String? = null,
    val newestFilenameResolution: String? = null,
    val newestRawTimeValue: Long? = null,
    val newestSizeBytes: Long? = null,
    val lastMalformedReason: String? = null,
    val startedAtMs: Long? = null,
    val durationMs: Long? = null,
    val completionReason: FileListCompletionReason? = null,
    val lastOperationError: String? = null,
)
