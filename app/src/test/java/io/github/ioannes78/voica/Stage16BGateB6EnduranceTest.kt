package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB6EnduranceTest {
    private val sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong()

    @Test
    fun `B6 accepts endurance duration window only`() {
        assertFalse(stage16BGateB6DurationEligible((STAGE16B_GATE_B_B6_MIN_RECORDING_SECONDS - 1L) * sampleRate))
        assertTrue(stage16BGateB6DurationEligible(STAGE16B_GATE_B_B6_MIN_RECORDING_SECONDS * sampleRate))
        assertTrue(stage16BGateB6DurationEligible(STAGE16B_GATE_B_B6_MAX_RECORDING_SECONDS * sampleRate))
        assertFalse(stage16BGateB6DurationEligible((STAGE16B_GATE_B_B6_MAX_RECORDING_SECONDS + 1L) * sampleRate))
    }

    @Test
    fun `B6 plan codec preserves exact canonical boundaries`() {
        val plan = Stage16BGateB6Plan(
            recordingId = "long-a",
            canonicalSha256 = "sha",
            totalSampleCount = 600L * sampleRate,
            vadModelId = Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID,
            vadModelVersion = "1.0.0",
            vadModelRevision = "1",
            vadWallMillis = 321,
            numThreads = 2,
            frozenBoundaries = listOf(
                SpeechSegment(100, 20_000),
                SpeechSegment(30_000, 70_000),
            ),
        )

        val decoded = Stage16BGateB6PlanStore.decode(Stage16BGateB6PlanStore.encode(plan))

        assertEquals(plan, decoded)
        assertEquals(59_900L, decoded.speechSampleCount)
    }

    @Test
    fun `B6 first and last quarter RTF expose slowdown`() {
        val tenSeconds = 10L * sampleRate
        val timings = listOf(
            Stage16BGateB6ChunkTiming(tenSeconds, 5_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 5_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 6_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 6_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 7_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 7_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 10_000, false),
            Stage16BGateB6ChunkTiming(tenSeconds, 10_000, false),
        )

        val (first, last) = stage16BGateB6QuarterRtfs(timings)

        assertEquals(0.5, first!!, 0.0001)
        assertEquals(1.0, last!!, 0.0001)
    }

    @Test
    fun `B6 result requires every frozen call nonblank`() {
        val plan = Stage16BGateB6Plan(
            recordingId = "long-b",
            canonicalSha256 = "sha",
            totalSampleCount = 600L * sampleRate,
            vadModelId = Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID,
            vadModelVersion = "1.0.0",
            vadModelRevision = "1",
            vadWallMillis = 100,
            numThreads = 2,
            frozenBoundaries = listOf(
                SpeechSegment(0, sampleRate),
                SpeechSegment(2 * sampleRate, 3 * sampleRate),
            ),
        )
        val result = Stage16BGateB6Result(
            processSessionId = "session",
            processId = 1,
            modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            modelVersion = "1",
            modelRevision = "1",
            plan = plan,
            engineOpenMillis = 100,
            asrWallMillis = 1_000,
            asrCallCount = 2,
            blankCallCount = 1,
            normalizedCharacterCount = 10,
            firstQuarterAsrRtf = 0.5,
            lastQuarterAsrRtf = 0.5,
            startPssKb = 100,
            peakPssKb = 200,
            endPssKb = 150,
            thermalStatusStart = 0,
            thermalStatusMax = 0,
            thermalStatusEnd = 0,
        )

        assertFalse(result.complete)
    }
}
