package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue

/**
 * A recorder STOP acknowledgement is authoritative for media ownership even when the slower ASR
 * consumer cannot finish its tail inside the UI join budget. In that case the physical recording is
 * completed and must still enter the library; only the realtime transcript tail is degraded.
 */
internal object Stage16AStopFinalizationPolicy {
    const val ERROR_CODE = "STOP_FINALIZATION_TIMEOUT"
    const val DEGRADED_MESSAGE = "录音已保存，但实时转写最后内容未完全收口"

    fun durableTerminalState(
        requestedState: String,
        endReason: String?,
        errorCode: String?,
    ): String =
        if (
            requestedState == RealtimeTranscriptionStateValue.FAILED &&
            endReason == ERROR_CODE &&
            errorCode == ERROR_CODE
        ) {
            RealtimeTranscriptionStateValue.COMPLETED
        } else {
            requestedState
        }

    fun isDegradedCompletedStop(code: String?): Boolean = code == ERROR_CODE
}
