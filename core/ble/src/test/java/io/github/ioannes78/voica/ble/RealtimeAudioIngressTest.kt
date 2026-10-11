package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioIngressTest {
    @Test
    fun valid160ByteBodyIsQueuedAndSplitIntoFourOrdered40BytePackets() {
        val ingress = RealtimeAudioIngress()
        val body = ByteArray(160) { it.toByte() }
        val notification = audio(body = body, sequence = 0, source = NotificationSource.AE22)

        val result = ingress.offer(
            notification = notification,
            sessionGeneration = 7L,
            receivedAtNanos = 123_456L,
        )

        assertEquals(RealtimeAudioOfferResult.ACCEPTED, result)
        val block = ingress.poll() ?: error("missing block")
        assertEquals(7L, block.sessionGeneration)
        assertEquals(0L, block.continuityId)
        assertEquals(NotificationSource.AE22, block.source)
        assertEquals(0, block.protocolSequence)
        assertEquals(123_456L, block.receivedAtNanos)
        assertFalse(block.discontinuityBefore)
        assertArrayEquals(body, block.encodedBytes)

        val packets = RealtimeOpusPacketizer.split(block)
        assertEquals(4, packets.size)
        packets.forEachIndexed { index, packet ->
            assertEquals(index, packet.packetIndexInBlock)
            assertEquals(40, packet.encodedBytes.size)
            assertEquals(block.sessionGeneration, packet.sessionGeneration)
            assertEquals(block.continuityId, packet.continuityId)
            assertEquals(block.protocolSequence, packet.protocolSequence)
            assertEquals(block.receivedAtNanos, packet.receivedAtNanos)
            assertEquals(index == 0 && block.discontinuityBefore, packet.discontinuityBefore)
            val expected = body.copyOfRange(index * 40, index * 40 + 40)
            assertArrayEquals(expected, packet.encodedBytes)
        }

        val snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.acceptedBlocks)
        assertEquals(160L, snapshot.acceptedBytes)
        assertEquals(0, snapshot.queueBlocks)
        assertEquals(0, snapshot.queueBytes)
    }

    @Test
    fun overflowDropsStaleBacklogAndAcceptsLiveEdgeWithDiscontinuity() {
        val ingress = RealtimeAudioIngress(maxBlocks = 3, maxEncodedBytes = 640)
        repeat(3) { index ->
            assertEquals(
                RealtimeAudioOfferResult.ACCEPTED,
                ingress.offer(audio(fill = index), 1L, index.toLong()),
            )
        }

        val overflowResult = ingress.offer(audio(fill = 99), 1L, 99L)
        assertEquals(RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY, overflowResult)

        val snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.overflowEvents)
        assertEquals(3L, snapshot.droppedBlocks)
        assertEquals(480L, snapshot.droppedBytes)
        assertEquals(1, snapshot.queueBlocks)
        assertEquals(160, snapshot.queueBytes)
        assertEquals(1L, snapshot.continuityId)

        val live = ingress.poll() ?: error("missing live-edge block")
        assertTrue(live.discontinuityBefore)
        assertEquals(1L, live.continuityId)
        assertEquals(99.toByte(), live.encodedBytes.first())
        assertNull(ingress.poll())
    }

    @Test
    fun encodedByteLimitAlsoTriggersLiveEdgeRecovery() {
        val ingress = RealtimeAudioIngress(maxBlocks = 12, maxEncodedBytes = 320)
        assertEquals(RealtimeAudioOfferResult.ACCEPTED, ingress.offer(audio(fill = 1), 1L, 1L))
        assertEquals(RealtimeAudioOfferResult.ACCEPTED, ingress.offer(audio(fill = 2), 1L, 2L))
        assertEquals(
            RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
            ingress.offer(audio(fill = 3), 1L, 3L),
        )

        val snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.overflowEvents)
        assertEquals(2L, snapshot.droppedBlocks)
        assertEquals(320L, snapshot.droppedBytes)
        assertEquals(1, snapshot.queueBlocks)
        assertEquals(160, snapshot.queueBytes)
    }

    @Test
    fun malformedBodyCreatesDiscontinuityAndDoesNotQueueGuessedFraming() {
        val ingress = RealtimeAudioIngress()
        assertEquals(RealtimeAudioOfferResult.ACCEPTED, ingress.offer(audio(fill = 1), 5L, 1L))

        val malformed = ingress.offer(audio(body = ByteArray(159)), 5L, 2L)
        assertEquals(RealtimeAudioOfferResult.REJECTED, malformed)
        assertNull(ingress.poll())

        val recovery = ingress.offer(audio(fill = 2), 5L, 3L)
        assertEquals(RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY, recovery)
        val recovered = ingress.poll() ?: error("missing recovered block")
        assertTrue(recovered.discontinuityBefore)
        assertEquals(1L, recovered.continuityId)

        val snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.malformedBlocks)
        assertEquals(1L, snapshot.droppedBlocks)
    }

    @Test
    fun generationChangeClearsOldBacklogAndMarksNewGenerationBoundary() {
        val ingress = RealtimeAudioIngress()
        assertEquals(RealtimeAudioOfferResult.ACCEPTED, ingress.offer(audio(fill = 1), 10L, 1L))

        val result = ingress.offer(audio(fill = 2), 11L, 2L)
        assertEquals(RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)

        val block = ingress.poll() ?: error("missing new-generation block")
        assertEquals(11L, block.sessionGeneration)
        assertEquals(1L, block.continuityId)
        assertTrue(block.discontinuityBefore)
        assertEquals(2.toByte(), block.encodedBytes.first())

        val snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.generationTransitions)
        assertEquals(1L, snapshot.droppedBlocks)
        assertEquals(11L, snapshot.activeSessionGeneration)
    }

    @Test
    fun sessionCloseDropsBacklogAndNextGenerationStartsAfterBoundary() {
        val ingress = RealtimeAudioIngress()
        ingress.offer(audio(fill = 1), 20L, 1L)
        ingress.onSessionClosed(20L)

        var snapshot = ingress.snapshot()
        assertEquals(1L, snapshot.sessionClosures)
        assertEquals(1L, snapshot.droppedBlocks)
        assertNull(snapshot.activeSessionGeneration)

        val result = ingress.offer(audio(fill = 2), 21L, 2L)
        assertEquals(RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)
        val block = ingress.poll() ?: error("missing next-session block")
        assertTrue(block.discontinuityBefore)
        assertEquals(1L, block.continuityId)

        snapshot = ingress.snapshot()
        assertEquals(21L, snapshot.activeSessionGeneration)
    }

    @Test
    fun nonRealtimeAudioFrameIsIgnored() {
        val ingress = RealtimeAudioIngress()
        val event = RoutedNotification(
            source = NotificationSource.AE22,
            frame = ProtocolFrame(
                sequence = 4,
                data = byteArrayOf(
                    ProtocolConstants.Type.REALTIME.toByte(),
                    ProtocolConstants.Realtime.DEVICE_STATE.toByte(),
                    1,
                ),
            ),
        )

        assertEquals(RealtimeAudioOfferResult.IGNORED, ingress.offer(event, 1L, 1L))
        assertEquals(0L, ingress.snapshot().acceptedBlocks)
        assertNull(ingress.poll())
    }

    private fun audio(
        fill: Int = 0,
        sequence: Int = 0,
        source: NotificationSource = NotificationSource.AE22,
        body: ByteArray = ByteArray(160) { fill.toByte() },
    ): RoutedNotification = RoutedNotification(
        source = source,
        frame = ProtocolFrame(
            sequence = sequence,
            data = byteArrayOf(
                ProtocolConstants.Type.REALTIME.toByte(),
                ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
            ) + body,
        ),
    )
}
