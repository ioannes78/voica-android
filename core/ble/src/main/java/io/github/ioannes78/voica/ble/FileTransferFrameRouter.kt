package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FileTransferDecodeResult
import io.github.ioannes78.voica.protocol.FileTransferProtocol
import io.github.ioannes78.voica.protocol.ProtocolConstants

sealed interface FileTransferFrameEvent {
    val source: NotificationSource
    val sequence: Int

    data class Start(
        override val source: NotificationSource,
        override val sequence: Int,
        val actualFilename: String?,
        val bodySize: Int,
    ) : FileTransferFrameEvent

    data class Data(
        override val source: NotificationSource,
        override val sequence: Int,
        val bytes: ByteArray,
    ) : FileTransferFrameEvent

    data class End(
        override val source: NotificationSource,
        override val sequence: Int,
        val statusCode: Int,
        val bodySize: Int,
    ) : FileTransferFrameEvent

    data class AbortResponse(
        override val source: NotificationSource,
        override val sequence: Int,
        val statusCode: Int?,
        val bodySize: Int,
    ) : FileTransferFrameEvent

    data class Malformed(
        override val source: NotificationSource,
        override val sequence: Int,
        val command: Int,
        val reason: String,
        val bodySize: Int,
    ) : FileTransferFrameEvent
}

class FileTransferFrameRouter {
    fun route(notification: RoutedNotification): FileTransferFrameEvent? {
        val frame = notification.frame
        if (frame.type != ProtocolConstants.Type.FILE) return null

        return when (val command = frame.command) {
            ProtocolConstants.File.IMPORT_START ->
                when (val decoded = FileTransferProtocol.decodeDownloadStart(frame.body)) {
                    is FileTransferDecodeResult.Success ->
                        FileTransferFrameEvent.Start(
                            source = notification.source,
                            sequence = frame.sequence,
                            actualFilename = decoded.value.actualFilename,
                            bodySize = frame.body.size,
                        )
                    is FileTransferDecodeResult.Malformed ->
                        FileTransferFrameEvent.Malformed(
                            source = notification.source,
                            sequence = frame.sequence,
                            command = command,
                            reason = decoded.reason,
                            bodySize = frame.body.size,
                        )
                }

            ProtocolConstants.File.DATA ->
                FileTransferFrameEvent.Data(
                    source = notification.source,
                    sequence = frame.sequence,
                    bytes = frame.body,
                )

            ProtocolConstants.File.IMPORT_END ->
                when (val decoded = FileTransferProtocol.decodeDownloadEnd(frame.body)) {
                    is FileTransferDecodeResult.Success ->
                        FileTransferFrameEvent.End(
                            source = notification.source,
                            sequence = frame.sequence,
                            statusCode = decoded.value.statusCode,
                            bodySize = frame.body.size,
                        )
                    is FileTransferDecodeResult.Malformed ->
                        FileTransferFrameEvent.Malformed(
                            source = notification.source,
                            sequence = frame.sequence,
                            command = command,
                            reason = decoded.reason,
                            bodySize = frame.body.size,
                        )
                }

            ProtocolConstants.File.ABORT_RESPONSE ->
                when (val decoded = FileTransferProtocol.decodeAbortResponse(frame.body)) {
                    is FileTransferDecodeResult.Success ->
                        FileTransferFrameEvent.AbortResponse(
                            source = notification.source,
                            sequence = frame.sequence,
                            statusCode = decoded.value.statusCode,
                            bodySize = frame.body.size,
                        )
                    is FileTransferDecodeResult.Malformed ->
                        FileTransferFrameEvent.Malformed(
                            source = notification.source,
                            sequence = frame.sequence,
                            command = command,
                            reason = decoded.reason,
                            bodySize = frame.body.size,
                        )
                }

            else -> null
        }
    }
}

interface ReliableFileTransferConsumer {
    /**
     * Called synchronously from the GATT notification path.
     *
     * Return false when the frame cannot be accepted without loss. The caller
     * must treat that as a transfer failure; it must never silently drop DATA.
     */
    fun offer(notification: RoutedNotification): Boolean
}

internal fun RoutedNotification.isFileTransferFrame(): Boolean =
    frame.type == ProtocolConstants.Type.FILE &&
        frame.command in setOf(
            ProtocolConstants.File.IMPORT_START,
            ProtocolConstants.File.DATA,
            ProtocolConstants.File.IMPORT_END,
            ProtocolConstants.File.ABORT_RESPONSE,
        )
