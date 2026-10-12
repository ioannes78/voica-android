package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.RealtimeAudioConsumer
import io.github.ioannes78.voica.ble.RealtimeAudioOfferResult
import io.github.ioannes78.voica.ble.RealtimeCommandFence
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import io.github.ioannes78.voica.ble.RealtimeDeviceTransport
import io.github.ioannes78.voica.ble.RealtimeTransportCommandResult
import io.github.ioannes78.voica.ble.RoutedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeConfirmedExistingRecordingAttachTest {
    @Test
    fun confirmedAttachBecomesActiveWithoutSendingStartThenNormalControlsStillWork() = runBlocking {
        val transport = FakeTransport(generation = 12)
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)

        val attached = controller.attachConfirmedExistingRecording()

        assertTrue(attached.success)
        assertNull(attached.failure)
        assertEquals(12L, attached.transportGeneration)
        assertEquals(0, transport.sentCommands.size)
        assertEquals(1, boundaries.startCount)
        assertEquals(RealtimeRuntimePhase.ACTIVE, controller.snapshot.value.phase)
        assertTrue(fence.snapshot().audioGateOpen)
        assertEquals(1L, controller.snapshot.value.existingRecordingAttachSuccess)

        assertTrue(controller.pause().success)
        assertEquals(listOf(RealtimeControlCommand.PAUSE), transport.sentCommands)
        assertEquals(RealtimeRuntimePhase.PAUSED, controller.snapshot.value.phase)
        assertFalse(fence.snapshot().audioGateOpen)

        assertTrue(controller.resume().success)
        assertEquals(
            listOf(RealtimeControlCommand.PAUSE, RealtimeControlCommand.RESUME),
            transport.sentCommands,
        )
        assertEquals(1, boundaries.controlBoundaryCount)
        assertEquals(RealtimeRuntimePhase.ACTIVE, controller.snapshot.value.phase)
        assertTrue(fence.snapshot().audioGateOpen)
    }

    @Test
    fun attachIsRejectedUnlessControllerIsIdleOrTransportClosed() = runBlocking {
        val transport = FakeTransport(generation = 3)
        val fence = RealtimeCommandFence(CapturingConsumer())
        val controller = RealtimeCommandController(transport, fence, FakeBoundarySink())

        assertTrue(controller.start().success)
        val sentBeforeAttach = transport.sentCommands.toList()

        val attached = controller.attachConfirmedExistingRecording()

        assertFalse(attached.success)
        assertEquals(RealtimeExistingRecordingAttachFailure.INVALID_PHASE, attached.failure)
        assertEquals(sentBeforeAttach, transport.sentCommands)
        assertEquals(1L, controller.snapshot.value.existingRecordingAttachFailure)
    }

    private class FakeTransport(
        generation: Long?,
    ) : RealtimeDeviceTransport {
        override val connectionState: StateFlow<DeviceConnectionState> =
            MutableStateFlow(DeviceConnectionState.Idle)
        var currentGenerationValue: Long? = generation
        override val currentGeneration: Long?
            get() = currentGenerationValue
        val sentCommands = mutableListOf<RealtimeControlCommand>()

        override fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean = true
        override fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer) = Unit

        override suspend fun sendRealtimeCommand(command: RealtimeControlCommand): RealtimeTransportCommandResult {
            sentCommands += command
            val generationNow = currentGenerationValue
            return RealtimeTransportCommandResult(
                command = command,
                transportGeneration = generationNow,
                requestSequence = sentCommands.size,
                written = generationNow != null,
                generationStillCurrent = generationNow != null,
            )
        }
    }

    private class FakeBoundarySink : RealtimeCommandBoundarySink {
        var startCount = 0
        var controlBoundaryCount = 0

        override fun beginRealtimeSession(): Long {
            startCount += 1
            return startCount.toLong()
        }

        override fun requestControlBoundary(): Long {
            controlBoundaryCount += 1
            return controlBoundaryCount.toLong()
        }
    }

    private class CapturingConsumer : RealtimeAudioConsumer {
        override fun offer(
            notification: RoutedNotification,
            sessionGeneration: Long,
            receivedAtNanos: Long,
        ): RealtimeAudioOfferResult = RealtimeAudioOfferResult.ACCEPTED

        override fun onSessionClosed(sessionGeneration: Long) = Unit
    }
}
