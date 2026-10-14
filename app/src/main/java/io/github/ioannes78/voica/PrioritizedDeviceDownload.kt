package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileOperationError
import io.github.ioannes78.voica.ble.FileOperationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class PrioritizedDeviceDownloadResult(
    val completed: Boolean,
    val attempts: Int,
    val busyWaits: Int,
    val terminalError: String?,
)

/**
 * Waits out lower-priority file work before starting a real device download.
 *
 * The BLE repository intentionally serializes REFRESH/DOWNLOAD/WAV_HEADER_PROBE operations. Its
 * public download entry returns immediately when another operation currently owns that coordinator.
 * A file-list completion can schedule a WAV header probe at the same time Stage 16A requests its
 * complete recorder OPUS, so a one-shot call can be silently lost. This app-layer arbiter retries
 * only when no terminal DOWNLOAD state was produced. Real DOWNLOAD failure/cancellation is terminal
 * and is never blindly retried here.
 */
internal suspend fun runPrioritizedDeviceDownload(
    fileOperationState: StateFlow<FileOperationState>,
    remoteIdentity: String,
    format: DeviceAudioFormat,
    download: suspend () -> Unit,
    maxAttempts: Int = 6,
    overallTimeoutMs: Long = 45_000L,
    retryDelayMs: Long = 120L,
): PrioritizedDeviceDownloadResult {
    require(remoteIdentity.isNotBlank())
    require(maxAttempts > 0)
    require(overallTimeoutMs > 0L)
    require(retryDelayMs >= 0L)

    var attempts = 0
    var busyWaits = 0
    var lastError: String? = null

    val result =
        withTimeoutOrNull(overallTimeoutMs) {
            while (attempts < maxAttempts) {
                while (fileOperationState.value is FileOperationState.Active) {
                    busyWaits += 1
                    fileOperationState
                        .filter { state -> state !is FileOperationState.Active }
                        .first()
                    if (retryDelayMs > 0L) delay(retryDelayMs)
                }

                attempts += 1
                val thrown = runCatching { download() }.exceptionOrNull()
                if (thrown != null) {
                    lastError = thrown.message ?: thrown.javaClass.simpleName
                    return@withTimeoutOrNull PrioritizedDeviceDownloadResult(
                        completed = false,
                        attempts = attempts,
                        busyWaits = busyWaits,
                        terminalError = lastError,
                    )
                }

                when (val state = fileOperationState.value) {
                    is FileOperationState.Completed -> {
                        if (state.matchesDownload(remoteIdentity, format)) {
                            return@withTimeoutOrNull PrioritizedDeviceDownloadResult(
                                completed = true,
                                attempts = attempts,
                                busyWaits = busyWaits,
                                terminalError = null,
                            )
                        }
                    }

                    is FileOperationState.Failed -> {
                        if (state.matchesDownload(remoteIdentity, format)) {
                            lastError = state.error.toDiagnosticText()
                            return@withTimeoutOrNull PrioritizedDeviceDownloadResult(
                                completed = false,
                                attempts = attempts,
                                busyWaits = busyWaits,
                                terminalError = lastError,
                            )
                        }
                    }

                    is FileOperationState.Cancelled -> {
                        if (state.matchesDownload(remoteIdentity, format)) {
                            lastError = state.reason.toDiagnosticText()
                            return@withTimeoutOrNull PrioritizedDeviceDownloadResult(
                                completed = false,
                                attempts = attempts,
                                busyWaits = busyWaits,
                                terminalError = lastError,
                            )
                        }
                    }

                    is FileOperationState.OutcomeUnknown -> {
                        if (
                            state.operation == DeviceFileOperationType.DOWNLOAD &&
                            state.remoteIdentity == remoteIdentity
                        ) {
                            lastError = state.error.toDiagnosticText()
                            return@withTimeoutOrNull PrioritizedDeviceDownloadResult(
                                completed = false,
                                attempts = attempts,
                                busyWaits = busyWaits,
                                terminalError = lastError,
                            )
                        }
                    }

                    is FileOperationState.Active -> {
                        // Another operation acquired the mutex before delegate.downloadDeviceFile()
                        // could do so. Wait it out and retry rather than treating the silent return as
                        // a completed fallback.
                        busyWaits += 1
                    }

                    FileOperationState.Idle -> Unit
                }

                lastError = "DOWNLOAD_DEFERRED_BY_FILE_OPERATION"
                if (retryDelayMs > 0L) delay(retryDelayMs)
            }

            PrioritizedDeviceDownloadResult(
                completed = false,
                attempts = attempts,
                busyWaits = busyWaits,
                terminalError = lastError ?: "DOWNLOAD_RETRY_EXHAUSTED",
            )
        }

    return result
        ?: PrioritizedDeviceDownloadResult(
            completed = false,
            attempts = attempts,
            busyWaits = busyWaits,
            terminalError = "DOWNLOAD_WAIT_TIMEOUT",
        )
}

private fun FileOperationState.Completed.matchesDownload(
    remoteIdentity: String,
    format: DeviceAudioFormat,
): Boolean =
    operation == DeviceFileOperationType.DOWNLOAD &&
        this.remoteIdentity == remoteIdentity &&
        (audioFormat == null || audioFormat == format)

private fun FileOperationState.Failed.matchesDownload(
    remoteIdentity: String,
    format: DeviceAudioFormat,
): Boolean =
    operation == DeviceFileOperationType.DOWNLOAD &&
        this.remoteIdentity == remoteIdentity &&
        (audioFormat == null || audioFormat == format)

private fun FileOperationState.Cancelled.matchesDownload(
    remoteIdentity: String,
    format: DeviceAudioFormat,
): Boolean =
    operation == DeviceFileOperationType.DOWNLOAD &&
        this.remoteIdentity == remoteIdentity &&
        (audioFormat == null || audioFormat == format)

private fun FileOperationError.toDiagnosticText(): String =
    buildString {
        append(code.name)
        detail?.takeIf { it.isNotBlank() }?.let { detail ->
            append(':')
            append(detail)
        }
        remoteStatusCode?.let { status ->
            append(" remoteStatus=")
            append(status)
        }
    }
