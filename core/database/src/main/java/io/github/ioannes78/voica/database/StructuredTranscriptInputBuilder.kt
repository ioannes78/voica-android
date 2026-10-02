package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.EvidenceRefGenerator
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.TranscriptEvidenceSeed
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind

class StructuredTranscriptInputBuilder(
    private val database: VoicaDatabase,
) {
    suspend fun build(transcriptionId: String): StructuredTranscriptInput {
        require(transcriptionId.isNotBlank())
        val transcription =
            database.transcriptionDao().findTranscription(transcriptionId)
                ?: error("transcription not found")
        require(transcription.state == TranscriptionStateValue.COMPLETED) {
            "AI summary requires a completed transcription"
        }

        val segments = database.transcriptionDao().loadSegments(transcriptionId)
        val segmentsById = segments.associateBy { it.id }
        val alignment =
            database.diarizationDao().loadLatestCompletedAlignment(transcriptionId)

        val drafts =
            if (alignment != null) {
                buildSpeakerDrafts(
                    alignment = alignment,
                    segmentsById = segmentsById,
                ).ifEmpty {
                    buildSegmentDrafts(segments)
                }
            } else {
                buildSegmentDrafts(segments)
            }

        require(drafts.isNotEmpty()) {
            "completed transcription has no non-blank final text"
        }

        val refs =
            EvidenceRefGenerator().assign(
                drafts.map { draft ->
                    TranscriptEvidenceSeed(
                        sourceKind = draft.sourceKind,
                        sourceId = draft.sourceId,
                        speakerId = draft.speakerId,
                        speakerDisplayName = draft.speakerDisplayName,
                        assignmentQuality = draft.assignmentQuality,
                        overlap = draft.overlap,
                        ambiguous = draft.ambiguous,
                        startSampleIndex = draft.startSampleIndex,
                        endSampleIndexExclusive = draft.endSampleIndexExclusive,
                    )
                },
            )

        return StructuredTranscriptInput(
            recordingId = transcription.recordingId,
            transcriptionId = transcription.id,
            transcriptionMode = transcription.mode,
            canonicalAssetId = transcription.sourceCanonicalAssetId,
            canonicalSha256 = transcription.sourceCanonicalSha256,
            canonicalProfileId = transcription.canonicalProfileId,
            totalSampleCount = transcription.totalSampleCount,
            languageConfig = transcription.languageConfig,
            alignmentId = alignment?.id,
            units =
                drafts.zip(refs).map { (draft, evidence) ->
                    StructuredTranscriptUnit(
                        evidence = evidence,
                        text = draft.text,
                        detectedLanguage = draft.detectedLanguage,
                    )
                },
        )
    }

    private suspend fun buildSpeakerDrafts(
        alignment: TranscriptSpeakerAlignmentEntity,
        segmentsById: Map<String, TranscriptSegmentEntity>,
    ): List<Draft> {
        val dao = database.diarizationDao()
        val speakers =
            dao.loadSpeakers(alignment.diarizationRunId)
                .associateBy { it.id }
        return dao.loadSpans(alignment.id).mapNotNull { span ->
            val segment =
                segmentsById[span.sourceTranscriptSegmentId]
                    ?: error("speaker span references missing transcript segment")
            require(span.finalTextStartOffset in 0..segment.finalText.length)
            require(span.finalTextEndOffsetExclusive in span.finalTextStartOffset..segment.finalText.length)
            val text =
                segment.finalText
                    .substring(span.finalTextStartOffset, span.finalTextEndOffsetExclusive)
                    .trim()
            if (text.isBlank()) {
                null
            } else {
                val speaker = span.speakerId?.let(speakers::get)
                Draft(
                    sourceKind = TranscriptEvidenceSourceKind.SPEAKER_SPAN,
                    sourceId = span.id,
                    speakerId = span.speakerId,
                    speakerDisplayName =
                        speaker?.displayName?.takeIf { it.isNotBlank() }
                            ?: speaker?.let { "说话人 ${it.speakerOrdinal}" },
                    assignmentQuality = span.assignmentQuality,
                    overlap = span.overlap,
                    ambiguous = span.ambiguous,
                    startSampleIndex = span.startSampleIndex,
                    endSampleIndexExclusive = span.endSampleIndexExclusive,
                    text = text,
                    detectedLanguage = segment.detectedLanguage,
                )
            }
        }
    }

    private fun buildSegmentDrafts(
        segments: List<TranscriptSegmentEntity>,
    ): List<Draft> =
        segments.mapNotNull { segment ->
            val text = segment.finalText.trim()
            if (text.isBlank()) {
                null
            } else {
                Draft(
                    sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                    sourceId = segment.id,
                    speakerId = null,
                    speakerDisplayName = null,
                    assignmentQuality = null,
                    overlap = false,
                    ambiguous = false,
                    startSampleIndex = segment.startSampleIndex,
                    endSampleIndexExclusive = segment.endSampleIndexExclusive,
                    text = text,
                    detectedLanguage = segment.detectedLanguage,
                )
            }
        }

    private data class Draft(
        val sourceKind: TranscriptEvidenceSourceKind,
        val sourceId: String,
        val speakerId: String?,
        val speakerDisplayName: String?,
        val assignmentQuality: String?,
        val overlap: Boolean,
        val ambiguous: Boolean,
        val startSampleIndex: Long,
        val endSampleIndexExclusive: Long,
        val text: String,
        val detectedLanguage: String?,
    )
}
