package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneSignalAccumulatorTest {
    @Test
    fun `aggregate calibration metrics span all accepted frames`() {
        val accumulator = PhoneMicrophoneSignalAccumulator()

        val first = ShortArray(1_280) { 1_000 }
        val second = ShortArray(1_280) { 2_000 }
        val firstSummary = accumulator.add(first)
        val finalSummary = accumulator.add(second)

        assertEquals(1L, firstSummary.frameCount)
        assertEquals(2L, finalSummary.frameCount)
        assertEquals(2_560L, finalSummary.sampleCount)
        assertEquals(2_000, finalSummary.peakSample)
        assertTrue(finalSummary.rms > 1_000.0)
        assertTrue(finalSummary.rms < 2_000.0)
        assertEquals(
            PhoneMicrophoneSignalMeter.analyze(first).rmsDbfs,
            finalSummary.noiseFloorCandidateDbfs,
            0.0001,
        )
        assertEquals(0L, finalSummary.clippingSamples)
        assertEquals(0.0, finalSummary.clippingRatio, 0.0)
        assertEquals(0L, finalSummary.zeroSamples)
        assertEquals(0.0, finalSummary.zeroSampleRatio, 0.0)
    }

    @Test
    fun `aggregate clipping and zero ratios use total sample count`() {
        val accumulator = PhoneMicrophoneSignalAccumulator()

        accumulator.add(shortArrayOf(0, Short.MAX_VALUE))
        val summary = accumulator.add(shortArrayOf(0, Short.MIN_VALUE))

        assertEquals(4L, summary.sampleCount)
        assertEquals(2L, summary.clippingSamples)
        assertEquals(0.5, summary.clippingRatio, 0.0001)
        assertEquals(2L, summary.zeroSamples)
        assertEquals(0.5, summary.zeroSampleRatio, 0.0001)
        assertEquals(32_768, summary.peakSample)
        assertEquals(0.0, summary.peakDbfs, 0.0001)
    }
}
