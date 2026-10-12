package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalConsumerDiscontinuityReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage15CanonicalProbeAccumulatorTest {
    @Test
    fun contiguousFramesStayGapAndOverlapFree() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(pcmEvent(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(pcmEvent(start = 320))
        accumulator.accept(pcmEvent(start = 640))

        val snapshot = accumulator.snapshot()
        assertEquals(3L, snapshot.frames)
        assertEquals(960L, snapshot.samples)
        assertEquals(0L, snapshot.gapCount)
        assertEquals(0L, snapshot.overlapCount)
        assertEquals(0L, snapshot.invalidEpochStartCount)
    }

    @Test
    fun newEpochMustRestartAtZeroAndCarryReason() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(pcmEvent(start = 0, epoch = 1, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(pcmEvent(start = 320, epoch = 1))
        accumulator.accept(pcmEvent(start = 0, epoch = 2, reason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY))

        val snapshot = accumulator.snapshot()
        assertEquals(2L, snapshot.epochsSeen)
        assertEquals(0L, snapshot.gapCount)
        assertEquals(0L, snapshot.overlapCount)
        assertEquals(0L, snapshot.invalidEpochStartCount)
    }

    @Test
    fun detectsGapAndOverlapWithinSameEpoch() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(pcmEvent(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(pcmEvent(start = 400))
        accumulator.accept(pcmEvent(start = 650))

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.gapCount)
        assertEquals(1L, snapshot.overlapCount)
    }

    @Test
    fun acknowledgedConsumerDiscontinuityDoesNotCountAsUnexpectedGap() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(pcmEvent(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(pcmEvent(start = 320))
        accumulator.accept(
            RealtimeCanonicalStreamEvent.ConsumerDiscontinuity(
                sessionId = 1,
                epochId = 1,
                resumeAtSampleIndex = 1_600,
                reason = RealtimeCanonicalConsumerDiscontinuityReason.BACKPRESSURE,
                droppedFrames = 3,
                droppedSamples = 960,
            ),
        )
        accumulator.accept(pcmEvent(start = 1_600))

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.consumerDiscontinuities)
        assertEquals(1L, snapshot.consumerGapAcknowledged)
        assertEquals(0L, snapshot.gapCount)
        assertEquals(0L, snapshot.overlapCount)
    }

    @Test
    fun mismatchedConsumerResumeStillCountsAsUnexpectedGap() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(pcmEvent(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(pcmEvent(start = 320))
        accumulator.accept(
            RealtimeCanonicalStreamEvent.ConsumerDiscontinuity(
                sessionId = 1,
                epochId = 1,
                resumeAtSampleIndex = 1_600,
                reason = RealtimeCanonicalConsumerDiscontinuityReason.BACKPRESSURE,
                droppedFrames = 3,
                droppedSamples = 960,
            ),
        )
        accumulator.accept(pcmEvent(start = 1_920))

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.consumerDiscontinuities)
        assertEquals(0L, snapshot.consumerGapAcknowledged)
        assertEquals(1L, snapshot.gapCount)
    }

    @Test
    fun lifecycleEventsAndSessionEndAreObservedIndependently() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(RealtimeCanonicalStreamEvent.SessionStarted(1))
        accumulator.accept(
            RealtimeCanonicalStreamEvent.EpochStarted(
                sessionId = 1,
                epochId = 1,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        accumulator.accept(pcmEvent(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(
            RealtimeCanonicalStreamEvent.SessionEnded(
                sessionId = 1,
                finalEpochId = 1,
                finalSampleIndexExclusive = 320,
                reason = RealtimeCanonicalSessionEndReason.STOP,
                drainTimedOut = false,
            ),
        )

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.sessionStartedEvents)
        assertEquals(1L, snapshot.epochStartedEvents)
        assertEquals(1L, snapshot.sessionEndedEvents)
        assertEquals(0L, snapshot.sessionEndMismatchCount)
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, snapshot.lastSessionEndReason)
    }

    private fun pcmEvent(
        start: Long,
        epoch: Long = 1,
        reason: RealtimeCanonicalEpochStartReason? = null,
    ) = RealtimeCanonicalStreamEvent.PcmFrame(
        RealtimeCanonicalPcmFrame(
            sessionId = 1,
            epochId = epoch,
            startSampleIndex = start,
            samples = ShortArray(320),
            epochStartReason = reason,
        ),
    )
}
