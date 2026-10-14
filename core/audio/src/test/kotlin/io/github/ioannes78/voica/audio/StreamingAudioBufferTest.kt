package io.github.ioannes78.voica.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAudioBufferTest {
    @Test
    fun `lifecycle and pcm remain ordered`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 4, maxQueuedPcmSamples = 1_280)
        val frame = frame(start = 0)

        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED, buffer.offer(StreamingAudioEvent.SessionStarted(1)))
        assertEquals(
            StreamingAudioBufferOfferResult.ACCEPTED,
            buffer.offer(StreamingAudioEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START)),
        )
        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED, buffer.offer(StreamingAudioEvent.PcmFrame(frame)))
        buffer.finishInput()

        assertTrue(buffer.receive() is StreamingAudioEvent.SessionStarted)
        assertTrue(buffer.receive() is StreamingAudioEvent.EpochStarted)
        val pcm = buffer.receive() as StreamingAudioEvent.PcmFrame
        assertEquals(0L, pcm.frame.startSampleIndex)
        assertNull(buffer.receive())
    }

    @Test
    fun `overflow creates recovery headroom and one explicit gap`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 4, maxQueuedPcmSamples = 1_280)
        buffer.offer(StreamingAudioEvent.SessionStarted(1))
        buffer.offer(StreamingAudioEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 0)))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 320)))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 640)))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 960)))

        val result = buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 1_280)))
        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)

        assertTrue(buffer.receive() is StreamingAudioEvent.SessionStarted)
        assertTrue(buffer.receive() is StreamingAudioEvent.EpochStarted)
        val gap = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW, gap.reason)
        assertEquals(2L, gap.droppedFrames)
        assertEquals(640L, gap.droppedSamples)
        assertEquals(640L, gap.resumeAtSampleIndex)
        assertEquals(640L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(960L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(1_280L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)

        val snapshot = buffer.snapshot()
        assertEquals(1L, snapshot.overflowEvents)
        assertEquals(1L, snapshot.overflowEpisodes)
        assertEquals(2L, snapshot.droppedFrames)
        assertEquals(640L, snapshot.droppedSamples)
        assertEquals(2L, snapshot.maxConsecutiveDroppedFrames)
        assertEquals(640L, snapshot.maxConsecutiveDroppedSamples)
    }

    @Test
    fun `sustained pressure widens pending overflow gap instead of emitting finalize storm`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 4, maxQueuedPcmSamples = 1_280)
        buffer.offer(StreamingAudioEvent.SessionStarted(1))
        buffer.offer(StreamingAudioEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START))
        listOf(0L, 320L, 640L, 960L).forEach { start ->
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(start)))
        }

        assertEquals(
            StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(1_280))),
        )
        assertEquals(
            StreamingAudioBufferOfferResult.ACCEPTED,
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(1_600))),
        )
        assertEquals(
            StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(1_920))),
        )

        assertTrue(buffer.receive() is StreamingAudioEvent.SessionStarted)
        assertTrue(buffer.receive() is StreamingAudioEvent.EpochStarted)
        val gap = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW, gap.reason)
        assertEquals(3L, gap.droppedFrames)
        assertEquals(960L, gap.droppedSamples)
        assertEquals(960L, gap.resumeAtSampleIndex)
        listOf(960L, 1_280L, 1_600L, 1_920L).forEach { expected ->
            assertEquals(expected, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        }

        val snapshot = buffer.snapshot()
        assertEquals(2L, snapshot.overflowEvents)
        assertEquals(1L, snapshot.overflowEpisodes)
        assertEquals(3L, snapshot.droppedFrames)
        assertEquals(960L, snapshot.droppedSamples)
        assertEquals(3L, snapshot.maxConsecutiveDroppedFrames)
        assertEquals(960L, snapshot.maxConsecutiveDroppedSamples)
    }

    @Test
    fun `sample budget recovery drops oldest frames to recovery watermark`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 8, maxQueuedPcmSamples = 1_600)
        listOf(0L, 320L, 640L, 960L, 1_280L).forEach { start ->
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(start)))
        }

        val result = buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 1_600)))
        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)

        val gap = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(3L, gap.droppedFrames)
        assertEquals(960L, gap.droppedSamples)
        assertEquals(960L, gap.resumeAtSampleIndex)
        assertEquals(960L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(1_280L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(1_600L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
    }

    @Test
    fun `upstream discontinuity is retained explicitly`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 2, maxQueuedPcmSamples = 640)
        buffer.offer(
            StreamingAudioEvent.Discontinuity(
                sessionId = 1,
                epochId = 1,
                resumeAtSampleIndex = 960,
                reason = StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE,
                droppedFrames = 3,
                droppedSamples = 960,
            ),
        )
        buffer.finishInput()

        val event = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE, event.reason)
        assertEquals(960L, event.resumeAtSampleIndex)
        assertEquals(1L, buffer.snapshot().upstreamDiscontinuities)
        assertNull(buffer.receive())
    }

    @Test
    fun `finish input drains queued events before end of stream`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 2, maxQueuedPcmSamples = 640)
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 0)))
        buffer.finishInput()

        assertTrue(buffer.receive() is StreamingAudioEvent.PcmFrame)
        assertNull(buffer.receive())
        assertEquals(
            StreamingAudioBufferOfferResult.REJECTED_AFTER_FINISH,
            buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 320))),
        )
    }

    private fun frame(start: Long) = RealtimeCanonicalPcmFrame(
        sessionId = 1,
        epochId = 1,
        startSampleIndex = start,
        samples = ShortArray(320) { 9 },
        epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
    )
}
