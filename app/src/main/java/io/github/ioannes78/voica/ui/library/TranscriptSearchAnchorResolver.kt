package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.ui.transcript.TranscriptDisplaySegment
import io.github.ioannes78.voica.ui.transcript.TranscriptReadingParagraph

/**
 * Resolves a persisted full-text-search anchor onto the currently rendered transcript timeline.
 *
 * Search indexing deliberately stores the durable source transcript segment id. The timeline may
 * render that segment either as one `segment:<id>` row or as multiple diarization `span:<id>` rows.
 * Therefore model-original search navigation must match [TranscriptDisplaySegment.sourceSegmentId]
 * rather than the presentation row's [TranscriptDisplaySegment.stableId].
 */
internal fun resolveTranscriptSearchRowIndex(
    target: SearchDocumentEntity?,
    displayedTranscriptionId: String?,
    currentRevisionId: String?,
    paragraphs: List<TranscriptReadingParagraph>,
    segments: List<TranscriptDisplaySegment>,
): Int {
    val resolvedTarget = target ?: return -1
    if (resolvedTarget.transcriptionId != displayedTranscriptionId) return -1
    val anchorId = resolvedTarget.sourceAnchorId ?: return -1

    return if (currentRevisionId != null) {
        if (resolvedTarget.revisionId != currentRevisionId) {
            -1
        } else {
            paragraphs.indexOfFirst { paragraph -> paragraph.stableId == anchorId }
        }
    } else {
        if (resolvedTarget.revisionId != null) {
            -1
        } else {
            segments.indexOfFirst { segment -> segment.sourceSegmentId == anchorId }
        }
    }
}

internal fun isTranscriptSearchSegmentHighlighted(
    target: SearchDocumentEntity?,
    highlightedSearchDocumentId: String?,
    segment: TranscriptDisplaySegment,
): Boolean =
    target != null &&
        highlightedSearchDocumentId == target.documentId &&
        target.revisionId == null &&
        target.sourceAnchorId == segment.sourceSegmentId
