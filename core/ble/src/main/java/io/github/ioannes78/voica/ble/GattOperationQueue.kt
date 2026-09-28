package io.github.ioannes78.voica.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class GattOperationQueue(
    private val starter: GattOperationStarter,
    private val operationTimeoutMs: Long = 5_000L,
) {
    private data class Active(
        val operation: GattOperation,
        val deferred: CompletableDeferred<GattOperationResult>,
    )

    private val serialMutex = Mutex()
    private val stateLock = Any()
    private var active: Active? = null
    private var waitingCount = 0
    private var closed = false
    private val mutableSnapshot = MutableStateFlow(GattQueueSnapshot())

    val snapshot: StateFlow<GattQueueSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun execute(operation: GattOperation): GattOperationResult {
        synchronized(stateLock) {
            if (closed) return GattOperationResult.QueueClosed(operation)
            waitingCount += 1
            publishLocked()
        }

        return serialMutex.withLock {
            val deferred = CompletableDeferred<GattOperationResult>()
            synchronized(stateLock) {
                waitingCount = (waitingCount - 1).coerceAtLeast(0)
                if (closed) {
                    publishLocked()
                    return@withLock GattOperationResult.QueueClosed(operation)
                }
                active = Active(operation, deferred)
                publishLocked()
            }

            val started = try {
                starter.start(operation)
            } catch (_: RuntimeException) {
                false
            }
            if (!started) {
                clearActive(operation.id)
                return@withLock GattOperationResult.Rejected(operation)
            }

            val result = withTimeoutOrNull(operationTimeoutMs) {
                deferred.await()
            } ?: GattOperationResult.TimedOut(operation)

            clearActive(operation.id)
            result
        }
    }

    fun onCallback(event: GattCallbackEvent): Boolean {
        val current = synchronized(stateLock) { active } ?: return false
        if (!matches(current.operation, event)) return false

        val status = when (event) {
            is GattCallbackEvent.ServicesDiscovered -> event.status
            is GattCallbackEvent.DescriptorWritten -> event.status
            is GattCallbackEvent.MtuChanged -> event.status
            is GattCallbackEvent.CharacteristicWritten -> event.status
        }
        val mtu = (event as? GattCallbackEvent.MtuChanged)?.mtu
        return current.deferred.complete(
            GattOperationResult.Completed(
                operation = current.operation,
                status = status,
                negotiatedMtu = mtu,
            ),
        )
    }

    fun close() {
        val toCancel: Active?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            toCancel = active
            active = null
            waitingCount = 0
            publishLocked()
        }
        toCancel?.deferred?.complete(GattOperationResult.Cancelled(toCancel.operation))
    }

    private fun clearActive(operationId: Long) {
        synchronized(stateLock) {
            if (active?.operation?.id == operationId) {
                active = null
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        mutableSnapshot.value = GattQueueSnapshot(
            activeOperation = active?.operation?.javaClass?.simpleName,
            waitingCount = waitingCount,
            closed = closed,
        )
    }

    private fun matches(operation: GattOperation, event: GattCallbackEvent): Boolean =
        when (operation) {
            is GattOperation.DiscoverServices -> event is GattCallbackEvent.ServicesDiscovered
            is GattOperation.EnableNotification ->
                event is GattCallbackEvent.DescriptorWritten &&
                    event.characteristicUuid == operation.characteristicUuid &&
                    event.descriptorUuid == BleUuids.CCCD
            is GattOperation.RequestMtu -> event is GattCallbackEvent.MtuChanged
            is GattOperation.WriteCharacteristic ->
                event is GattCallbackEvent.CharacteristicWritten &&
                    event.characteristicUuid == operation.characteristicUuid
        }
}
