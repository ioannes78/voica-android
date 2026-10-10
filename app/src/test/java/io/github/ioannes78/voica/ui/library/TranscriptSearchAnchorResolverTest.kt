package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.database.SearchDocumentTypeValue
import io.github.ioannes78.voica.transcript.RevisionTimingQuality
import io.github.ioannes78.voica.ui.transcript.TranscriptDisplaySegment
import io.github.ioannes78.voica.ui.transcript.TranscriptReadingParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSearchAnchorResolverTest {
    @Test
    fun `model original uses source segment id instead of presentation row id`() {
        val target = searchTarget(anchorId = "source-segment-2")
        val segments =
            listOf(
                segment(stableId = "segment:source-segment-1", sourceSegmentId = "source-segment-1"),
                segment(stableId = "segment:source-segment-2", sourceSegmentId = "source-segment-2"),
            )

        assertEquals(
            1,
            resolveTranscriptSearchRowIndex(
                target = target,
                displayedTranscriptionId = "transcription-1",
                currentRevisionId = null,
                paragraphs = emptyList(),
                segments = segments,
            ),
        )
    }

    @Test
    fun `diarization split scrolls to first span and highlights every span from source segment`() {
        val target = searchTarget(anchorId = "source-segment-2")
        val segments =
            listOf(
                segment(stableId = "span:a", sourceSegmentId = "source-segment-1"),
                segment(stableId = "span:b", sourceSegmentId = "source-segment-2"),
                segment(stableId = "span:c", sourceSegmentId = "source-segment-2"),
                segment(stableId = "span:d", sourceSegmentId = "source-segment-3"),
            )

        assertEquals(
            1,
            resolveTranscriptSearchRowIndex(
                target = target,
                displayedTranscriptionId = "transcription-1",
                currentRevisionId = null,
                paragraphs = emptyList(),
                segments = segments,
            ),
        )
        assertTrue(isTranscriptSearchSegmentHighlighted(target, target.documentId, segments[1]))
        assertTrue(isTranscriptSearchSegmentHighlighted(target, target.documentId, segments[2]))
        assertFalse(isTranscriptSearchSegmentHighlighted(target, target.documentId, segments[3]))
    }

    @Test
    fun `revision anchor resolves only inside the matching current revision`() {
        val target = searchTarget(anchorId = "paragraph-2", revisionId = "revision-2")
        val paragraphs = listOf(paragraph("paragraph-1"), paragraph("paragraph-2"))

        assertEquals(
            1,
            resolveTranscriptSearchRowIndex(
                target = target,
                displayedTranscriptionId = "transcription-1",
                currentRevisionId = "revision-2",
                paragraphs = paragraphs,
                segments = emptyList(),
            ),
        )
        assertEquals(
            -1,
            resolveTranscriptSearchRowIndex(
                target = target,
                displayedTranscriptionId = "transcription-1",
                currentRevisionId = "revision-other",
                paragraphs = paragraphs,
                segments = emptyList(),
            ),
        )
    }

    @Test
    fun `target from another transcription never resolves`() {
        val target = searchTarget(anchorId = "source-segment-1").copy(transcriptionId = "other")

        assertEquals(
            -1,
            resolveTranscriptSearchRowIndex(
                target = target,
                displayedTranscriptionId = "transcription-1",
                currentRevisionId = null,
                paragraphs = emptyList(),
                segments = listOf(segment("segment:source-segment-1", "source-segment-1")),
            ),
        )
    }

    private fun searchTarget(
        anchorId: String,
        revisionId: String? = null,
    ) =
        SearchDocumentEntity(
            rowId = 1L,
            documentId = "search-document",
            documentType = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
            recordingId = "recording-1",
            transcriptionId = "transcription-1",
            revisionId = revisionId,
            sourceAnchorId = anchorId,
            aiSummaryId = null,
            sectionId = null,
            itemId = null,
            folderId = null,
            tagId = null,
            displayTitle = "转写",
            displayText = "命中文本",
            indexTitle = "转写",
            indexBody = "命中文本",
            updatedAtMs = 1L,
        )

    private fun segment(
        stableId: String,
        sourceSegmentId: String,
    ) =
        TranscriptDisplaySegment(
            stableId = stableId,
            sourceSegmentId = sourceSegmentId,
            displayIndex = 0,
            segmentIndex = 0,
            startSampleIndex = 0L,
            endSampleIndexExclusive = 16_000L,
            text = "文本",
        )

    private fun paragraph(stableId: String) =
        TranscriptReadingParagraph(
            stableId = stableId,
            text = "文本",
            sourceAnchorRefs = emptyList(),
            anchorStartSampleIndex = 0L,
            anchorEndSampleIndexExclusive = 16_000L,
            speakerId = null,
            speakerDisplayName = null,
            timingQuality = RevisionTimingQuality.EXACT,
            isUserModified = false,
        )
}
