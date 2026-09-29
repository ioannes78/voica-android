package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FrameParser
import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFrameRouterTest {
    private val router = RecordingFrameRouter(nowMs = { 1234L })

    @Test
    fun routesStateResponseFromEitherNotificationSource() {
        val frame = frame(command = ProtocolConstants.Key.STATE_RESPONSE, body = byteArrayOf(1))
        val event = router.route(RoutedNotification(NotificationSource.AE23, frame))

        assertEquals(
            RecordingFrameEvent.State(
                value = RecordingStatus.Recording,
                source = NotificationSource.AE23,
                command = ProtocolConstants.Key.STATE_RESPONSE,
                sequence = 77,
            ),
            event,
        )
    }

    @Test
    fun routesTimeFilenameAndGain() {
        val time = router.route(
            RoutedNotification(
                NotificationSource.AE22,
                frame(
                    command = ProtocolConstants.Key.TIME_RESPONSE,
                    body = byteArrayOf(10, 0, 0x34, 0x12, 0, 0),
                ),
            ),
        )
        assertTrue(time is RecordingFrameEvent.Time)

        val filename = router.route(
            RoutedNotification(
                NotificationSource.AE23,
                frame(
                    command = ProtocolConstants.Key.FILENAME_RESPONSE,
                    body = "REC.opus\u0000".toByteArray(),
                ),
            ),
        )
        assertEquals("REC.opus", (filename as RecordingFrameEvent.Filename).value)

        val gain = router.route(
            RoutedNotification(
                NotificationSource.AE22,
                frame(
                    command = ProtocolConstants.Key.GAIN_RESPONSE,
                    body = byteArrayOf(2),
                ),
            ),
        )
        assertEquals(RecordingGain.Medium, (gain as RecordingFrameEvent.Gain).value)
    }

    @Test
    fun unsolicitedStartIsHardwareEventNotCommandResponse() {
        val event = router.route(
            RoutedNotification(
                NotificationSource.AE23,
                frame(command = ProtocolConstants.Key.HARDWARE_RECORD_START),
            ),
        )

        event as RecordingFrameEvent.Hardware
        assertEquals(RecordingHardwareEventKind.START, event.event.kind)
        assertEquals(1234L, event.event.timestampMs)
    }

    @Test
    fun truncatedTimeIsMalformedInsteadOfZeroTime() {
        val event = router.route(
            RoutedNotification(
                NotificationSource.AE22,
                frame(
                    command = ProtocolConstants.Key.TIME_RESPONSE,
                    body = ByteArray(5),
                ),
            ),
        )

        assertTrue(event is RecordingFrameEvent.Malformed)
    }

    private fun frame(command: Int, body: ByteArray = byteArrayOf()) =
        FrameParser("test").feed(
            ProtocolCodec.buildCommand(
                sequence = 77,
                type = ProtocolConstants.Type.KEY,
                command = command,
                params = body,
            ),
        ).single()
}
