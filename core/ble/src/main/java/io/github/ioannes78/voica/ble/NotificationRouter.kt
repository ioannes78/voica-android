package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.NotificationParsers
import io.github.ioannes78.voica.protocol.ProtocolFrame
import java.util.UUID

data class RoutedNotification(
    val source: NotificationSource,
    val frame: ProtocolFrame,
)

class NotificationRouter {
    private val parsers = NotificationParsers()
    private var ae22Frames = 0
    private var ae23Frames = 0
    private var unknown = 0

    fun accept(
        characteristicUuid: UUID,
        bytes: ByteArray,
    ): List<RoutedNotification> {
        val source = when (characteristicUuid) {
            BleUuids.AE22_NOTIFY -> NotificationSource.AE22
            BleUuids.AE23_NOTIFY -> NotificationSource.AE23
            else -> {
                unknown += 1
                return emptyList()
            }
        }
        val parser = if (source == NotificationSource.AE22) parsers.ae22 else parsers.ae23
        val frames = parser.feed(bytes)
        if (source == NotificationSource.AE22) {
            ae22Frames += frames.size
        } else {
            ae23Frames += frames.size
        }
        return frames.map { RoutedNotification(source, it) }
    }

    fun stats(): NotificationStats = NotificationStats(
        ae22Frames = ae22Frames,
        ae23Frames = ae23Frames,
        ae22CrcErrors = parsers.ae22.crcErrorCount,
        ae23CrcErrors = parsers.ae23.crcErrorCount,
        ae22InvalidLengths = parsers.ae22.invalidLengthCount,
        ae23InvalidLengths = parsers.ae23.invalidLengthCount,
        unknownCharacteristicNotifications = unknown,
    )

    fun reset() {
        parsers.ae22.reset()
        parsers.ae23.reset()
        ae22Frames = 0
        ae23Frames = 0
        unknown = 0
    }
}
