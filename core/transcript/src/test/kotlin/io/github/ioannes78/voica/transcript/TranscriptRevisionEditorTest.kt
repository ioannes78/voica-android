package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRevisionEditorTest {
    @Test
    fun mergeWrongAsrBoundaryKeepsCombinedSourceAnchorsWithoutInventingTokenTime() {
        val source =
            listOf(
                paragraph(
                    text = "今天下午我们重点讨论一下这个",
                    refs = listOf("SEGMENT:12"),
                    start = 0,
                    end = 16_000,
                    speaker = "speaker-1",
                ),
                paragraph(
                    text = "项目的技术方案以及后续实施安排。",
                    refs = listOf("SEGMENT:13"),
                    start = 16_000,
                    end = 32_000,
                    speaker = "speaker-1",
                ),
            )

        val result = TranscriptRevisionEditor.mergeWithNext(source, 0)

        assertEquals(1, result.size)
        assertEquals("今天下午我们重点讨论一下这个项目的技术方案以及后续实施安排。", result.single().text)
        assertEquals(listOf("SEGMENT:12", "SEGMENT:13"), result.single().sourceAnchorRefs)
        assertEquals(0L, result.single().anchorStartSampleIndex)
        assertEquals(32_000L, result.single().anchorEndSampleIndexExclusive)
        assertEquals("speaker-1", result.single().speakerId)
        assertEquals(RevisionTimingQuality.ANCHORED, result.single().timingQuality)
        assertTrue(result.single().isUserModified)
    }

    @Test
    fun mergeAcrossDifferentSpeakersDoesNotInventSpeakerIdentity() {
        val source =
            listOf(
                paragraph("hello", listOf("SEGMENT:a"), 0, 100, "speaker-a"),
                paragraph("world", listOf("SEGMENT:b"), 100, 200, "speaker-b"),
            )

        val result = TranscriptRevisionEditor.mergeWithNext(source, 0)

        assertEquals("hello world", result.single().text)
        assertNull(result.single().speakerId)
        assertEquals(RevisionTimingQuality.ANCHORED, result.single().timingQuality)
    }

    @Test
    fun splitWithoutTokenBoundaryKeepsOriginalAnchorAndMarksApproximate() {
        val source =
            listOf(
                paragraph("今天讨论产品方案，下午再讨论实施计划。", listOf("SEGMENT:1"), 0, 32_000, "speaker-1"),
            )

        val result = TranscriptRevisionEditor.splitAt(source, 0, 9)

        assertEquals(2, result.size)
        assertEquals(0L, result[0].anchorStartSampleIndex)
        assertEquals(32_000L, result[0].anchorEndSampleIndexExclusive)
        assertEquals(0L, result[1].anchorStartSampleIndex)
        assertEquals(32_000L, result[1].anchorEndSampleIndexExclusive)
        assertEquals(RevisionTimingQuality.APPROXIMATE, result[0].timingQuality)
        assertEquals(RevisionTimingQuality.APPROXIMATE, result[1].timingQuality)
    }

    @Test
    fun splitWithVerifiedTokenBoundaryCanRemainExact() {
        val source =
            listOf(
                paragraph("hello world", listOf("SEGMENT:1"), 0, 20_000, null),
            )

        val result = TranscriptRevisionEditor.splitAt(
            paragraphs = source,
            index = 0,
            textOffset = 5,
            exactSplitSampleIndex = 10_000,
        )

        assertEquals(10_000L, result[0].anchorEndSampleIndexExclusive)
        assertEquals(10_000L, result[1].anchorStartSampleIndex)
        assertEquals(RevisionTimingQuality.EXACT, result[0].timingQuality)
        assertEquals(RevisionTimingQuality.EXACT, result[1].timingQuality)
    }

    @Test
    fun textEditDowngradesExactParagraphToAnchored() {
        val source =
            listOf(
                paragraph("供应连", listOf("SEGMENT:1"), 0, 10_000, "speaker-1"),
            )

        val result = TranscriptRevisionEditor.editText(source, 0, "供应链")

        assertEquals("供应链", result.single().text)
        assertEquals(RevisionTimingQuality.ANCHORED, result.single().timingQuality)
        assertTrue(result.single().isUserModified)
    }

    @Test
    fun arrangeForReadingMergesBrokenSentenceButNeverCrossesSpeakerBoundary() {
        val source =
            listOf(
                paragraph(
                    "今天先讨论这个",
                    listOf("SEGMENT:1"),
                    0,
                    10_000,
                    "speaker-1",
                ),
                paragraph(
                    "项目的技术方案。",
                    listOf("SEGMENT:2"),
                    10_000,
                    20_000,
                    "speaker-1",
                ),
                paragraph(
                    "下一项由我来说明",
                    listOf("SEGMENT:3"),
                    20_000,
                    30_000,
                    "speaker-2",
                ),
                paragraph(
                    "补充内容。",
                    listOf("SEGMENT:4"),
                    30_000,
                    40_000,
                    null,
                ),
            )

        val result = TranscriptRevisionEditor.arrangeForReading(source)

        assertEquals(3, result.size)
        assertEquals("今天先讨论这个项目的技术方案。", result[0].text)
        assertEquals("speaker-1", result[0].speakerId)
        assertEquals("下一项由我来说明", result[1].text)
        assertEquals("speaker-2", result[1].speakerId)
        assertEquals("补充内容。", result[2].text)
        assertNull(result[2].speakerId)
    }

    private fun paragraph(
        text: String,
        refs: List<String>,
        start: Long,
        end: Long,
        speaker: String?,
    ) =
        RevisionParagraphDraft(
            text = text,
            sourceAnchorRefs = refs,
            anchorStartSampleIndex = start,
            anchorEndSampleIndexExclusive = end,
            speakerId = speaker,
            timingQuality = RevisionTimingQuality.EXACT,
            isUserModified = false,
        )
}
