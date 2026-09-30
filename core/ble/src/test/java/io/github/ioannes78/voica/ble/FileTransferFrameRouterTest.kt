package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferFrameRouterTest {
    private val router = FileTransferFrameRouter()

    @Test
    fun routesStartFromEitherNotificationSource() {
        val frame = parse(
            ProtocolCodec.buildCommand(
                sequence = 4,
                type = ProtocolConstants.Type.FILE,
                command = ProtocolConstants.File.IMPORT_START,
                params = "note20260930-083059.wav".encodeToByteArray(),
            ),
        )
        val event = router.route(RoutedNotification(NotificationSource.AE23, frame))

        assertTrue(event is FileTransferFrameEvent.Start)
        event as FileTransferFrameEvent.Start
        assertEquals(NotificationSource.AE23, event.source)
        assertEquals("note20260930-083059.wav", event.actualFilename)
    }

    @Test
    fun routesDataWithoutTransformingBytes() {
        val payload = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01, 0x02)
        val frame = parse(
            ProtocolCodec.buildCommand(
                sequence = 5,
                type = ProtocolConstants.Type.FILE,
                command = ProtocolConstants.File.DATA,
                params = payload,
            ),
        )
        val event = router.route(RoutedNotification(NotificationSource.AE22, frame))

        assertTrue(event is FileTransferFrameEvent.Data)
        event as FileTransferFrameEvent.Data
        assertArrayEquals(payload, event.bytes)
        assertEquals(NotificationSource.AE22, event.source)
    }

    @Test
    fun routesEndAndAbortResponse() {
        val end = router.route(
            RoutedNotification(
                NotificationSource.AE22,
                parse(
                    ProtocolCodec.buildCommand(
                        6,
                        ProtocolConstants.Type.FILE,
                        ProtocolConstants.File.IMPORT_END,
                        byteArrayOf(0),
                    ),
                ),
            ),
        )
        val abort = router.route(
            RoutedNotification(
                NotificationSource.AE23,
                parse(
                    ProtocolCodec.buildCommand(
                        7,
                        ProtocolConstants.Type.FILE,
                        ProtocolConstants.File.ABORT_RESPONSE,
                        byteArrayOf(0),
                    ),
                ),
            ),
        )

        assertEquals(0, (end as FileTransferFrameEvent.End).statusCode)
        assertEquals(0, (abort as FileTransferFrameEvent.AbortResponse).statusCode)
    }

    @Test
    fun unrelatedFileFrameIsIgnored() {
        val frame = parse(
            ProtocolCodec.buildCommand(
                8,
                ProtocolConstants.Type.FILE,
                ProtocolConstants.File.LIST_DONE,
                byteArrayOf(0),
            ),
        )
        assertNull(router.route(RoutedNotification(NotificationSource.AE22, frame)))
        assertTrue(!RoutedNotification(NotificationSource.AE22, frame).isFileTransferFrame())
    }

    private fun parse(bytes: ByteArray) = FrameParserForTest.parse(bytes)
}

private object FrameParserForTest {
    fun parse(bytes: ByteArray) =
        io.github.ioannes78.voica.protocol.FrameParser("file-transfer-test")
            .feed(bytes)
            .single()
}
