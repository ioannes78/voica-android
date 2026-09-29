package io.github.ioannes78.voica.ble

sealed interface RecordingRequestOutcome<out T> {
    data class Success<T>(
        val value: T,
        val requestSequence: Int,
        val responseSequence: Int,
        val source: NotificationSource?,
        val latencyMs: Long?,
    ) : RecordingRequestOutcome<T>

    data class Malformed(val reason: String) : RecordingRequestOutcome<Nothing>
    data object WriteFailed : RecordingRequestOutcome<Nothing>
    data object ResponseTimedOut : RecordingRequestOutcome<Nothing>
    data object Cancelled : RecordingRequestOutcome<Nothing>
}
