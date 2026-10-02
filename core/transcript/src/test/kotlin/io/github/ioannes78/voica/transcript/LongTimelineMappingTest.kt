package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTimelineMappingTest {
    @Test
    fun virtualThirtySixtyAndOneTwentyMinuteTimelinesMapBoundaries() {
        listOf(30, 60, 120).forEach { minutes ->
            val timeline = virtualTimeline(minutes)
            val mapper = TranscriptTimelinePositionMapper(timeline)
            val totalSamples = minutes.toLong() * 60L * SAMPLE_RATE_HZ

            assertEquals(minutes * 60, timeline.rows.size)
            assertEquals("segment:s0", mapper.map(0L).activeRowId)
            assertNotNull(mapper.map(totalSamples / 2L).activeRowId)
            assertNotNull(mapper.map(totalSamples - 1L).activeRowId)
            assertTrue(mapper.map(totalSamples).inTranscriptGap)
        }
    }

    @Test
    fun virtualOneTwentyMinuteTimelineHandlesTwentyHertzPlaybackSequence() {
        val timeline = virtualTimeline(120)
        val mapper = TranscriptTimelinePositionMapper(timeline)
        val totalSamples = 120L * 60L * SAMPLE_RATE_HZ
        val tickSamples = SAMPLE_RATE_HZ / 20L

        var previousRowId: String? = null
        var rowTransitions = 0
        var ticks = 0
        var sample = 0L
        while (sample < totalSamples) {
            val position = mapper.map(sample)
            assertTrue(!position.inTranscriptGap)
            assertNotNull(position.activeRowId)
            assertNotNull(position.activeCueId)
            if (position.activeRowId != previousRowId) {
                rowTransitions += 1
                previousRowId = position.activeRowId
            }
            ticks += 1
            sample += tickSamples
        }

        assertEquals(144_000, ticks)
        assertEquals(7_200, rowTransitions)
        assertEquals(7_200, timeline.rows.size)
        assertEquals(7_200, timeline.rows.sumOf { it.cues.size })
    }

    @Test
    fun virtualLongTimelineSupportsRandomSeekWithoutLinearCursorState() {
        val timeline = virtualTimeline(120)
        val mapper = TranscriptTimelinePositionMapper(timeline)
        val probes =
            listOf(
                115_199_999L,
                0L,
                57_600_000L,
                16_000L,
                96_000_000L,
                1_600_000L,
                114_000_000L,
                32_000L,
            )

        probes.forEach { sample ->
            val position = mapper.map(sample)
            assertNotNull(position.activeRowId)
            assertNotNull(position.activeCueId)
        }
    }

    private fun virtualTimeline(minutes: Int): TranscriptTimeline {
        val rowCount = minutes * 60
        val segments =
            List(rowCount) { index ->
                val start = index.toLong() * SAMPLE_RATE_HZ
                val end = start + SAMPLE_RATE_HZ
                val tokenText = "词" + index
                TimelineTranscriptSegmentInput(
                    id = "s" + index,
                    segment =
                        TranscriptSegment(
                            segmentIndex = index,
                            startSampleIndex = start,
                            endSampleIndexExclusive = end,
                            firstPassRawText = tokenText,
                            finalText = tokenText + "。",
                            tokens =
                                listOf(
                                    TranscriptToken(
                                        text = tokenText,
                                        startSampleIndex = start,
                                        endSampleIndexExclusive = end,
                                        source = TokenSource.FIRST_PASS,
                                    ),
                                ),
                        ),
                )
            }

        return buildTranscriptTimeline(
            recordingId = "virtual-recording",
            transcriptionId = "virtual-transcription-" + minutes,
            alignmentId = null,
            sourceCanonicalAssetId = "virtual-asset",
            sourceCanonicalSha256 = "virtual-sha",
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = rowCount.toLong() * SAMPLE_RATE_HZ,
            segments = segments,
        )
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000L
    }
}
