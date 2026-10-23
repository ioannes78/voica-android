package io.github.ioannes78.voica.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalFileAsrBenchmarkTest {
    @Test
    fun `CER normalizes punctuation whitespace width and case`() {
        val metrics =
            AsrBenchmarkScoring.characterErrorRate(
                reference = "Ｖｏｉｃａ， QS668！",
                hypothesis = "voica qs668",
            )

        assertEquals(0, metrics.errors)
        assertEquals(10, metrics.referenceUnits)
        assertEquals(0.0, metrics.errorRate)
    }

    @Test
    fun `CER reports Chinese substitution`() {
        val metrics =
            AsrBenchmarkScoring.characterErrorRate(
                reference = "福州数字产业",
                hypothesis = "福州数智产业",
            )

        assertEquals(1, metrics.substitutions)
        assertEquals(0, metrics.insertions)
        assertEquals(0, metrics.deletions)
        assertEquals(6, metrics.referenceUnits)
        assertEquals(1.0 / 6.0, metrics.errorRate)
    }

    @Test
    fun `edit distance exposes deterministic insertion and substitution breakdown`() {
        val metrics =
            AsrBenchmarkScoring.wordErrorRate(
                referenceWords = listOf("a", "b", "c"),
                hypothesisWords = listOf("a", "x", "c", "d"),
            )

        assertEquals(1, metrics.substitutions)
        assertEquals(1, metrics.insertions)
        assertEquals(0, metrics.deletions)
        assertEquals(2, metrics.errors)
        assertEquals(3, metrics.referenceUnits)
        assertEquals(4, metrics.hypothesisUnits)
        assertEquals(2.0 / 3.0, metrics.errorRate)
    }

    @Test
    fun `WER does not invent Chinese segmentation`() {
        val metrics =
            AsrBenchmarkScoring.wordErrorRate(
                referenceWords = listOf("今天", "讨论", "项目"),
                hypothesisWords = listOf("今天", "讨论", "项目"),
            )

        assertEquals(0, metrics.errors)
        assertEquals(3, metrics.referenceUnits)
    }

    @Test
    fun `error rate is undefined for empty normalized reference`() {
        val metrics =
            AsrBenchmarkScoring.characterErrorRate(
                reference = "，。！",
                hypothesis = "Voica",
            )

        assertEquals(5, metrics.insertions)
        assertEquals(0, metrics.referenceUnits)
        assertNull(metrics.errorRate)
    }

    @Test
    fun `benchmark observation calculates wall time RTF from canonical samples`() {
        val observation =
            LocalFileAsrBenchmarkObservation(
                recordingId = "recording-1",
                sampleRateHz = 16_000,
                totalSampleCount = 160_000,
                asr = BenchmarkComponentIdentity(id = "sensevoice-2024-int8"),
                wallTimeMillis = 5_000,
            )

        assertEquals(10.0, observation.audioDurationSeconds)
        assertEquals(0.5, observation.realTimeFactor)
    }

    @Test
    fun `zero duration benchmark does not fabricate RTF`() {
        val observation =
            LocalFileAsrBenchmarkObservation(
                recordingId = "recording-empty",
                sampleRateHz = 16_000,
                totalSampleCount = 0,
                asr = BenchmarkComponentIdentity(id = "candidate"),
                wallTimeMillis = 1,
            )

        assertNull(observation.realTimeFactor)
    }
}
