package io.github.ioannes78.voica.ble

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private object OperationIds {
    private val counter = AtomicLong(0)
    fun next(): Long = counter.incrementAndGet()
}

sealed interface GattOperation {
    val id: Long

    data class DiscoverServices(
        override val id: Long = OperationIds.next(),
    ) : GattOperation

    data class EnableNotification(
        val characteristicUuid: UUID,
        override val id: Long = OperationIds.next(),
    ) : GattOperation

    data class RequestMtu(
        val mtu: Int,
        override val id: Long = OperationIds.next(),
    ) : GattOperation

    data class WriteCharacteristic(
        val characteristicUuid: UUID,
        val value: ByteArray,
        override val id: Long = OperationIds.next(),
    ) : GattOperation {
        override fun equals(other: Any?): Boolean =
            other is WriteCharacteristic &&
                characteristicUuid == other.characteristicUuid &&
                value.contentEquals(other.value) &&
                id == other.id

        override fun hashCode(): Int =
            31 * (31 * characteristicUuid.hashCode() + value.contentHashCode()) + id.hashCode()
    }
}

sealed interface GattCallbackEvent {
    data class ServicesDiscovered(val status: Int) : GattCallbackEvent
    data class DescriptorWritten(
        val characteristicUuid: UUID,
        val descriptorUuid: UUID,
        val status: Int,
    ) : GattCallbackEvent
    data class MtuChanged(val mtu: Int, val status: Int) : GattCallbackEvent
    data class CharacteristicWritten(
        val characteristicUuid: UUID,
        val status: Int,
    ) : GattCallbackEvent
}

sealed interface GattOperationResult {
    data class Completed(
        val operation: GattOperation,
        val status: Int,
        val negotiatedMtu: Int? = null,
    ) : GattOperationResult

    data class Rejected(val operation: GattOperation) : GattOperationResult
    data class TimedOut(val operation: GattOperation) : GattOperationResult
    data class Cancelled(val operation: GattOperation) : GattOperationResult
    data class QueueClosed(val operation: GattOperation) : GattOperationResult
}

fun interface GattOperationStarter {
    fun start(operation: GattOperation): Boolean
}
