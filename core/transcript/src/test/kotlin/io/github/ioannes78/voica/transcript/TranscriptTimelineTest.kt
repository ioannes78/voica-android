package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTimelineTest {
    @Test
    fun mapsExactBoundariesAndLeavesSilenceGapUnassigned() {
        val timeline =
            buildTranscriptTimeline(
                recordingId = "r1",
                transcriptionId = "t1",
                alignmentId = null,
                sourceCanonicalAssetId = "a1",
                sourceCanonicalSha256 = "sha",
                canonicalProfileId = "canonical",
                totalSampleCount = 40_000L,
                segments =
                    listOf(
                        input(
                            id = "s1",
                            index = 0,
                            start = 0L,
                            end = 16_000L,
                            text = "你好。",
                            tokens = listOf(token("你好", 0L, null)),
                        ),
                        input(
                            id = "s2",
                            index = 1,
                            start = 24_000L,
                            end = 40_000L,
                            text = "再见。",
                            tokens = listOf(token("再见", 24_000L, null)),
                        ),
                    ),
            )
        val mapper = TranscriptTimelinePositionMapper(timeline)

        assertEquals("segment:s1", mapper.map(0L).activeRowId)
        assertEquals("segment:s1:FIRST_PASS:0", mapper.map(15_999L).activeCueId)
        assertTrue(mapper.map(16_000L).inTranscriptGap)
        assertNull(mapper.map(16_000L).activeRowId)
        assertEquals("segment:s2", mapper.map(24_000L).activeRowId)
        assertTrue(mapper.map(40_000L).inTranscriptGap)
    }

    @Test
    fun speakerSpanUsesStableIdentityAndExactTokenCue() {
        val segment =
            input(
                id = "segment-id",
                index = 0,
                start = 0L,
                end = 32_000L,
                text = "你好，世界。",
                tokens =
                    listOf(
                        token("你好", 0L, 16_000L),
                        token("世界", 16_000L, 32_000L),
                    ),
            )
        val timeline =
            buildTranscriptTimeline(
                recordingId = "r1",
                transcriptionId = "t1",
                alignmentId = "al1",
                sourceCanonicalAssetId = "a1",
                sourceCanonicalSha256 = "sha",
                canonicalProfileId = "canonical",
                totalSampleCount = 32_000L,
                segments = listOf(segment),
                speakerSpans =
                    listOf(
                        TimelineSpeakerSpanInput(
                            id = "span-id",
                            spanIndex = 0,
                            sourceSegmentId = "segment-id",
                            speakerId = "speaker-1",
                            speakerOrdinal = 1,
                            speakerDisplayName = "张三",
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 32_000L,
                            tokenSource = TokenSource.FIRST_PASS,
                            tokenStartIndex = 0,
                            tokenEndIndexExclusive = 2,
                            finalTextStartOffset = 0,
                            finalTextEndOffsetExclusive = 6,
                            assignmentQuality = SpeakerAssignmentQuality.ASSIGNED,
                            overlap = false,
                            ambiguous = false,
                        ),
                    ),
            )

        val row = timeline.rows.single()
        assertEquals("span:span-id", row.id)
        assertEquals("张三", row.speakerDisplayName)
        assertEquals(2, row.cues.size)
        assertEquals(0, row.cues[0].textStartOffset)
        assertEquals(3, row.cues[0].textEndOffsetExclusive)
        assertEquals(TextProjectionQuality.EXACT, row.cues[0].projectionQuality)
    }

    @Test
    fun punctuationOnlyTimedTokenDoesNotCreateIndependentCue() {
        val timeline =
            buildTranscriptTimeline(
                recordingId = "r1",
                transcriptionId = "t1",
                alignmentId = null,
                sourceCanonicalAssetId = "a1",
                sourceCanonicalSha256 = "sha",
                canonicalProfileId = "canonical",
                totalSampleCount = 16_000L,
                segments =
                    listOf(
                        input(
                            id = "s1",
                            index = 0,
                            start = 0L,
                            end = 16_000L,
                            text = "活动。",
                            tokens =
                                listOf(
                                    token("活动", 0L, 15_000L),
                                    token("。", 15_000L, 16_000L),
                                ),
                        ),
                    ),
            )

        val cue = timeline.rows.single().cues.single()
        assertEquals(0, cue.textStartOffset)
        assertEquals("活动。".length, cue.textEndOffsetExclusive)
        assertEquals(TextProjectionQuality.EXACT, cue.projectionQuality)
    }

    @Test
    fun heuristicProjectionNeverClaimsCurrentToken() {
        val timeline =
            buildTranscriptTimeline(
                recordingId = "r1",
                transcriptionId = "t1",
                alignmentId = null,
                sourceCanonicalAssetId = "a1",
                sourceCanonicalSha256 = "sha",
                canonicalProfileId = "canonical",
                totalSampleCount = 16_000L,
                segments =
                    listOf(
                        input(
                            id = "s1",
                            index = 0,
                            start = 0L,
                            end = 16_000L,
                            text = "100元。",
                            tokens =
                                listOf(
                                    token("一百", 0L, 8_000L),
                                    token("元", 8_000L, 16_000L),
                                ),
                        ),
                    ),
            )

        assertEquals(
            TextProjectionQuality.HEURISTIC,
            timeline.rows.single().cues.first().projectionQuality,
        )
        val position = TranscriptTimelinePositionMapper(timeline).map(4_000L)
        assertEquals("segment:s1", position.activeRowId)
        assertNull(position.activeCueId)
        assertFalse(position.inTranscriptGap)
    }

    @Test
    fun sameStartTokenWithZeroEffectiveDurationIsNotPublishedAsCue() {
        val timeline =
            buildTranscriptTimeline(
                recordingId = "r1",
                transcriptionId = "t1",
                alignmentId = null,
                sourceCanonicalAssetId = "a1",
                sourceCanonicalSha256 = "sha",
                canonicalProfileId = "canonical",
                totalSampleCount = 16_000L,
                segments =
                    listOf(
                        input(
                            id = "s1",
                            index = 0,
                            start = 0L,
                            end = 16_000L,
                            text = "甲乙。",
                            tokens =
                                listOf(
                                    token("甲", 0L, null),
                                    token("乙", 0L, null),
                                ),
                        ),
                    ),
            )

        val cues = timeline.rows.single().cues
        assertEquals(1, cues.size)
        assertEquals(1, cues.single().tokenIndex)
    }

    private fun input(
        id: String,
        index: Int,
        start: Long,
        end: Long,
        text: String,
        tokens: List<TranscriptToken>,
    ) =
        TimelineTranscriptSegmentInput(
            id = id,
            segment =
                TranscriptSegment(
                    segmentIndex = index,
                    startSampleIndex = start,
                    endSampleIndexExclusive = end,
                    firstPassRawText = text.replace("。", ""),
                    finalText = text,
                    tokens = tokens,
                ),
        )

    private fun token(
        text: String,
        start: Long?,
        end: Long?,
    ) =
        TranscriptToken(
            text = text,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            source = TokenSource.FIRST_PASS,
        )
}
