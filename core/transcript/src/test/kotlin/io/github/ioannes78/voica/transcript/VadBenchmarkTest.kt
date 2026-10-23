package io.github.ioannes78.voica.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VadBenchmarkTest {
    @Test
    fun summarizesSegmentDurationsAndForcedSplitProxy() {
        val observation =
            VadBenchmarkObservation(
                profile =
                    VadBenchmarkProfile(
                        id = "silero-30s",
                        displayName = "Silero · 30s",
                        modelId = "silero-vad-int8",
                        maxSpeechDurationSeconds = 30.0F,
                        primaryGateAProfile = true,
                    ),
                modelVersion = "baseline",
                modelRevision = 1,
                sampleRateHz = 16_000,
                totalSampleCount = 640_000,
                wallTimeMillis = 400,
                peakPssKb = 123_000,
                segments =
                    listOf(
                        SpeechSegment(0L, 4_800L),
                        SpeechSegment(16_000L, 496_000L),
                    ),
            )

        assertEquals(2, observation.segmentCount)
        assertEquals(484_800L, observation.speechSampleCount)
        assertEquals(30_300L, observation.speechDurationMillis)
        assertEquals(1, observation.shortFragmentCount)
        assertEquals(1, observation.maxDurationSplitProxyCount)
        assertEquals(0.01, observation.realTimeFactor)
    }

    @Test
    fun zeroDurationDoesNotFabricateRates() {
        val observation =
            VadBenchmarkObservation(
                profile =
                    VadBenchmarkProfile(
                        id = "empty",
                        displayName = "Empty",
                        modelId = "vad",
                        maxSpeechDurationSeconds = 30.0F,
                        primaryGateAProfile = true,
                    ),
                modelVersion = "v1",
                modelRevision = 1,
                sampleRateHz = 16_000,
                totalSampleCount = 0,
                wallTimeMillis = 1,
                peakPssKb = null,
                segments = emptyList(),
            )

        assertNull(observation.realTimeFactor)
        assertNull(observation.speechRatio)
        assertNull(observation.averageSegmentDurationMillis)
        assertNull(observation.medianSegmentDurationMillis)
    }

    @Test
    fun referenceScoringMeasuresSpeechOverlap() {
        val reference =
            listOf(
                SpeechSegment(1_000L, 3_000L),
                SpeechSegment(5_000L, 7_000L),
            )
        val hypothesis =
            listOf(
                SpeechSegment(500L, 2_000L),
                SpeechSegment(5_500L, 7_500L),
            )

        val metrics = VadBenchmarkScoring.againstReference(hypothesis, reference)

        assertEquals(4_000L, metrics.referenceSpeechSamples)
        assertEquals(2_500L, metrics.truePositiveSpeechSamples)
        assertEquals(1_000L, metrics.falsePositiveSpeechSamples)
        assertEquals(1_500L, metrics.falseNegativeSpeechSamples)
        assertEquals(0.625, metrics.speechRecall)
        assertEquals(2_500.0 / 3_500.0, metrics.speechPrecision)
    }
}
