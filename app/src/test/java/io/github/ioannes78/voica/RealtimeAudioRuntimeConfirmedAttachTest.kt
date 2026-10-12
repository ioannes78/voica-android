package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.audio.OpusPacketInspector
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioRuntimeConfirmedAttachTest {
    @Test
    fun reconnectAttachStartsFreshCanonicalSessionWithoutSendingStart() = runBlocking {
        val transport = FakeTransport(generation = 41)
        val backend = FakeBackend()
        val runtime = RealtimeAudioRuntime(
            scope = this,
            transport = transport,
            packetInspector = backend,
            decoderFactory = backend,
            workerDispatcher = Dispatchers.Default,
        )

        assertTrue(runtime.start().success)
        assertEquals(listOf(RealtimeControlCommand.START), transport.sentCommands)
        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 4)
        transport.closeCurrentGeneration()
        awaitReleases(runtime, 1)
        drainSession(runtime, expectedSessionId = 1)

        transport.openGeneration(42)
        val commandsBeforeAttach = transport.sentCommands.toList()
        val attached = runtime.attachConfirmedExistingRecording()

        assertTrue(attached.success)
        assertEquals(commandsBeforeAttach, transport.sentCommands)
        assertEquals(RealtimeRuntimePhase.ACTIVE, runtime.snapshot().phase)
        assertTrue(runtime.snapshot().fence.audioGateOpen)
        assertEquals(1L, runtime.snapshot().command.existingRecordingAttachSuccess)

        assertTrue(transport.emitAudioBlock())
        awaitOfferedFrames(runtime, 8)
        val started = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.SessionStarted
        assertEquals(2L, started.sessionId)
        val epoch = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.EpochStarted
        assertEquals(2L, epoch.sessionId)
        val firstPcm = runtime.canonicalSource.receive() as RealtimeCanonicalStreamEvent.PcmFrame
        assertEquals(2L, firstPcm.sessionId)
        assertEquals(0L, firstPcm.frame.startSampleIndex)

        assertTrue(runtime.stop().success)
        assertEquals(
            listOf(RealtimeControlCommand.START, RealtimeControlCommand.STOP),
            transport.sentCommands,
        )
        assertEquals(RealtimeRuntimePhase.IDLE, runtime.snapshot().phase)
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
        val sentCommands = mutableListOf<RealtimeControlCommand>()

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
            sentCommands += command
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
