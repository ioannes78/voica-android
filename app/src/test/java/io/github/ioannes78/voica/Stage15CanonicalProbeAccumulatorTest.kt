package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage15CanonicalProbeAccumulatorTest {
    @Test
    fun contiguousFramesStayGapAndOverlapFree() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(frame(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(frame(start = 320))
        accumulator.accept(frame(start = 640))

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
        accumulator.accept(frame(start = 0, epoch = 1, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(frame(start = 320, epoch = 1))
        accumulator.accept(frame(start = 0, epoch = 2, reason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY))

        val snapshot = accumulator.snapshot()
        assertEquals(2L, snapshot.epochsSeen)
        assertEquals(0L, snapshot.gapCount)
        assertEquals(0L, snapshot.overlapCount)
        assertEquals(0L, snapshot.invalidEpochStartCount)
    }

    @Test
    fun detectsGapAndOverlapWithinSameEpoch() {
        val accumulator = Stage15CanonicalProbeAccumulator()
        accumulator.accept(frame(start = 0, reason = RealtimeCanonicalEpochStartReason.SESSION_START))
        accumulator.accept(frame(start = 400))
        accumulator.accept(frame(start = 650))

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.gapCount)
        assertEquals(1L, snapshot.overlapCount)
    }

    private fun frame(
        start: Long,
        epoch: Long = 1,
        reason: RealtimeCanonicalEpochStartReason? = null,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = 1,
        epochId = epoch,
        startSampleIndex = start,
        samples = ShortArray(320),
        epochStartReason = reason,
    )
}
