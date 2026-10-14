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
    fun `overflow drops only oldest pcm needed to preserve live backlog`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 2, maxQueuedPcmSamples = 640)
        buffer.offer(StreamingAudioEvent.SessionStarted(1))
        buffer.offer(StreamingAudioEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 0)))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 320)))

        val result = buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 640)))
        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)

        assertTrue(buffer.receive() is StreamingAudioEvent.SessionStarted)
        assertTrue(buffer.receive() is StreamingAudioEvent.EpochStarted)
        val gap = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW, gap.reason)
        assertEquals(1L, gap.droppedFrames)
        assertEquals(320L, gap.droppedSamples)
        assertEquals(320L, gap.resumeAtSampleIndex)
        val retained = buffer.receive() as StreamingAudioEvent.PcmFrame
        assertEquals(320L, retained.frame.startSampleIndex)
        val live = buffer.receive() as StreamingAudioEvent.PcmFrame
        assertEquals(640L, live.frame.startSampleIndex)

        val snapshot = buffer.snapshot()
        assertEquals(1L, snapshot.overflowEvents)
        assertEquals(1L, snapshot.droppedFrames)
        assertEquals(320L, snapshot.droppedSamples)
    }

    @Test
    fun `sample budget drops minimum number of oldest frames`() = runBlocking {
        val buffer = StreamingAudioBuffer(maxQueuedPcmFrames = 8, maxQueuedPcmSamples = 800)
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 0)))
        buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 320)))

        val result = buffer.offer(StreamingAudioEvent.PcmFrame(frame(start = 640)))
        assertEquals(StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)

        val gap = buffer.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(1L, gap.droppedFrames)
        assertEquals(320L, gap.droppedSamples)
        assertEquals(320L, gap.resumeAtSampleIndex)
        assertEquals(320L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(640L, (buffer.receive() as StreamingAudioEvent.PcmFrame).frame.startSampleIndex)
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
