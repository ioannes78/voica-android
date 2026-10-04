package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSpeakerAlignmentTest {
    @Test
    fun splitsPunctuatedChineseTextAtSpeakerChangeWithoutLosingCharacters() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 32_000L,
                firstPassRawText = "你好世界",
                finalText = "你好，世界。",
                tokens =
                    listOf(
                        token("你好", 0L, 16_000L),
                        token("世界", 16_000L, 32_000L),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns =
                    listOf(
                        globalTurn(0, 0L, 16_000L),
                        globalTurn(1, 16_000L, 32_000L),
                    ),
            )

        assertEquals(2, result.spans.size)
        assertEquals(0, result.spans[0].speakerIndex)
        assertEquals(1, result.spans[1].speakerIndex)
        assertEquals("你好，", text(segment, result.spans[0]))
        assertEquals("世界。", text(segment, result.spans[1]))
        assertEquals(segment.finalText, reconstructAlignedFinalText(segment, result.spans))
    }

    @Test
    fun proportionalTextFallbackPreservesItnOutput() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                firstPassRawText = "一百元",
                finalText = "100元。",
                tokens =
                    listOf(
                        token("一百", 0L, 8_000L),
                        token("元", 8_000L, 16_000L),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns =
                    listOf(
                        globalTurn(0, 0L, 8_000L),
                        globalTurn(1, 8_000L, 16_000L),
                    ),
            )

        assertEquals("100", text(segment, result.spans[0]))
        assertEquals("元。", text(segment, result.spans[1]))
        assertEquals(segment.finalText, reconstructAlignedFinalText(segment, result.spans))
    }

    @Test
    fun punctuationOnlyTimedTokenStaysAttachedToPreviousSpeakerText() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                secondPassRawText = "活动。",
                finalText = "活动。",
                tokens =
                    listOf(
                        TranscriptToken(
                            text = "活动",
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 15_000L,
                            source = TokenSource.SECOND_PASS,
                        ),
                        TranscriptToken(
                            text = "。",
                            startSampleIndex = 15_000L,
                            endSampleIndexExclusive = 16_000L,
                            source = TokenSource.SECOND_PASS,
                        ),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns = listOf(globalTurn(0, 0L, 15_000L)),
            )

        assertEquals(1, result.spans.size)
        assertEquals(0, result.spans.single().speakerIndex)
        assertEquals("活动。", text(segment, result.spans.single()))
        assertEquals(segment.finalText, reconstructAlignedFinalText(segment, result.spans))
    }

    @Test
    fun overlappingSpeakersProduceAmbiguousTokenInsteadOfDuplicatedText() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                firstPassRawText = "同时说话",
                finalText = "同时说话。",
                tokens = listOf(token("同时说话", 8_000L, 12_000L)),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns =
                    listOf(
                        globalTurn(0, 0L, 16_000L, overlap = true),
                        globalTurn(1, 8_000L, 16_000L, overlap = true),
                    ),
            )

        val span = result.spans.single()
        assertNull(span.speakerIndex)
        assertEquals(SpeakerAssignmentQuality.OVERLAP_AMBIGUOUS, span.assignmentQuality)
        assertTrue(span.ambiguous)
        assertEquals(segment.finalText, reconstructAlignedFinalText(segment, result.spans))
    }

    @Test
    fun noTimingAssignsWholeSegmentOnlyWhenOneTurnFullyContainsIt() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 16_000L,
                endSampleIndexExclusive = 32_000L,
                firstPassRawText = "没有时间戳",
                finalText = "没有时间戳。",
                tokens =
                    listOf(
                        TranscriptToken(
                            text = "没有时间戳",
                            startSampleIndex = null,
                            endSampleIndexExclusive = null,
                            source = TokenSource.FIRST_PASS,
                        ),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns = listOf(globalTurn(3, 0L, 64_000L)),
            )

        assertEquals(3, result.spans.single().speakerIndex)
        assertEquals(SpeakerAssignmentQuality.ASSIGNED, result.spans.single().assignmentQuality)
    }

    @Test
    fun noTimingAcrossSpeakerBoundaryStaysUnresolved() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 32_000L,
                firstPassRawText = "跨说话人",
                finalText = "跨说话人。",
                tokens =
                    listOf(
                        TranscriptToken(
                            text = "跨说话人",
                            startSampleIndex = null,
                            endSampleIndexExclusive = null,
                            source = TokenSource.FIRST_PASS,
                        ),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns =
                    listOf(
                        globalTurn(0, 0L, 16_000L),
                        globalTurn(1, 16_000L, 32_000L),
                    ),
            )

        assertNull(result.spans.single().speakerIndex)
        assertEquals(
            SpeakerAssignmentQuality.UNRESOLVED,
            result.spans.single().assignmentQuality,
        )
    }

    @Test
    fun timedSecondPassIsPreferredOverFirstPass() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                firstPassRawText = "hello",
                secondPassRawText = "hello",
                finalText = "hello.",
                tokens =
                    listOf(
                        TranscriptToken(
                            text = "hello",
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 16_000L,
                            source = TokenSource.FIRST_PASS,
                        ),
                        TranscriptToken(
                            text = "hello",
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 16_000L,
                            source = TokenSource.SECOND_PASS,
                        ),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns = listOf(globalTurn(0, 0L, 16_000L)),
            )

        assertEquals(TokenSource.SECOND_PASS, result.spans.single().tokenSource)
        assertEquals(0, result.spans.single().tokenStartIndex)
        assertEquals(1, result.spans.single().tokenEndIndexExclusive)
    }

    @Test
    fun missingTokenEndUsesNextTokenStart() {
        val segment =
            TranscriptSegment(
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 32_000L,
                firstPassRawText = "AB",
                finalText = "A B.",
                tokens =
                    listOf(
                        TranscriptToken(
                            text = "A",
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = null,
                            source = TokenSource.FIRST_PASS,
                        ),
                        TranscriptToken(
                            text = "B",
                            startSampleIndex = 16_000L,
                            endSampleIndexExclusive = null,
                            source = TokenSource.FIRST_PASS,
                        ),
                    ),
            )

        val result =
            alignTranscriptSegmentsToSpeakers(
                segments = listOf(segment),
                speakerTurns =
                    listOf(
                        globalTurn(0, 0L, 16_000L),
                        globalTurn(1, 16_000L, 32_000L),
                    ),
            )

        assertEquals(2, result.spans.size)
        assertEquals("A ", text(segment, result.spans[0]))
        assertEquals("B.", text(segment, result.spans[1]))
        assertEquals(segment.finalText, reconstructAlignedFinalText(segment, result.spans))
    }

    private companion object {
        fun token(
            text: String,
            start: Long,
            end: Long,
        ) =
            TranscriptToken(
                text = text,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                source = TokenSource.FIRST_PASS,
            )

        fun globalTurn(
            speaker: Int,
            start: Long,
            end: Long,
            overlap: Boolean = false,
        ) =
            GlobalSpeakerTurn(
                globalSpeakerIndex = speaker,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                confidence = 0.8F,
                overlap = overlap,
            )

        fun text(
            segment: TranscriptSegment,
            span: SpeakerAlignedTextSpan,
        ): String =
            segment.finalText.substring(
                span.finalTextStartOffset,
                span.finalTextEndOffsetExclusive,
            )
    }
}
