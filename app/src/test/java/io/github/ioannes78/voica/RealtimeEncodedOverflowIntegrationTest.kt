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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 15.7-B deterministic encoded-overflow integration gate.
 *
 * The production decoder worker is deliberately stalled on its first packet while the real
 * RealtimeAudioIngress receives more than its production 32-block capacity. The test then releases
 * the worker and verifies that the live-edge block becomes an explicit DISCONTINUITY epoch rather
 * than fabricated continuous media time.
 */
class RealtimeEncodedOverflowIntegrationTest {
    @Test
    fun productionCapacityOverflowRecoversAsNewDiscontinuityEpoch() = runBlocking {
        val ingress = RealtimeAudioIngress()
        val backend = BlockingFirstDecodeBackend()
        val frames: MutableList<RealtimeCanonicalPcmFrame> =
            Collections.synchronizedList(mutableListOf())
        val pipeline = RealtimeCanonicalAudioPipeline(
            ingress = ingress,
            packetInspector = backend,
            decoderFactory = backend,
            onFrame = { frames += it },
            nowNanos = { 0L },
        )
        pipeline.beginRealtimeSession()
        val worker = launch(Dispatchers.Default) { pipeline.run() }

        try {
            assertEquals(
                RealtimeAudioOfferResult.ACCEPTED,
                ingress.offer(audio(fill = 1), sessionGeneration = 7L, receivedAtNanos = 1L),
            )
            assertTrue("decoder did not enter deterministic stall", backend.awaitBlocked())

            repeat(RealtimeAudioIngress.DEFAULT_MAX_BLOCKS) { index ->
                assertEquals(
                    RealtimeAudioOfferResult.ACCEPTED,
                    ingress.offer(
                        audio(fill = index + 2),
                        sessionGeneration = 7L,
                        receivedAtNanos = (index + 2).toLong(),
                    ),
                )
            }

            var ingressSnapshot = ingress.snapshot()
            assertEquals(RealtimeAudioIngress.DEFAULT_MAX_BLOCKS, ingressSnapshot.queueBlocks)
            assertEquals(
                RealtimeAudioIngress.DEFAULT_MAX_ENCODED_BYTES,
                ingressSnapshot.queueBytes,
            )
            assertEquals(0L, ingressSnapshot.overflowEvents)

            assertEquals(
                RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
                ingress.offer(
                    audio(fill = 99),
                    sessionGeneration = 7L,
                    receivedAtNanos = 99L,
                ),
            )

            ingressSnapshot = ingress.snapshot()
            assertEquals(1L, ingressSnapshot.overflowEvents)
            assertEquals(
                RealtimeAudioIngress.DEFAULT_MAX_BLOCKS.toLong(),
                ingressSnapshot.droppedBlocks,
            )
            assertEquals(
                RealtimeAudioIngress.DEFAULT_MAX_ENCODED_BYTES.toLong(),
                ingressSnapshot.droppedBytes,
            )
            assertEquals(1L, ingressSnapshot.continuityId)
            assertEquals(1, ingressSnapshot.queueBlocks)
            assertEquals(RealtimeAudioIngress.REALTIME_BLOCK_BYTES, ingressSnapshot.queueBytes)

            backend.release()
            withTimeout(3_000L) {
                while (frames.size < 8) delay(5L)
            }

            val firstEpoch = frames.take(4)
            val recoveredEpoch = frames.drop(4).take(4)
            assertEquals(setOf(1L), firstEpoch.map { it.epochId }.toSet())
            assertEquals(
                RealtimeCanonicalEpochStartReason.SESSION_START,
                firstEpoch.first().epochStartReason,
            )
            assertEquals(setOf(2L), recoveredEpoch.map { it.epochId }.toSet())
            assertEquals(0L, recoveredEpoch.first().startSampleIndex)
            assertEquals(
                RealtimeCanonicalEpochStartReason.DISCONTINUITY,
                recoveredEpoch.first().epochStartReason,
            )
            assertEquals(
                listOf(0L, 320L, 640L, 960L),
                recoveredEpoch.map { it.startSampleIndex },
            )

            val pipelineSnapshot = pipeline.snapshot()
            assertEquals(1L, pipelineSnapshot.sessionCount)
            assertEquals(2L, pipelineSnapshot.epochCount)
            assertEquals(1L, pipelineSnapshot.discontinuityCount)
            assertEquals(1L, pipelineSnapshot.transportBoundaryCount)
            assertEquals(2L, pipelineSnapshot.receivedBlocks)
            assertEquals(8L, pipelineSnapshot.packetizedPackets)
            assertEquals(8L, pipelineSnapshot.decodeSuccess)
            assertEquals(2_560L, pipelineSnapshot.canonicalSamples)
            assertEquals(1_280L, pipelineSnapshot.nextSampleIndex)
            assertTrue(pipelineSnapshot.decoderResetCount >= 1L)
        } finally {
            backend.release()
            ingress.close()
            worker.join()
            pipeline.close()
        }
    }

    private fun audio(fill: Int): RoutedNotification = RoutedNotification(
        source = NotificationSource.AE22,
        frame = ProtocolFrame(
            sequence = 0,
            data = byteArrayOf(
                ProtocolConstants.Type.REALTIME.toByte(),
                ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
            ) + ByteArray(RealtimeAudioIngress.REALTIME_BLOCK_BYTES) { fill.toByte() },
        ),
    )

    private class BlockingFirstDecodeBackend : OpusPacketInspector, OpusDecoderFactory {
        private val firstDecode = AtomicBoolean(true)
        private val decodeEntered = CountDownLatch(1)
        private val releaseDecode = CountDownLatch(1)

        override val version: String = "stage15.7-blocking-fake"

        fun awaitBlocked(): Boolean = decodeEntered.await(2L, TimeUnit.SECONDS)

        fun release() {
            releaseDecode.countDown()
        }

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
                    if (firstDecode.compareAndSet(true, false)) {
                        decodeEntered.countDown()
                        check(releaseDecode.await(2L, TimeUnit.SECONDS)) {
                            "deterministic decode stall was not released"
                        }
                    }
                    return ShortArray(320)
                }

                override fun reset() = Unit

                override fun close() = Unit
            }
    }
}
