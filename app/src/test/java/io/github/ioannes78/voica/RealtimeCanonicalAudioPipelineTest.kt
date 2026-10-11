package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.audio.OpusPacketInspector
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioOfferResult
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCanonicalAudioPipelineTest {
    @Test
    fun decodesFourPacketsAndAdvancesEpochTimelineByActualSamples() = runBlocking {
        val harness = Harness(FakeBackend())
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock()
        harness.awaitFrames(4)
        harness.stop()

        val frames = harness.frames.toList()
        assertEquals(listOf(0L, 320L, 640L, 960L), frames.map { it.startSampleIndex })
        assertEquals(listOf(1L, 1L, 1L, 1L), frames.map { it.sessionId })
        assertEquals(listOf(1L, 1L, 1L, 1L), frames.map { it.epochId })
        assertEquals(RealtimeCanonicalEpochStartReason.SESSION_START, frames.first().epochStartReason)
        assertEquals(listOf(null, null, null), frames.drop(1).map { it.epochStartReason })

        val snapshot = harness.pipeline.snapshot()
        assertEquals(1L, snapshot.sessionCount)
        assertEquals(1L, snapshot.epochCount)
        assertEquals(1L, snapshot.receivedBlocks)
        assertEquals(4L, snapshot.packetizedPackets)
        assertEquals(4L, snapshot.decodeSuccess)
        assertEquals(1_280L, snapshot.canonicalSamples)
        assertEquals(1_280L, snapshot.nextSampleIndex)
        assertEquals(mapOf(320 to 4L), snapshot.decodedSampleHistogram)
    }

    @Test
    fun timelineUsesActualDecoderSampleCountsInsteadOfHardcoded320() = runBlocking {
        val backend = FakeBackend(
            decodePlan = listOf(
                DecodeStep.Samples(319),
                DecodeStep.Samples(321),
                DecodeStep.Samples(320),
                DecodeStep.Samples(318),
            ),
        )
        val harness = Harness(backend)
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock()
        harness.awaitFrames(4)
        harness.stop()

        assertEquals(listOf(0L, 319L, 640L, 960L), harness.frames.map { it.startSampleIndex })
        val snapshot = harness.pipeline.snapshot()
        assertEquals(3L, snapshot.unexpectedDecodedSampleCount)
        assertEquals(1_278L, snapshot.canonicalSamples)
        assertEquals(1_278L, snapshot.nextSampleIndex)
        assertEquals(mapOf(318 to 1L, 319 to 1L, 320 to 1L, 321 to 1L), snapshot.decodedSampleHistogram.toSortedMap())
    }

    @Test
    fun repeatedStartOnSameGattGenerationCreatesNewRealtimeSession() = runBlocking {
        val harness = Harness(FakeBackend())
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock(sessionGeneration = 7L)
        harness.awaitFrames(4)
        harness.pipeline.beginRealtimeSession()
        harness.offerAudioBlock(sessionGeneration = 7L)
        harness.awaitFrames(8)
        harness.stop()

        val first = harness.frames.take(4)
        val second = harness.frames.drop(4)
        assertEquals(setOf(1L), first.map { it.sessionId }.toSet())
        assertEquals(setOf(2L), second.map { it.sessionId }.toSet())
        assertEquals(0L, second.first().startSampleIndex)
        assertEquals(1L, second.first().epochId)
        assertEquals(RealtimeCanonicalEpochStartReason.SESSION_START, second.first().epochStartReason)

        val snapshot = harness.pipeline.snapshot()
        assertEquals(2L, snapshot.sessionCount)
        assertEquals(2L, snapshot.epochCount)
        assertTrue(snapshot.decoderResetCount >= 1L)
    }

    @Test
    fun controlBoundaryCreatesNewEpochAndResetsEpochLocalSampleIndex() = runBlocking {
        val harness = Harness(FakeBackend())
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock()
        harness.awaitFrames(4)
        harness.pipeline.requestControlBoundary()
        harness.offerAudioBlock()
        harness.awaitFrames(8)
        harness.stop()

        val secondEpoch = harness.frames.drop(4)
        assertEquals(setOf(1L), secondEpoch.map { it.sessionId }.toSet())
        assertEquals(setOf(2L), secondEpoch.map { it.epochId }.toSet())
        assertEquals(0L, secondEpoch.first().startSampleIndex)
        assertEquals(
            RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY,
            secondEpoch.first().epochStartReason,
        )

        val snapshot = harness.pipeline.snapshot()
        assertEquals(1L, snapshot.controlBoundaryCount)
        assertEquals(1L, snapshot.discontinuityCount)
        assertEquals(2L, snapshot.epochCount)
    }

    @Test
    fun decodeFailureSkipsMissingPacketAndStartsRecoveryEpochWithoutSyntheticSamples() = runBlocking {
        val backend = FakeBackend(
            decodePlan = listOf(
                DecodeStep.Samples(320),
                DecodeStep.Fail,
                DecodeStep.Samples(320),
                DecodeStep.Samples(320),
            ),
        )
        val harness = Harness(backend)
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock()
        harness.awaitFrames(3)
        harness.stop()

        val frames = harness.frames.toList()
        assertEquals(3, frames.size)
        assertEquals(1L, frames[0].epochId)
        assertEquals(0L, frames[0].startSampleIndex)
        assertEquals(2L, frames[1].epochId)
        assertEquals(0L, frames[1].startSampleIndex)
        assertEquals(RealtimeCanonicalEpochStartReason.DECODER_RECOVERY, frames[1].epochStartReason)
        assertEquals(320L, frames[2].startSampleIndex)

        val snapshot = harness.pipeline.snapshot()
        assertEquals(1L, snapshot.decodeFailures)
        assertEquals(1L, snapshot.decoderRecoveryBoundaryCount)
        assertEquals(2L, snapshot.epochCount)
        assertEquals(960L, snapshot.canonicalSamples)
        assertEquals(640L, snapshot.nextSampleIndex)
    }

    @Test
    fun reconnectGenerationCreatesNewSessionRatherThanInventingContinuity() = runBlocking {
        val harness = Harness(FakeBackend())
        harness.pipeline.beginRealtimeSession()
        harness.start()

        harness.offerAudioBlock(sessionGeneration = 11L)
        harness.awaitFrames(4)
        harness.offerAudioBlock(sessionGeneration = 12L)
        harness.awaitFrames(8)
        harness.stop()

        assertEquals(2L, harness.frames[4].sessionId)
        assertEquals(1L, harness.frames[4].epochId)
        assertEquals(0L, harness.frames[4].startSampleIndex)
        val snapshot = harness.pipeline.snapshot()
        assertEquals(2L, snapshot.sessionCount)
        assertEquals(1L, snapshot.transportBoundaryCount)
        assertEquals(1L, snapshot.discontinuityCount)
    }

    private class Harness(backend: FakeBackend) {
        val ingress = RealtimeAudioIngress()
        val frames: MutableList<RealtimeCanonicalPcmFrame> =
            Collections.synchronizedList(mutableListOf())
        val pipeline = RealtimeCanonicalAudioPipeline(
            ingress = ingress,
            packetInspector = backend,
            decoderFactory = backend,
            onFrame = { frames += it },
            nowNanos = { 0L },
        )
        private var job: kotlinx.coroutines.Job? = null

        fun start() {
            check(job == null)
            job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { pipeline.run() }
        }

        fun offerAudioBlock(sessionGeneration: Long = 1L) {
            val body = ByteArray(RealtimeAudioIngress.REALTIME_BLOCK_BYTES) { index -> index.toByte() }
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
            val result = ingress.offer(
                notification = notification,
                sessionGeneration = sessionGeneration,
                receivedAtNanos = 0L,
            )
            assertTrue(
                result == RealtimeAudioOfferResult.ACCEPTED ||
                    result == RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
            )
        }

        suspend fun awaitFrames(count: Int) {
            withTimeout(2_000L) {
                while (frames.size < count) delay(5L)
            }
        }

        suspend fun stop() {
            ingress.close()
            job?.join()
            pipeline.close()
        }
    }

    private sealed interface DecodeStep {
        data class Samples(val count: Int) : DecodeStep
        data object Fail : DecodeStep
    }

    private class FakeBackend(
        decodePlan: List<DecodeStep> = emptyList(),
    ) : OpusPacketInspector, OpusDecoderFactory {
        private val plan = ArrayDeque(decodePlan)

        override val version: String = "fake"

        override fun inspect(packet: ByteArray): OpusPacketInfo = OpusPacketInfo(
            valid = true,
            channels = 1,
            totalSamples48k = 960,
        )

        override fun create(sampleRateHz: Int, channelCount: Int): OpusDecoder =
            object : OpusDecoder {
                override val sampleRateHz: Int = sampleRateHz
                override val channelCount: Int = channelCount

                override fun decode(packet: ByteArray): ShortArray {
                    val step = if (plan.isEmpty()) DecodeStep.Samples(320) else plan.removeFirst()
                    return when (step) {
                        is DecodeStep.Samples -> ShortArray(step.count)
                        DecodeStep.Fail -> error("synthetic decode failure")
                    }
                }

                override fun reset() = Unit

                override fun close() = Unit
            }
    }
}
