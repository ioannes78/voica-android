package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage13CBoundaryProfilerTest {
    @Test
    fun measuresSpeechSilenceAndLongestGapInsideOverlap() {
        val metrics =
            analyzeStage13CBoundaries(
                windows =
                    listOf(
                        Stage13CBoundaryWindowRange(0L, 100L),
                        Stage13CBoundaryWindowRange(80L, 180L),
                    ),
                speechSegments =
                    listOf(
                        SpeechSegment(82L, 88L),
                        SpeechSegment(92L, 98L),
                    ),
            )

        val metric = metrics.single()
        assertEquals(20L, metric.overlapSamples)
        assertEquals(12L, metric.speechSamples)
        assertEquals(8L, metric.silenceSamples)
        assertEquals(2, metric.speechRunCount)
        assertEquals(3, metric.silenceGapCount)
        assertEquals(4L, metric.longestSilenceGapSamples)
        assertEquals(88L, metric.longestSilenceGapStartSampleIndex)
        assertEquals(92L, metric.longestSilenceGapEndSampleIndexExclusive)
        assertEquals(0.6, metric.speechRatio, 0.000_001)
    }

    @Test
    fun mergesOverlappingVadSegmentsBeforeCountingSpeech() {
        val metric =
            analyzeStage13CBoundaries(
                windows =
                    listOf(
                        Stage13CBoundaryWindowRange(0L, 100L),
                        Stage13CBoundaryWindowRange(90L, 190L),
                    ),
                speechSegments =
                    listOf(
                        SpeechSegment(85L, 95L),
                        SpeechSegment(93L, 100L),
                    ),
            ).single()

        assertEquals(10L, metric.overlapSamples)
        assertEquals(10L, metric.speechSamples)
        assertEquals(0L, metric.silenceSamples)
        assertEquals(1, metric.speechRunCount)
        assertEquals(0, metric.silenceGapCount)
        assertEquals(0L, metric.longestSilenceGapSamples)
        assertNull(metric.longestSilenceGapStartSampleIndex)
        assertNull(metric.longestSilenceGapEndSampleIndexExclusive)
    }

    @Test
    fun ignoresAdjacentWindowsWithoutOverlap() {
        val metrics =
            analyzeStage13CBoundaries(
                windows =
                    listOf(
                        Stage13CBoundaryWindowRange(0L, 100L),
                        Stage13CBoundaryWindowRange(100L, 200L),
                        Stage13CBoundaryWindowRange(220L, 260L),
                    ),
                speechSegments = listOf(SpeechSegment(20L, 40L)),
            )

        assertEquals(emptyList<Stage13CBoundaryMetric>(), metrics)
    }
}
