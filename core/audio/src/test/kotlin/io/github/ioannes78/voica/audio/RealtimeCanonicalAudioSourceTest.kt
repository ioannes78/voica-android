package io.github.ioannes78.voica.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCanonicalAudioSourceTest {
    @Test
    fun `first frame emits session epoch and pcm in order`() = runBlocking {
        val source = RealtimeCanonicalAudioSource()
        val frame = frame(sessionId = 1, epochId = 1, start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START)

        assertEquals(RealtimeCanonicalOfferResult.ACCEPTED, source.offer(frame))
        assertEquals(RealtimeCanonicalStreamEvent.SessionStarted(1), source.receive())
        assertEquals(
            RealtimeCanonicalStreamEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
            source.receive(),
        )
        val pcm = source.receive() as RealtimeCanonicalStreamEvent.PcmFrame
        assertEquals(0L, pcm.frame.startSampleIndex)
        assertEquals(320, pcm.frame.sampleCount)

        val snapshot = source.snapshot()
        assertTrue(snapshot.sessionActive)
        assertEquals(1L, snapshot.sessionStartedEvents)
        assertEquals(1L, snapshot.epochStartedEvents)
        assertEquals(1L, snapshot.offeredFrames)
    }

    @Test
    fun `same epoch emits only pcm while new epoch emits boundary`() = runBlocking {
        val source = RealtimeCanonicalAudioSource()
        source.offer(frame(1, 1, 0, RealtimeCanonicalEpochStartReason.SESSION_START))
        repeat(3) { source.receive() }

        source.offer(frame(1, 1, 320, null))
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.PcmFrame)

        source.offer(frame(1, 2, 0, RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY))
        assertEquals(
            RealtimeCanonicalStreamEvent.EpochStarted(1, 2, RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY),
            source.receive(),
        )
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.PcmFrame)
    }

    @Test
    fun `pcm overflow drops stale pcm and emits explicit discontinuity`() = runBlocking {
        val source = RealtimeCanonicalAudioSource(maxQueuedPcmFrames = 2, maxQueuedPcmSamples = 640)
        source.offer(frame(1, 1, 0, RealtimeCanonicalEpochStartReason.SESSION_START))
        source.offer(frame(1, 1, 320, null))
        val result = source.offer(frame(1, 1, 640, null))

        assertEquals(RealtimeCanonicalOfferResult.ACCEPTED_AFTER_DISCONTINUITY, result)
        assertEquals(RealtimeCanonicalStreamEvent.SessionStarted(1), source.receive())
        assertEquals(
            RealtimeCanonicalStreamEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
            source.receive(),
        )
        val discontinuity = source.receive() as RealtimeCanonicalStreamEvent.ConsumerDiscontinuity
        assertEquals(2L, discontinuity.droppedFrames)
        assertEquals(640L, discontinuity.droppedSamples)
        assertEquals(640L, discontinuity.resumeAtSampleIndex)
        val live = source.receive() as RealtimeCanonicalStreamEvent.PcmFrame
        assertEquals(640L, live.frame.startSampleIndex)

        val snapshot = source.snapshot()
        assertEquals(1L, snapshot.overflowEvents)
        assertEquals(2L, snapshot.droppedFrames)
        assertEquals(640L, snapshot.droppedSamples)
        assertEquals(1L, snapshot.consumerDiscontinuities)
        assertEquals(1, snapshot.queuedPcmFrames)
    }

    @Test
    fun `stop drain appends session ended after queued pcm`() = runBlocking {
        var now = 1_000_000_000L
        val source = RealtimeCanonicalAudioSource(
            nowNanos = { now },
            sleeper = { millis -> now += millis * 1_000_000L },
        )
        source.offer(frame(1, 1, 0, RealtimeCanonicalEpochStartReason.SESSION_START))

        val result = source.endActiveSessionAfterQuiet(
            reason = RealtimeCanonicalSessionEndReason.STOP,
            quietPeriodMs = 250,
            maxDrainMs = 1_500,
        )
        assertEquals(RealtimeCanonicalSessionEndResult.ENDED, result)

        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.SessionStarted)
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.EpochStarted)
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.PcmFrame)
        val ended = source.receive() as RealtimeCanonicalStreamEvent.SessionEnded
        assertEquals(1L, ended.sessionId)
        assertEquals(1L, ended.finalEpochId)
        assertEquals(320L, ended.finalSampleIndexExclusive)
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, ended.reason)
        assertFalse(ended.drainTimedOut)
        assertFalse(source.snapshot().sessionActive)
    }

    @Test
    fun `new session implicitly ends previous session if stop lifecycle was missed`() = runBlocking {
        val source = RealtimeCanonicalAudioSource()
        source.offer(frame(1, 1, 0, RealtimeCanonicalEpochStartReason.SESSION_START))
        repeat(3) { source.receive() }

        source.offer(frame(2, 1, 0, RealtimeCanonicalEpochStartReason.SESSION_START))
        val ended = source.receive() as RealtimeCanonicalStreamEvent.SessionEnded
        assertEquals(1L, ended.sessionId)
        assertEquals(RealtimeCanonicalSessionEndReason.SUPERSEDED_BY_NEW_SESSION, ended.reason)
        assertEquals(RealtimeCanonicalStreamEvent.SessionStarted(2), source.receive())
        assertEquals(
            RealtimeCanonicalStreamEvent.EpochStarted(2, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
            source.receive(),
        )
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.PcmFrame)
    }

    private fun frame(
        sessionId: Long,
        epochId: Long,
        start: Long,
        reason: RealtimeCanonicalEpochStartReason?,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = epochId,
        startSampleIndex = start,
        samples = ShortArray(320) { 7 },
        epochStartReason = reason,
    )
}
