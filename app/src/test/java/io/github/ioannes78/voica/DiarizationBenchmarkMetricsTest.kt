package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationBenchmarkMetricsTest {
    @Test
    fun extraDetectedSpeakersAreCountedAsFragmentation() {
        val result = evaluateDiarizationSpeakerCount(1, 3)

        assertEquals(2, result.absoluteError)
        assertEquals(2, result.extraSpeakers)
        assertEquals(0, result.missingSpeakers)
    }

    @Test
    fun missingDetectedSpeakersAreCountedAsMergeLoss() {
        val result = evaluateDiarizationSpeakerCount(3, 1)

        assertEquals(2, result.absoluteError)
        assertEquals(0, result.extraSpeakers)
        assertEquals(2, result.missingSpeakers)
    }

    @Test
    fun noReferenceLeavesSemiAutomaticMetricsUnset() {
        val result = evaluateDiarizationSpeakerCount(null, 4)

        assertNull(result.absoluteError)
        assertNull(result.extraSpeakers)
        assertNull(result.missingSpeakers)
    }
}
