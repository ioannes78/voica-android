package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FileListDecodeResult
import io.github.ioannes78.voica.protocol.FileListDecoder
import io.github.ioannes78.voica.protocol.FileListChunk
import io.github.ioannes78.voica.protocol.ProtocolConstants

sealed interface FileListFrameEvent {
    val source: NotificationSource
    val sequence: Int

    data class Data(
        override val source: NotificationSource,
        override val sequence: Int,
        val chunk: FileListChunk,
    ) : FileListFrameEvent

    data class Done(
        override val source: NotificationSource,
        override val sequence: Int,
        val bodySize: Int,
    ) : FileListFrameEvent

    data class Malformed(
        override val source: NotificationSource,
        override val sequence: Int,
        val reason: String,
        val bodySize: Int,
    ) : FileListFrameEvent
}

class FileListFrameRouter {
    fun route(notification: RoutedNotification): FileListFrameEvent? {
        val frame = notification.frame
        if (frame.type != ProtocolConstants.Type.FILE) return null

        return when (frame.command) {
            ProtocolConstants.File.LIST_DATA ->
                when (val decoded = FileListDecoder.decode(frame.body)) {
                    is FileListDecodeResult.Success ->
                        FileListFrameEvent.Data(
                            source = notification.source,
                            sequence = frame.sequence,
                            chunk = decoded.chunk,
                        )
                    is FileListDecodeResult.Malformed ->
                        FileListFrameEvent.Malformed(
                            source = notification.source,
                            sequence = frame.sequence,
                            reason = decoded.reason,
                            bodySize = frame.body.size,
                        )
                }

            ProtocolConstants.File.LIST_DONE ->
                FileListFrameEvent.Done(
                    source = notification.source,
                    sequence = frame.sequence,
                    bodySize = frame.body.size,
                )

            else -> null
        }
    }
}
