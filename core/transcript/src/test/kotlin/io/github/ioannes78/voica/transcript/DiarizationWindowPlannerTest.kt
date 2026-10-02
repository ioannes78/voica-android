package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationWindowPlannerTest {
    @Test
    fun paddingMergesNearbyVadSegmentsWithoutChangingAbsoluteTimeline() {
        val windows =
            planDiarizationWindows(
                speechSegments =
                    listOf(
                        SpeechSegment(16_000L, 32_000L),
                        SpeechSegment(40_000L, 56_000L),
                    ),
                totalSampleCount = 80_000L,
                config = DiarizationConfig(),
            )

        assertEquals(1, windows.size)
        assertEquals(8_000L, windows.single().startSampleIndex)
        assertEquals(64_000L, windows.single().endSampleIndexExclusive)
    }

    @Test
    fun longRegionUsesSixtySecondWindowsWithTenSecondOverlap() {
        val config = DiarizationConfig()
        val windows =
            planDiarizationWindows(
                speechSegments =
                    listOf(
                        SpeechSegment(
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 2L * 60L * 60L * 16_000L,
                        ),
                    ),
                totalSampleCount = 2L * 60L * 60L * 16_000L,
                config = config,
            )

        assertTrue(windows.size > 100)
        assertTrue(windows.all { it.sampleCount <= config.chunkSizeSamples })
        windows.zipWithNext().forEach { (left, right) ->
            assertEquals(
                config.chunkOverlapSamples,
                left.endSampleIndexExclusive - right.startSampleIndex,
            )
        }
    }

    @Test
    fun virtualThirtySixtyAndOneTwentyMinuteInputsStayBounded() {
        val config = DiarizationConfig()
        listOf(30L, 60L, 120L).forEach { minutes ->
            val total = minutes * 60L * 16_000L
            val windows =
                planDiarizationWindows(
                    speechSegments = listOf(SpeechSegment(0L, total)),
                    totalSampleCount = total,
                    config = config,
                )

            assertTrue(windows.isNotEmpty())
            assertTrue(windows.all { it.sampleCount <= config.chunkSizeSamples })
            assertTrue(windows.maxOf { it.sampleCount } <= 60L * 16_000L)
        }
    }

    @Test
    fun sparseSpeechDoesNotCreateWindowAcrossLongSilence() {
        val windows =
            planDiarizationWindows(
                speechSegments =
                    listOf(
                        SpeechSegment(16_000L, 32_000L),
                        SpeechSegment(10L * 60L * 16_000L, 10L * 60L * 16_000L + 16_000L),
                    ),
                totalSampleCount = 11L * 60L * 16_000L,
                config = DiarizationConfig(),
            )

        assertEquals(2, windows.size)
        assertTrue(windows[0].endSampleIndexExclusive < windows[1].startSampleIndex)
    }

    @Test
    fun plannerPreservesNonZeroAbsoluteStart() {
        val config =
            DiarizationConfig(
                vadContextPaddingSamples = 0L,
            )
        val start = 30L * 60L * 16_000L
        val windows =
            planDiarizationWindows(
                speechSegments =
                    listOf(
                        SpeechSegment(
                            startSampleIndex = start,
                            endSampleIndexExclusive = start + 70L * 16_000L,
                        ),
                    ),
                totalSampleCount = start + 70L * 16_000L,
                config = config,
            )

        assertEquals(start, windows.first().startSampleIndex)
        assertEquals(start + 60L * 16_000L, windows.first().endSampleIndexExclusive)
        assertEquals(start + 50L * 16_000L, windows[1].startSampleIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsVadSegmentPastCanonicalEnd() {
        planDiarizationWindows(
            speechSegments = listOf(SpeechSegment(0L, 16_001L)),
            totalSampleCount = 16_000L,
            config = DiarizationConfig(),
        )
    }
}
