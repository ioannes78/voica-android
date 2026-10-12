package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.audio.OpusPacketInspector
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioRuntimeTest {
    @Test
    fun transportCloseJoinsProducerBeforeTerminalEventAndBalancesDecoder() = runBlocking {
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

        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)

        assertEquals(RealtimeCanonicalStreamEvent.SessionStarted(1), runtime.canonicalSource.receive())
        assertTrue(runtime.canonicalSource.receive() is RealtimeCanonicalStreamEvent.EpochStarted)
        repeat(4) { index ->
            val pcm = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.PcmFrame
            assertEquals(index * 320L, pcm.frame.startSampleIndex)
        }
        val ended = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.SessionEnded
        assertEquals(1L, ended.sessionId)
        assertEquals(1_280L, ended.finalSampleIndexExclusive)
        assertEquals(RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED, ended.reason)
        assertFalse(ended.drainTimedOut)

        val snapshot = runtime.snapshot()
        assertEquals(RealtimeRuntimePhase.TRANSPORT_CLOSED, snapshot.phase)
        assertEquals(1L, snapshot.transportRuntimeCreates)
        assertEquals(1L, snapshot.transportRuntimeReleases)
        assertEquals(1L, snapshot.transportCloseCount)
        assertEquals(1L, snapshot.transportCloseWhileActiveCount)
        assertEquals(snapshot.decoderCreateCount, snapshot.decoderReleaseCount)
        assertEquals(backend.decoderCreates.get().toLong(), snapshot.decoderCreateCount)
        assertEquals(backend.decoderCloses.get().toLong(), snapshot.decoderReleaseCount)
        assertFalse(snapshot.source.sessionActive)
        assertFalse(snapshot.fence.audioGateOpen)

        runtime.close()
    }

    @Test
    fun reconnectCreatesFreshOwnerAndProcessMonotonicLogicalSessionId() = runBlocking {
        val transport = FakeTransport(generation = 11)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        transport.emitAudioBlock()
        awaitOfferedFrames(runtime, 4)
        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)
        drainSession(runtime, expectedSessionId = 1)

        transport.openGeneration(12)
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)
        assertEquals(12L, runtime.snapshot().transportGeneration)
        assertTrue(runtime.start().success)
        transport.emitAudioBlock()
        withTimeout(2_000L) {
            while (runtime.snapshot().source.offeredFrames < 8L) delay(5)
        }

        val started = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.SessionStarted
        assertEquals(2L, started.sessionId)
        val epoch = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.EpochStarted
        assertEquals(2L, epoch.sessionId)
        val firstPcm = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.PcmFrame
        assertEquals(2L, firstPcm.sessionId)
        assertEquals(0L, firstPcm.frame.startSampleIndex)

        transport.closeCurrentGeneration()
        awaitReleases(runtime, 2)
        assertEquals(2L, runtime.snapshot().transportRuntimeCreates)
        assertEquals(2L, runtime.snapshot().transportRuntimeReleases)
        assertEquals(runtime.snapshot().decoderCreateCount, runtime.snapshot().decoderReleaseCount)
        runtime.close()
    }

    @Test
    fun pauseClosesAudioGateAndResumeReopensWithNewEpoch() = runBlocking {
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
        assertTrue(runtime.pause().success)
        assertFalse(runtime.snapshot().fence.audioGateOpen)
        assertFalse(transport.emitAudioBlock())
        delay(20)
        assertEquals(4L, runtime.snapshot().source.offeredFrames)

        assertTrue(runtime.resume().success)
        assertTrue(runtime.snapshot().fence.audioGateOpen)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 8)
        assertEquals(2L, runtime.snapshot().pipeline.epochCount)

        assertTrue(runtime.stop().success)
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)
        assertFalse(runtime.snapshot().fence.audioGateOpen)
        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)
        runtime.close()
    }

    @Test
    fun stopWithoutAnyAudioDoesNotRemainDraining() = runBlocking {
        val transport = FakeTransport(generation = 3)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        assertTrue(runtime.stop().success)
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)
        assertFalse(runtime.snapshot().source.sessionActive)
        assertFalse(runtime.snapshot().fence.audioGateOpen)

        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)
        runtime.close()
    }

    @Test
    fun disconnectDuringStopDrainProducesExactlyOneTerminalEvent() = runBlocking {
        val transport = FakeTransport(generation = 4)
        val backend = FakeBackend()
        val source = RealtimeCanonicalAudioSource(
            sleeper = { delay(100L) },
        )
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            canonicalSource = source,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        transport.emitAudioBlock()
        awaitOfferedFrames(runtime, 4)

        val stopJob = launch { runtime.stop() }
        withTimeout(2_000L) {
            while (runtime.snapshot().phase != RealtimeRuntimePhase.DRAINING) delay(1)
        }
        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)
        stopJob.join()

        var endCount = 0
        repeat(7) {
            val event = runtime.canonicalSource.receive()
            if (event is RealtimeCanonicalStreamEvent.SessionEnded) {
                endCount += 1
                assertEquals(RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED, event.reason)
            }
        }
        assertEquals(1, endCount)
        assertEquals(1L, runtime.snapshot().source.sessionEndedEvents)
        assertEquals(RealtimeRuntimePhase.TRANSPORT_CLOSED, runtime.snapshot().phase)
        assertEquals(runtime.snapshot().decoderCreateCount, runtime.snapshot().decoderReleaseCount)
        runtime.close()
    }

    @Test
    fun generationChangeDuringRegistrationCannotLeaveStaleOwner() = runBlocking {
        val transport = FakeTransport(generation = 21)
        val backend = FakeBackend()
        transport.switchGenerationDuringNextRegister(22)
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        val first = runtime.start()
        assertFalse(first.success)
        assertEquals(RealtimeCommandFailure.TRANSPORT_NOT_READY, first.failure)
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)
        assertEquals(22L, runtime.snapshot().transportGeneration)
        assertEquals(1L, runtime.snapshot().transportRuntimeCreates)
        assertEquals(1L, runtime.snapshot().transportRuntimeReleases)

        assertTrue(runtime.start().success)
        assertEquals(2L, runtime.snapshot().transportRuntimeCreates)
        assertEquals(22L, runtime.snapshot().transportGeneration)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 4)

        transport.closeCurrentGeneration()
        awaitReleases(runtime, 2)
        assertEquals(runtime.snapshot().decoderCreateCount, runtime.snapshot().decoderReleaseCount)
        runtime.close()
    }

    private suspend fun awaitOfferedFrames(runtime: RealtimeAudioRuntime, expected: Long) {
        withTimeout(2_000L) {
            while (runtime.snapshot().source.offeredFrames < expected) delay(5)
        }
    }

    private suspend fun awaitReleases(runtime: RealtimeAudioRuntime, expected: Long) {
        withTimeout(2_000L) {
            while (runtime.snapshot().transportRuntimeReleases < expected) delay(5)
        }
    }

    private suspend fun drainSession(runtime: RealtimeAudioRuntime, expectedSessionId: Long) {
        val started = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.SessionStarted
        assertEquals(expectedSessionId, started.sessionId)
        runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.EpochStarted
        repeat(4) { runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.PcmFrame }
        val ended = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.SessionEnded
        assertEquals(expectedSessionId, ended.sessionId)
    }

    private class FakeTransport(
        generation: Long?,
    ) : RealtimeDeviceTransport {
        override val connectionState: StateFlow<DeviceConnectionState> =
            MutableStateFlow(DeviceConnectionState.Idle)

        private var generationValue: Long? = generation
        private var consumer: RealtimeAudioConsumer? = null
        private var sequence = 0
        private var switchOnRegister: Long? = null

        override val currentGeneration: Long?
            get() = generationValue

        override fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean {
            if (this.consumer != null) return false
            this.consumer = consumer
            switchOnRegister?.let {
                generationValue = it
                switchOnRegister = null
            }
            return true
        }

        override fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer) {
            if (this.consumer === consumer) this.consumer = null
        }

        override suspend fun sendRealtimeCommand(command: RealtimeControlCommand): RealtimeTransportCommandResult {
            val generation = generationValue
            sequence = (sequence + 1) and 0xFF
            return RealtimeTransportCommandResult(
                command = command,
                transportGeneration = generation,
                requestSequence = sequence,
                written = generation != null,
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

        fun closeCurrentGeneration() {
            val generation = generationValue ?: return
            generationValue = null
            consumer?.onSessionClosed(generation)
        }

        fun openGeneration(generation: Long) {
            check(generationValue == null)
            generationValue = generation
        }

        fun switchGenerationDuringNextRegister(generation: Long) {
            switchOnRegister = generation
        }
    }

    private class FakeBackend : OpusPacketInspector, OpusDecoderFactory {
        override val version: String = "fake"
        val decoderCreates = AtomicInteger(0)
        val decoderCloses = AtomicInteger(0)

        override fun inspect(packet: ByteArray): OpusPacketInfo =
            OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960)

        override fun create(sampleRateHz: Int, channelCount: Int): OpusDecoder {
            decoderCreates.incrementAndGet()
            return object : OpusDecoder {
                override val sampleRateHz: Int = sampleRateHz
                override val channelCount: Int = channelCount
                override fun decode(packet: ByteArray): ShortArray = ShortArray(320) { 7 }
                override fun reset() = Unit
                override fun close() {
                    decoderCloses.incrementAndGet()
                }
            }
        }
    }
}
