package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB5BenchmarkTest {
    @Test
    fun `B5 freezes long FSMN segments into common 25 second inputs`() {
        val sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong()
        val frozen =
            stage16BGateB5FrozenBoundaries(
                speechSegments = listOf(SpeechSegment(0, 60 * sampleRate)),
            )

        assertEquals(3, frozen.size)
        assertEquals(25 * sampleRate, frozen[0].sampleCount)
        assertEquals(25 * sampleRate, frozen[1].sampleCount)
        assertEquals(10 * sampleRate, frozen[2].sampleCount)
        assertEquals(60 * sampleRate, frozen.sumOf(SpeechSegment::sampleCount))
    }

    @Test
    fun `B5 plan codec preserves exact canonical boundaries`() {
        val plan =
            Stage16BGateB5Plan(
                recordingId = "recording-a",
                canonicalSha256 = "abc123",
                totalSampleCount = 100_000,
                vadModelId = Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID,
                vadModelVersion = "1.0.0",
                vadModelRevision = "1",
                vadWallMillis = 123,
                numThreads = 4,
                frozenBoundaries =
                    listOf(
                        SpeechSegment(100, 20_000),
                        SpeechSegment(30_000, 50_000),
                    ),
            )

        val decoded = Stage16BGateB5PlanStore.decode(Stage16BGateB5PlanStore.encode(plan))

        assertEquals(plan, decoded)
        assertEquals(39_900L, decoded.speechSampleCount)
    }

    @Test
    fun `B5 pass computes RTF from frozen speech not recording duration`() {
        val observation =
            Stage16BGateB5PassObservation(
                label = "COLD",
                engineOpenMillis = 1_000,
                asrWallMillis = 5_000,
                asrCallCount = 2,
                blankCallCount = 0,
                speechSampleCount = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * 10,
                startPssKb = 100,
                peakPssKb = 300,
                endPssKb = 150,
            )

        assertEquals(0.5, observation.asrRtf, 0.0001)
        assertEquals(0.6, observation.totalRtf, 0.0001)
        assertTrue(observation.complete)
    }

    @Test
    fun `B5 pass rejects blank ASR calls`() {
        val observation =
            Stage16BGateB5PassObservation(
                label = "WARM",
                engineOpenMillis = 1,
                asrWallMillis = 1,
                asrCallCount = 3,
                blankCallCount = 1,
                speechSampleCount = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong(),
                startPssKb = 100,
                peakPssKb = 100,
                endPssKb = 100,
            )

        assertFalse(observation.complete)
    }
}
