package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.audio.OpusPacketInspector
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RealtimeAudioConsumer
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioOfferResult
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import io.github.ioannes78.voica.ble.RealtimeDeviceTransport
import io.github.ioannes78.voica.ble.RealtimeTransportCommandResult
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioRuntimeReconciliationTest {
    @Test
    fun deviceRecordingEndUsesBestEffortStopAndClosesCanonicalLifecycle() = runBlocking {
        val transport = FakeTransport(generation = 1)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 4)

        val outcome = runtime.reconcileDeviceRecordingEnded()
        assertEquals(RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP, outcome.result)

        val snapshot = runtime.snapshot()
        assertEquals(RealtimeRuntimePhase.IDLE, snapshot.phase)
        assertFalse(snapshot.fence.audioGateOpen)
        assertFalse(snapshot.source.sessionActive)
        assertEquals(RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED, snapshot.source.lastSessionEndReason)
        assertEquals(1L, snapshot.source.sessionEndedEvents)
        assertEquals(1L, snapshot.reconciliation.attempts)
        assertEquals(1L, snapshot.reconciliation.stopWriteSuccess)
        assertEquals(0L, snapshot.reconciliation.stopWriteFailure)
        assertEquals(0L, snapshot.reconciliation.fallbackCount)
        assertEquals(1L, snapshot.reconciliation.completed)
        assertEquals(RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP, snapshot.reconciliation.lastResult)

        runtime.close()
    }

    @Test
    fun failedStopWriteFallsBackLocallyAndNextStartStillWorks() = runBlocking {
        val transport = FakeTransport(generation = 2)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 4)
        transport.failStopWrites = true

        val outcome = runtime.reconcileDeviceRecordingEnded()
        assertEquals(RealtimeRecordingReconciliationResult.COMPLETED_WITH_FALLBACK, outcome.result)

        var snapshot = runtime.snapshot()
        assertEquals(RealtimeRuntimePhase.IDLE, snapshot.phase)
        assertFalse(snapshot.fence.audioGateOpen)
        assertFalse(snapshot.source.sessionActive)
        assertEquals(RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED, snapshot.source.lastSessionEndReason)
        assertEquals(1L, snapshot.reconciliation.attempts)
        assertEquals(0L, snapshot.reconciliation.stopWriteSuccess)
        assertEquals(1L, snapshot.reconciliation.stopWriteFailure)
        assertEquals(1L, snapshot.reconciliation.fallbackCount)
        assertEquals(1L, snapshot.reconciliation.completed)
        assertEquals(RealtimeCommandFailure.WRITE_FAILED, snapshot.reconciliation.lastStopFailure)

        transport.failStopWrites = false
        assertTrue(runtime.start().success)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 8)
        snapshot = runtime.snapshot()
        assertEquals(RealtimeRuntimePhase.ACTIVE, snapshot.phase)
        assertTrue(snapshot.source.sessionActive)
        assertTrue(runtime.stop().success)
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)

        runtime.close()
    }

    @Test
    fun reconciliationWhileIdleIsNoOpAndDoesNotSendStop() = runBlocking {
        val transport = FakeTransport(generation = 3)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        val outcome = runtime.reconcileDeviceRecordingEnded()
        assertEquals(RealtimeRecordingReconciliationResult.NO_OP, outcome.result)
        assertEquals(0, transport.stopWrites)
        assertEquals(0L, runtime.snapshot().reconciliation.attempts)
        assertEquals(1L, runtime.snapshot().reconciliation.noOp)

        runtime.close()
    }

    private suspend fun awaitOfferedFrames(runtime: RealtimeAudioRuntime, expected: Long) {
        withTimeout(2_000L) {
            while (runtime.snapshot().source.offeredFrames < expected) delay(5)
        }
    }

    private class FakeTransport(
        generation: Long?,
    ) : RealtimeDeviceTransport {
        override val connectionState: StateFlow<DeviceConnectionState> =
            MutableStateFlow(DeviceConnectionState.Idle)

        private var generationValue: Long? = generation
        private var consumer: RealtimeAudioConsumer? = null
        private var sequence = 0
        var failStopWrites: Boolean = false
        var stopWrites: Int = 0
            private set

        override val currentGeneration: Long?
            get() = generationValue

        override fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean {
            if (this.consumer != null) return false
            this.consumer = consumer
            return true
        }

        override fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer) {
            if (this.consumer === consumer) this.consumer = null
        }

        override suspend fun sendRealtimeCommand(command: RealtimeControlCommand): RealtimeTransportCommandResult {
            val generation = generationValue
            sequence = (sequence + 1) and 0xFF
            if (command == RealtimeControlCommand.STOP) stopWrites += 1
            val written = generation != null && !(command == RealtimeControlCommand.STOP && failStopWrites)
            return RealtimeTransportCommandResult(
                command = command,
                transportGeneration = generation,
                requestSequence = sequence,
                written = written,
                generationStillCurrent = generation != null,
            )
        }

        fun emitAudioBlock(): Boolean {
            val generation = generationValue ?: return false
            val target = consumer ?: return false
            val body = ByteArray(RealtimeAudioIngress.REALTIME_BLOCK_BYTES) { it.toByte() }
            val notification = RoutedNotification(
                source = NotificationSource.AE22,
                frame = ProtocolFrame(
                    sequence = 0,
                    data = byteArrayOf(
                        ProtocolConstants.Type.REALTIME.toByte(),
                        ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
                    ) + body,
                ),
            )
            val result = target.offer(notification, generation, System.nanoTime())
            return result == RealtimeAudioOfferResult.ACCEPTED ||
                result == RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY
        }
    }

    private class FakeBackend : OpusPacketInspector, OpusDecoderFactory {
        override val version: String = "fake"

        override fun inspect(packet: ByteArray): OpusPacketInfo =
            OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960)

        override fun create(sampleRateHz: Int, channelCount: Int): OpusDecoder =
            object : OpusDecoder {
                override val sampleRateHz: Int = sampleRateHz
                override val channelCount: Int = channelCount
                override fun decode(packet: ByteArray): ShortArray = ShortArray(320) { 7 }
                override fun reset() = Unit
                override fun close() = Unit
            }
    }
}
