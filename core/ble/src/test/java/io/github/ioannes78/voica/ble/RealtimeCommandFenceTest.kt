package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCommandFenceTest {
    @Test
    fun startFenceBuffersThenCommitsInOrder() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val token = fence.prepare(RealtimeControlCommand.START, transportGeneration = 7)

        assertEquals(
            RealtimeAudioOfferResult.ACCEPTED,
            fence.offer(audioNotification(sequence = 1), 7, 100),
        )
        assertEquals(
            RealtimeAudioOfferResult.ACCEPTED,
            fence.offer(audioNotification(sequence = 2), 7, 200),
        )
        assertTrue(downstream.frames.isEmpty())

        assertEquals(RealtimeCommandFenceCommitResult.COMMITTED, fence.commit(token))
        assertEquals(listOf(1, 2), downstream.frames.map { it.notification.frame.sequence })
        assertEquals(2, fence.snapshot().peakBufferedFrames)
        assertEquals(1L, fence.snapshot().committedCount)
    }

    @Test
    fun abortDropsBufferedAudioWithoutForwarding() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val token = fence.prepare(RealtimeControlCommand.START, transportGeneration = 3)
        fence.offer(audioNotification(sequence = 4), 3, 100)

        assertTrue(fence.abort(token))
        assertTrue(downstream.frames.isEmpty())
        assertEquals(1L, fence.snapshot().droppedFrames)
        assertEquals(1L, fence.snapshot().abortedCount)
    }

    @Test
    fun overflowMakesFenceAmbiguousAndNeverReleasesPartialAudio() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(
            downstream = downstream,
            maxBufferedFrames = 1,
            maxBufferedBytes = 400,
        )
        val token = fence.prepare(RealtimeControlCommand.RESUME, transportGeneration = 5)
        fence.offer(audioNotification(sequence = 1), 5, 100)

        assertEquals(
            RealtimeAudioOfferResult.REJECTED,
            fence.offer(audioNotification(sequence = 2), 5, 200),
        )
        assertEquals(RealtimeCommandFenceCommitResult.AMBIGUOUS, fence.commit(token))
        assertTrue(downstream.frames.isEmpty())
        assertEquals(1L, fence.snapshot().overflowCount)
        assertEquals(1L, fence.snapshot().ambiguousCount)
        assertEquals(2L, fence.snapshot().droppedFrames)
    }

    @Test
    fun staleGenerationOfferMarksFenceAmbiguous() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val token = fence.prepare(RealtimeControlCommand.START, transportGeneration = 10)

        assertEquals(
            RealtimeAudioOfferResult.REJECTED,
            fence.offer(audioNotification(sequence = 1), 11, 100),
        )
        assertEquals(RealtimeCommandFenceCommitResult.AMBIGUOUS, fence.commit(token))
        assertEquals(1L, fence.snapshot().staleOfferCount)
        assertTrue(downstream.frames.isEmpty())
    }

    @Test
    fun sessionCloseAbortsMatchingFenceAndPropagatesClose() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val token = fence.prepare(RealtimeControlCommand.START, transportGeneration = 9)
        fence.offer(audioNotification(sequence = 1), 9, 100)

        fence.onSessionClosed(9)

        assertEquals(listOf(9L), downstream.closedGenerations)
        assertFalse(fence.abort(token))
        assertEquals(1L, fence.snapshot().droppedFrames)
    }

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

    private class CapturingConsumer : RealtimeAudioConsumer {
        val frames = mutableListOf<CapturedFrame>()
        val closedGenerations = mutableListOf<Long>()

        override fun offer(
            notification: RoutedNotification,
            sessionGeneration: Long,
            receivedAtNanos: Long,
        ): RealtimeAudioOfferResult {
            frames += CapturedFrame(notification, sessionGeneration, receivedAtNanos)
            return RealtimeAudioOfferResult.ACCEPTED
        }

        override fun onSessionClosed(sessionGeneration: Long) {
            closedGenerations += sessionGeneration
        }
    }

    private data class CapturedFrame(
        val notification: RoutedNotification,
        val sessionGeneration: Long,
        val receivedAtNanos: Long,
    )
}
