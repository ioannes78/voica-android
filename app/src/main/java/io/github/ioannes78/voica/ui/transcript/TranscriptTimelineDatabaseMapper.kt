package io.github.ioannes78.voica.ui.transcript

import io.github.ioannes78.voica.database.DiarizationSpeakerEntity
import io.github.ioannes78.voica.database.SpeakerAssignmentQualityValue
import io.github.ioannes78.voica.database.TranscriptSegmentEntity
import io.github.ioannes78.voica.database.TranscriptSpeakerSpanEntity
import io.github.ioannes78.voica.database.TranscriptTokenEntity
import io.github.ioannes78.voica.database.TranscriptTokenSourceValue
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.transcript.SpeakerAssignmentQuality
import io.github.ioannes78.voica.transcript.TimelineSpeakerSpanInput
import io.github.ioannes78.voica.transcript.TimelineTranscriptSegmentInput
import io.github.ioannes78.voica.transcript.TokenSource
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptTimeline
import io.github.ioannes78.voica.transcript.TranscriptToken
import io.github.ioannes78.voica.transcript.buildTranscriptTimeline

internal fun buildTranscriptTimelineFromDatabase(
    transcription: TranscriptionEntity,
    segments: List<TranscriptSegmentEntity>,
    tokens: List<TranscriptTokenEntity>,
    alignmentId: String? = null,
    spans: List<TranscriptSpeakerSpanEntity> = emptyList(),
    speakers: List<DiarizationSpeakerEntity> = emptyList(),
): TranscriptTimeline {
    val tokensBySegment = tokens.groupBy { it.transcriptSegmentId }
    val segmentInputs =
        segments.map { segment ->
            TimelineTranscriptSegmentInput(
                id = segment.id,
                segment =
                    TranscriptSegment(
                        segmentIndex = segment.segmentIndex,
                        startSampleIndex = segment.startSampleIndex,
                        endSampleIndexExclusive = segment.endSampleIndexExclusive,
                        firstPassRawText = segment.firstPassRawText,
                        secondPassRawText = segment.secondPassRawText,
                        finalText = segment.finalText,
                        detectedLanguage = segment.detectedLanguage,
                        confidence = segment.confidence,
                        tokens =
                            tokensBySegment[segment.id]
                                .orEmpty()
                                .map { token ->
                                    TranscriptToken(
                                        text = token.text,
                                        startSampleIndex = token.startSampleIndex,
                                        endSampleIndexExclusive = token.endSampleIndexExclusive,
                                        source = token.source.toTokenSource(),
                                    )
                                },
                    ),
            )
        }

    val speakersById = speakers.associateBy { it.id }
    val spanInputs =
        if (alignmentId == null) {
            emptyList()
        } else {
            spans.map { span ->
                val speaker = span.speakerId?.let(speakersById::get)
                TimelineSpeakerSpanInput(
                    id = span.id,
                    spanIndex = span.spanIndex,
                    sourceSegmentId = span.sourceTranscriptSegmentId,
                    speakerId = speaker?.id,
                    speakerOrdinal = speaker?.speakerOrdinal,
                    speakerDisplayName = speaker?.displayName,
                    startSampleIndex = span.startSampleIndex,
                    endSampleIndexExclusive = span.endSampleIndexExclusive,
                    tokenSource = span.tokenSource?.toTokenSource(),
                    tokenStartIndex = span.tokenStartIndex,
                    tokenEndIndexExclusive = span.tokenEndIndexExclusive,
                    finalTextStartOffset = span.finalTextStartOffset,
                    finalTextEndOffsetExclusive = span.finalTextEndOffsetExclusive,
                    assignmentQuality = span.assignmentQuality.toAssignmentQuality(),
                    overlap = span.overlap,
                    ambiguous = span.ambiguous,
                )
            }
        }

    return buildTranscriptTimeline(
        recordingId = transcription.recordingId,
        transcriptionId = transcription.id,
        alignmentId = alignmentId,
        sourceCanonicalAssetId = transcription.sourceCanonicalAssetId,
        sourceCanonicalSha256 = transcription.sourceCanonicalSha256,
        canonicalProfileId = transcription.canonicalProfileId,
        totalSampleCount = transcription.totalSampleCount,
        segments = segmentInputs,
        speakerSpans = spanInputs,
    )
}

internal fun timelineDisplaySegments(
    timeline: TranscriptTimeline,
    sourceSegments: List<TranscriptSegmentEntity>,
): List<TranscriptDisplaySegment> {
    val segmentIndexById = sourceSegments.associate { it.id to it.segmentIndex }
    return timeline.rows.mapIndexed { displayIndex, row ->
        TranscriptDisplaySegment(
            stableId = row.id,
            displayIndex = displayIndex,
            segmentIndex = checkNotNull(segmentIndexById[row.sourceSegmentId]),
            startSampleIndex = row.startSampleIndex,
            endSampleIndexExclusive = row.endSampleIndexExclusive,
            text = row.text,
            speakerId = row.speakerId,
            speakerOrdinal = row.speakerOrdinal,
            speakerDisplayName = row.speakerDisplayName,
            speakerAssignmentAvailable = row.speakerSpanId != null,
            overlap = row.overlap,
            ambiguous = row.ambiguous,
            assignmentQuality = row.assignmentQuality,
            cues = row.cues,
        )
    }
}

private fun String.toTokenSource(): TokenSource =
    when (this) {
        TranscriptTokenSourceValue.FIRST_PASS -> TokenSource.FIRST_PASS
        TranscriptTokenSourceValue.SECOND_PASS -> TokenSource.SECOND_PASS
        else -> error("unknown transcript token source: " + this)
    }

private fun String.toAssignmentQuality(): SpeakerAssignmentQuality =
    when (this) {
        SpeakerAssignmentQualityValue.ASSIGNED ->
            SpeakerAssignmentQuality.ASSIGNED
        SpeakerAssignmentQualityValue.ASSIGNED_WITH_OVERLAP ->
            SpeakerAssignmentQuality.ASSIGNED_WITH_OVERLAP
        SpeakerAssignmentQualityValue.OVERLAP_AMBIGUOUS ->
            SpeakerAssignmentQuality.OVERLAP_AMBIGUOUS
        SpeakerAssignmentQualityValue.UNRESOLVED ->
            SpeakerAssignmentQuality.UNRESOLVED
        else -> error("unknown speaker assignment quality: " + this)
    }
