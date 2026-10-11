package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RealtimeAudioConsumer
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioOfferResult
import io.github.ioannes78.voica.ble.RealtimeCommandFence
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import io.github.ioannes78.voica.ble.RealtimeDeviceTransport
import io.github.ioannes78.voica.ble.RealtimeTransportCommandResult
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCommandControllerTest {
    @Test
    fun startCommitsBoundaryBeforeReleasingFencedAudio() = runBlocking {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val transport = FakeTransport(generation = 7)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)
        val gate = CompletableDeferred<RealtimeTransportCommandResult>()
        transport.sender = { gate.await() }

        var outcome: RealtimeCommandOutcome? = null
        val startJob = launch { outcome = controller.start() }
        awaitPhase(controller, RealtimeRuntimePhase.STARTING)
        fence.offer(audioNotification(1), sessionGeneration = 7, receivedAtNanos = 100)
        assertTrue(downstream.frames.isEmpty())
        assertEquals(0, boundaries.startCount)

        gate.complete(successResult(RealtimeControlCommand.START, 7))
        startJob.join()

        assertTrue(requireNotNull(outcome).success)
        assertEquals(1, boundaries.startCount)
        assertEquals(listOf(1), downstream.frames.map { it.notification.frame.sequence })
        assertEquals(RealtimeRuntimePhase.ACTIVE, controller.snapshot.value.phase)
        assertEquals(1L, controller.snapshot.value.startSuccess)
    }

    @Test
    fun failedStartDropsFencedAudioAndDoesNotCreateSessionBoundary() = runBlocking {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val transport = FakeTransport(generation = 3)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)
        val gate = CompletableDeferred<RealtimeTransportCommandResult>()
        transport.sender = { gate.await() }

        var outcome: RealtimeCommandOutcome? = null
        val startJob = launch { outcome = controller.start() }
        awaitPhase(controller, RealtimeRuntimePhase.STARTING)
        fence.offer(audioNotification(4), sessionGeneration = 3, receivedAtNanos = 100)
        gate.complete(
            RealtimeTransportCommandResult(
                command = RealtimeControlCommand.START,
                transportGeneration = 3,
                requestSequence = 1,
                written = false,
                generationStillCurrent = true,
            ),
        )
        startJob.join()

        assertFalse(requireNotNull(outcome).success)
        assertEquals(RealtimeCommandFailure.WRITE_FAILED, outcome?.failure)
        assertEquals(0, boundaries.startCount)
        assertTrue(downstream.frames.isEmpty())
        assertEquals(1L, fence.snapshot().droppedFrames)
        assertEquals(RealtimeRuntimePhase.IDLE, controller.snapshot.value.phase)
    }

    @Test
    fun staleStartCompletionCannotCommitAcrossTransportGeneration() = runBlocking {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val transport = FakeTransport(generation = 12)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)
        val gate = CompletableDeferred<RealtimeTransportCommandResult>()
        transport.sender = { gate.await() }

        var outcome: RealtimeCommandOutcome? = null
        val startJob = launch { outcome = controller.start() }
        awaitPhase(controller, RealtimeRuntimePhase.STARTING)
        fence.offer(audioNotification(1), sessionGeneration = 12, receivedAtNanos = 100)
        transport.currentGenerationValue = 13
        gate.complete(
            RealtimeTransportCommandResult(
                command = RealtimeControlCommand.START,
                transportGeneration = 12,
                requestSequence = 1,
                written = true,
                generationStillCurrent = false,
            ),
        )
        startJob.join()

        assertEquals(RealtimeCommandFailure.STALE_GENERATION, outcome?.failure)
        assertEquals(0, boundaries.startCount)
        assertTrue(downstream.frames.isEmpty())
        assertEquals(RealtimeRuntimePhase.TRANSPORT_CLOSED, controller.snapshot.value.phase)
        assertEquals(1L, controller.snapshot.value.staleCommandCompletionCount)
    }

    @Test
    fun failedResumeDoesNotCreateControlBoundary() = runBlocking {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val transport = FakeTransport(generation = 5)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)
        transport.sender = { command -> successResult(command, 5) }

        assertTrue(controller.start().success)
        assertTrue(controller.pause().success)
        assertEquals(RealtimeRuntimePhase.PAUSED, controller.snapshot.value.phase)

        transport.sender = { command ->
            RealtimeTransportCommandResult(
                command = command,
                transportGeneration = 5,
                requestSequence = 3,
                written = false,
                generationStillCurrent = true,
            )
        }
        val result = controller.resume()

        assertFalse(result.success)
        assertEquals(RealtimeCommandFailure.WRITE_FAILED, result.failure)
        assertEquals(0, boundaries.controlBoundaryCount)
        assertEquals(RealtimeRuntimePhase.PAUSED, controller.snapshot.value.phase)
    }

    @Test
    fun successfulStopEntersDrainAndOnlyDrainCompletionReturnsIdle() = runBlocking {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val transport = FakeTransport(generation = 2)
        val boundaries = FakeBoundarySink()
        val controller = RealtimeCommandController(transport, fence, boundaries)
        transport.sender = { command -> successResult(command, 2) }

        assertTrue(controller.start().success)
        assertTrue(controller.stop().success)
        assertEquals(RealtimeRuntimePhase.DRAINING, controller.snapshot.value.phase)

        controller.completeStopDrain()

        assertEquals(RealtimeRuntimePhase.IDLE, controller.snapshot.value.phase)
        assertEquals(1L, controller.snapshot.value.stopSuccess)
    }

    private suspend fun awaitPhase(
        controller: RealtimeCommandController,
        phase: RealtimeRuntimePhase,
    ) {
        while (controller.snapshot.value.phase != phase) yield()
    }

    private fun successResult(
        command: RealtimeControlCommand,
        generation: Long,
    ) = RealtimeTransportCommandResult(
        command = command,
        transportGeneration = generation,
        requestSequence = 1,
        written = true,
        generationStillCurrent = true,
    )

    private fun audioNotification(sequence: Int): RoutedNotification =
        RoutedNotification(
            source = NotificationSource.AE22,
            frame = ProtocolFrame(
                sequence = sequence,
                data = byteArrayOf(
                    ProtocolConstants.Type.REALTIME.toByte(),
                    ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
                ) + ByteArray(RealtimeAudioIngress.REALTIME_BLOCK_BYTES) { index -> index.toByte() },
            ),
        )

    private class FakeTransport(
        generation: Long?,
    ) : RealtimeDeviceTransport {
        override val connectionState: StateFlow<DeviceConnectionState> =
            MutableStateFlow(DeviceConnectionState.Idle)
        var currentGenerationValue: Long? = generation
        override val currentGeneration: Long?
            get() = currentGenerationValue

        var sender: suspend (RealtimeControlCommand) -> RealtimeTransportCommandResult = { command ->
            val current = currentGenerationValue
            RealtimeTransportCommandResult(
                command = command,
                transportGeneration = current,
                requestSequence = 1,
                written = current != null,
                generationStillCurrent = current != null,
            )
        }

        override fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean = true
        override fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer) = Unit
        override suspend fun sendRealtimeCommand(command: RealtimeControlCommand): RealtimeTransportCommandResult =
            sender(command)
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
        val frames = mutableListOf<CapturedFrame>()

        override fun offer(
            notification: RoutedNotification,
            sessionGeneration: Long,
            receivedAtNanos: Long,
        ): RealtimeAudioOfferResult {
            frames += CapturedFrame(notification, sessionGeneration, receivedAtNanos)
            return RealtimeAudioOfferResult.ACCEPTED
        }

        override fun onSessionClosed(sessionGeneration: Long) = Unit
    }

    private data class CapturedFrame(
        val notification: RoutedNotification,
        val generation: Long,
        val receivedAtNanos: Long,
    )
}
