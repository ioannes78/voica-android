package io.github.ioannes78.voica.ble

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationQueueTest {
    @Test
    fun operationsAreStrictlySerialized() = runTest {
        val started = mutableListOf<GattOperation>()
        val queue = GattOperationQueue(
            starter = GattOperationStarter {
                started += it
                true
            },
            operationTimeoutMs = 5_000,
        )

        val firstOperation = GattOperation.DiscoverServices()
        val secondOperation = GattOperation.RequestMtu(517)
        val first = async { queue.execute(firstOperation) }
        val second = async { queue.execute(secondOperation) }
        runCurrent()

        assertEquals(listOf(firstOperation), started)
        assertTrue(queue.onCallback(GattCallbackEvent.ServicesDiscovered(0)))
        runCurrent()

        assertEquals(listOf(firstOperation, secondOperation), started)
        assertTrue(queue.onCallback(GattCallbackEvent.MtuChanged(517, 0)))

        assertTrue(first.await() is GattOperationResult.Completed)
        assertTrue(second.await() is GattOperationResult.Completed)
    }

    @Test
    fun wrongCallbackDoesNotCompleteCurrentOperation() = runTest {
        val queue = GattOperationQueue(
            starter = GattOperationStarter { true },
            operationTimeoutMs = 5_000,
        )
        val target = UUID.randomUUID()
        val operation = GattOperation.EnableNotification(target)
        val pending = async { queue.execute(operation) }
        runCurrent()

        assertFalse(
            queue.onCallback(
                GattCallbackEvent.DescriptorWritten(
                    characteristicUuid = UUID.randomUUID(),
                    descriptorUuid = BleUuids.CCCD,
                    status = 0,
                ),
            ),
        )
        assertTrue(
            queue.onCallback(
                GattCallbackEvent.DescriptorWritten(
                    characteristicUuid = target,
                    descriptorUuid = BleUuids.CCCD,
                    status = 0,
                ),
            ),
        )
        assertTrue(pending.await() is GattOperationResult.Completed)
    }

    @Test
    fun timeoutDoesNotLetLateCallbackCompleteNextOperation() = runTest {
        val started = mutableListOf<GattOperation>()
        val queue = GattOperationQueue(
            starter = GattOperationStarter {
                started += it
                true
            },
            operationTimeoutMs = 50,
        )
        val firstOperation = GattOperation.DiscoverServices()
        val first = async { queue.execute(firstOperation) }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        assertTrue(first.await() is GattOperationResult.TimedOut)
        assertFalse(queue.onCallback(GattCallbackEvent.ServicesDiscovered(0)))

        val secondOperation = GattOperation.RequestMtu(517)
        val second = async { queue.execute(secondOperation) }
        runCurrent()
        assertEquals(secondOperation, started.last())
        assertFalse(queue.onCallback(GattCallbackEvent.ServicesDiscovered(0)))
        assertTrue(queue.onCallback(GattCallbackEvent.MtuChanged(247, 0)))
        assertTrue(second.await() is GattOperationResult.Completed)
    }

    @Test
    fun closeCancelsActiveOperation() = runTest {
        val queue = GattOperationQueue(
            starter = GattOperationStarter { true },
            operationTimeoutMs = 5_000,
        )
        val pending = async { queue.execute(GattOperation.DiscoverServices()) }
        runCurrent()
        queue.close()

        assertTrue(pending.await() is GattOperationResult.Cancelled)
        assertTrue(queue.snapshot.value.closed)
    }
}
