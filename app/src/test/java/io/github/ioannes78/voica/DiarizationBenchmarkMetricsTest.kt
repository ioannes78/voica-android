package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationBenchmarkMetricsTest {
    @Test
    fun uniqueCoveredSamplesDeduplicatesWindowOverlap() {
        val ranges =
            listOf(
                DiarizationProfileWindowRange(0L, 60L),
                DiarizationProfileWindowRange(50L, 110L),
                DiarizationProfileWindowRange(200L, 240L),
            )

        assertEquals(150L, uniqueCoveredSamples(ranges))
        assertEquals(160L, ranges.sumOf { it.sampleCount })
        assertEquals(10L, ranges.sumOf { it.sampleCount } - uniqueCoveredSamples(ranges))
    }

    @Test
    fun realTimeFactorUsesCanonicalAudioDuration() {
        assertEquals(
            0.5,
            realTimeFactor(
                elapsedMs = 5_000.0,
                sampleCount = 160_000L,
            )!!,
            0.000_001,
        )
    }

    @Test
    fun realTimeFactorRejectsUnknownOrEmptyAudio() {
        assertNull(realTimeFactor(1_000.0, null))
        assertNull(realTimeFactor(1_000.0, 0L))
    }
}
