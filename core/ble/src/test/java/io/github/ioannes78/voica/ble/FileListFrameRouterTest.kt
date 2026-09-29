package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListFrameRouterTest {
    private val router = FileListFrameRouter()

    @Test
    fun routesListDataFromEitherNotificationSource() {
        val body = ByteArray(4 + 28)
        putU32Be(body, 0, 1)
        putU32Be(body, 4, 10)
        putU32Be(body, 8, 100)
        "a.opus".encodeToByteArray().copyInto(body, destinationOffset = 12)

        for (source in listOf(NotificationSource.AE22, NotificationSource.AE23)) {
            val event = router.route(notification(source, ProtocolConstants.File.LIST_DATA, body))
            assertTrue(event is FileListFrameEvent.Data)
            assertEquals(source, (event as FileListFrameEvent.Data).source)
            assertEquals(1, event.chunk.entries.size)
        }
    }

    @Test
    fun routesListDoneAndIgnoresOtherCommands() {
        val done = router.route(
            notification(NotificationSource.AE23, ProtocolConstants.File.LIST_DONE, byteArrayOf()),
        )
        assertTrue(done is FileListFrameEvent.Done)

        assertNull(
            router.route(
                notification(NotificationSource.AE22, ProtocolConstants.File.DATA, byteArrayOf()),
            ),
        )
    }

    @Test
    fun malformedListDataIsExplicit() {
        val event = router.route(
            notification(
                NotificationSource.AE22,
                ProtocolConstants.File.LIST_DATA,
                byteArrayOf(0, 0, 0),
            ),
        )
        assertTrue(event is FileListFrameEvent.Malformed)
    }

    private fun notification(
        source: NotificationSource,
        command: Int,
        body: ByteArray,
    ): RoutedNotification =
        RoutedNotification(
            source = source,
            frame = ProtocolFrame(
                sequence = 7,
                data = byteArrayOf(
                    ProtocolConstants.Type.FILE.toByte(),
                    command.toByte(),
                ) + body,
            ),
        )

    private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
