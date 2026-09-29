package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.protocol.RecordingTimeInfo

sealed interface RecordingStateEvent {
    data class SyncStarted(val timestampMs: Long) : RecordingStateEvent
    data class StateReceived(
        val status: RecordingStatus,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class TimeReceived(
        val time: RecordingTimeInfo,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class FilenameReceived(
        val filename: String,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class GainReceived(
        val gain: RecordingGain,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class HardwareReceived(
        val event: RecordingHardwareEvent,
    ) : RecordingStateEvent
    data class CommandStarted(
        val state: RecordingCommandState,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class CommandFinished(
        val error: RecordingError?,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class SyncCompleted(val timestampMs: Long) : RecordingStateEvent
    data class SyncFailed(
        val error: RecordingError,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class DecodeFailed(
        val error: RecordingError,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class OperationError(
        val error: RecordingError,
        val timestampMs: Long,
    ) : RecordingStateEvent
    data class Disconnected(val timestampMs: Long) : RecordingStateEvent
}

object RecordingStateReducer {
    fun reduce(
        state: RecordingDeviceState,
        event: RecordingStateEvent,
    ): RecordingDeviceState =
        when (event) {
            is RecordingStateEvent.SyncStarted -> state.copy(
                freshness = RecordingFreshness.SYNCING,
                commandState = if (state.commandState == RecordingCommandState.IDLE) {
                    RecordingCommandState.RECONCILING
                } else {
                    state.commandState
                },
                lastUpdatedTimeMs = event.timestampMs,
                lastError = null,
            )

            is RecordingStateEvent.StateReceived ->
                if (event.status == RecordingStatus.Idle) {
                    state.copy(
                        status = event.status,
                        durationSeconds = 0,
                        currentSizeBytes = 0L,
                        lastUpdatedTimeMs = event.timestampMs,
                    )
                } else {
                    state.copy(
                        status = event.status,
                        lastUpdatedTimeMs = event.timestampMs,
                    )
                }

            is RecordingStateEvent.TimeReceived -> state.copy(
                durationSeconds = event.time.durationSeconds,
                currentSizeBytes = event.time.currentSizeBytes,
                lastUpdatedTimeMs = event.timestampMs,
            )

            is RecordingStateEvent.FilenameReceived -> state.copy(
                filename = event.filename.ifBlank { null },
                lastUpdatedTimeMs = event.timestampMs,
            )

            is RecordingStateEvent.GainReceived -> state.copy(
                gain = event.gain,
                lastUpdatedTimeMs = event.timestampMs,
            )

            is RecordingStateEvent.HardwareReceived -> state.copy(
                freshness = RecordingFreshness.STALE,
                lastHardwareEvent = event.event,
                lastUpdatedTimeMs = event.event.timestampMs,
            )

            is RecordingStateEvent.CommandStarted -> state.copy(
                commandState = event.state,
                lastUpdatedTimeMs = event.timestampMs,
                lastError = null,
            )

            is RecordingStateEvent.CommandFinished -> state.copy(
                commandState = RecordingCommandState.IDLE,
                freshness = RecordingFreshness.STALE,
                lastUpdatedTimeMs = event.timestampMs,
                lastError = event.error,
            )

            is RecordingStateEvent.SyncCompleted -> state.copy(
                freshness = RecordingFreshness.FRESH,
                commandState = RecordingCommandState.IDLE,
                lastUpdatedTimeMs = event.timestampMs,
            )

            is RecordingStateEvent.SyncFailed -> state.copy(
                freshness = RecordingFreshness.FAILED,
                commandState = RecordingCommandState.IDLE,
                lastUpdatedTimeMs = event.timestampMs,
                lastError = event.error,
            )

            is RecordingStateEvent.DecodeFailed -> state.copy(
                lastUpdatedTimeMs = event.timestampMs,
                lastError = event.error,
            )

            is RecordingStateEvent.OperationError -> state.copy(
                lastUpdatedTimeMs = event.timestampMs,
                lastError = event.error,
            )

            is RecordingStateEvent.Disconnected -> state.copy(
                freshness = RecordingFreshness.STALE,
                commandState = RecordingCommandState.IDLE,
                lastUpdatedTimeMs = event.timestampMs,
            )
        }
}

object RecordingPollingPolicy {
    fun shouldPoll(
        ready: Boolean,
        foreground: Boolean,
        status: RecordingStatus?,
    ): Boolean =
        ready && foreground && status == RecordingStatus.Recording
}


object RecordingStateConvergencePolicy {
    const val MAX_ATTEMPTS = 6
    const val RETRY_DELAY_MS = 250L

    fun isSatisfied(
        actual: RecordingStatus?,
        expected: RecordingStatus,
    ): Boolean = actual == expected

    fun shouldRetry(
        attempt: Int,
        actual: RecordingStatus?,
        expected: RecordingStatus,
    ): Boolean =
        attempt < MAX_ATTEMPTS && !isSatisfied(actual, expected)

    fun expectedForHardwareEvent(
        kind: RecordingHardwareEventKind,
    ): RecordingStatus =
        when (kind) {
            RecordingHardwareEventKind.START -> RecordingStatus.Recording
            RecordingHardwareEventKind.SAVE -> RecordingStatus.Idle
            RecordingHardwareEventKind.PAUSE -> RecordingStatus.Paused
            RecordingHardwareEventKind.RESUME -> RecordingStatus.Recording
        }
}

object RecordingStateEvidencePolicy {
    fun resolveReportedStatus(
        reported: RecordingStatus,
        pauseSemanticLatched: Boolean,
    ): RecordingStatus =
        if (pauseSemanticLatched && reported == RecordingStatus.Recording) {
            RecordingStatus.Paused
        } else {
            reported
        }
}

object RecordingSupplementaryReadPolicy {
    fun shouldReadTime(status: RecordingStatus): Boolean =
        status != RecordingStatus.Idle

    fun shouldReadFilename(status: RecordingStatus): Boolean =
        status != RecordingStatus.Idle
}
