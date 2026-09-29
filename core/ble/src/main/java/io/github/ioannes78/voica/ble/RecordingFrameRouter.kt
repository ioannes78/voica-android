package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolDecodeResult
import io.github.ioannes78.voica.protocol.RecordingCommandResult
import io.github.ioannes78.voica.protocol.RecordingDecoders
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.protocol.RecordingTimeInfo

sealed interface RecordingFrameEvent {
    val source: NotificationSource
    val command: Int
    val sequence: Int

    data class State(
        val value: RecordingStatus,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Time(
        val value: RecordingTimeInfo,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Filename(
        val value: String,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Gain(
        val value: RecordingGain,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class CommandResponse(
        val value: RecordingCommandResult,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Hardware(
        val event: RecordingHardwareEvent,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Malformed(
        val reason: String,
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent

    data class Unknown(
        override val source: NotificationSource,
        override val command: Int,
        override val sequence: Int,
    ) : RecordingFrameEvent
}

class RecordingFrameRouter(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun route(notification: RoutedNotification): RecordingFrameEvent? {
        val frame = notification.frame
        if (frame.type != ProtocolConstants.Type.KEY) return null
        val command = frame.command ?: return RecordingFrameEvent.Unknown(
            source = notification.source,
            command = -1,
            sequence = frame.sequence,
        )

        return when (command) {
            ProtocolConstants.Key.STATE_RESPONSE ->
                decode(
                    RecordingDecoders.decodeStatus(frame.body),
                    notification,
                    command,
                ) { value, source, sequence ->
                    RecordingFrameEvent.State(value, source, command, sequence)
                }

            ProtocolConstants.Key.TIME_RESPONSE ->
                decode(
                    RecordingDecoders.decodeTime(frame.body),
                    notification,
                    command,
                ) { value, source, sequence ->
                    RecordingFrameEvent.Time(value, source, command, sequence)
                }

            ProtocolConstants.Key.FILENAME_RESPONSE ->
                decode(
                    RecordingDecoders.decodeFilename(frame.body),
                    notification,
                    command,
                ) { value, source, sequence ->
                    RecordingFrameEvent.Filename(value, source, command, sequence)
                }

            ProtocolConstants.Key.GAIN_RESPONSE ->
                decode(
                    RecordingDecoders.decodeGain(frame.body),
                    notification,
                    command,
                ) { value, source, sequence ->
                    RecordingFrameEvent.Gain(value, source, command, sequence)
                }

            ProtocolConstants.Key.RECORD_START_RESPONSE,
            ProtocolConstants.Key.RECORD_SAVE_RESPONSE,
            ProtocolConstants.Key.RECORD_PAUSE_RESPONSE,
            ProtocolConstants.Key.RECORD_RESUME_RESPONSE,
            ProtocolConstants.Key.SET_GAIN_RESPONSE,
            ->
                decode(
                    RecordingDecoders.decodeCommandResult(frame.body),
                    notification,
                    command,
                ) { value, source, sequence ->
                    RecordingFrameEvent.CommandResponse(value, source, command, sequence)
                }

            ProtocolConstants.Key.RECORD_START,
            ProtocolConstants.Key.RECORD_SAVE,
            ProtocolConstants.Key.RECORD_PAUSE,
            ProtocolConstants.Key.RECORD_RESUME,
            -> {
                val kind = when (command) {
                    ProtocolConstants.Key.RECORD_START -> RecordingHardwareEventKind.START
                    ProtocolConstants.Key.RECORD_SAVE -> RecordingHardwareEventKind.SAVE
                    ProtocolConstants.Key.RECORD_PAUSE -> RecordingHardwareEventKind.PAUSE
                    else -> RecordingHardwareEventKind.RESUME
                }
                val hardware = RecordingHardwareEvent(
                    kind = kind,
                    source = notification.source,
                    command = command,
                    sequence = frame.sequence,
                    timestampMs = nowMs(),
                )
                RecordingFrameEvent.Hardware(
                    event = hardware,
                    source = notification.source,
                    command = command,
                    sequence = frame.sequence,
                )
            }

            else -> RecordingFrameEvent.Unknown(
                source = notification.source,
                command = command,
                sequence = frame.sequence,
            )
        }
    }

    private fun <T> decode(
        result: ProtocolDecodeResult<T>,
        notification: RoutedNotification,
        command: Int,
        success: (T, NotificationSource, Int) -> RecordingFrameEvent,
    ): RecordingFrameEvent =
        when (result) {
            is ProtocolDecodeResult.Success ->
                success(result.value, notification.source, notification.frame.sequence)
            is ProtocolDecodeResult.Malformed ->
                RecordingFrameEvent.Malformed(
                    reason = result.reason,
                    source = notification.source,
                    command = command,
                    sequence = notification.frame.sequence,
                )
        }
}
