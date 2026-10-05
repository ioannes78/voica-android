package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.EvidenceRefGenerator
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.TranscriptEvidenceSeed
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class StructuredTranscriptInputBuilder(
    private val database: VoicaDatabase,
) {
    /**
     * Builds and freezes the recording's Current Effective Transcription at this call boundary.
     * The supplied transcription id is only used to identify the recording. If that id is a
     * candidate/history result, it must not bypass RecordingContentSelection.
     */
    suspend fun build(transcriptionId: String): StructuredTranscriptInput =
        buildEffective(transcriptionId)

    suspend fun buildEffective(transcriptionId: String): StructuredTranscriptInput {
        require(transcriptionId.isNotBlank())
        val transcriptionDao = database.transcriptionDao()
        val contentDao = database.stage12cContentDao()
        val requested =
            transcriptionDao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        val selectedId = contentDao.findContentSelection(requested.recordingId)?.currentTranscriptionId
        val selected = selectedId?.let { transcriptionDao.findTranscription(it) }
        val effective =
            selected?.takeIf {
                it.recordingId == requested.recordingId &&
                    it.state == TranscriptionStateValue.COMPLETED
            } ?: transcriptionDao.findLatestCompleted(requested.recordingId)
            ?: requested.takeIf { it.state == TranscriptionStateValue.COMPLETED }
            ?: error("recording has no completed transcription")
        val currentRevisionId =
            contentDao.findTranscriptionMetadata(effective.id)?.currentRevisionId
                ?.let { revisionId ->
                    contentDao.findTranscriptionRevision(revisionId)
                        ?.takeIf { it.transcriptionId == effective.id }
                        ?.id
                }
        return buildSnapshot(effective.id, currentRevisionId)
    }

    /**
     * Rebuilds an exact lineage snapshot. A null revisionId explicitly means model original;
     * it does not resolve today's current revision.
     */
    suspend fun buildSnapshot(
        transcriptionId: String,
        revisionId: String?,
    ): StructuredTranscriptInput {
        require(transcriptionId.isNotBlank())
        val transcriptionDao = database.transcriptionDao()
        val contentDao = database.stage12cContentDao()
        val transcription =
            transcriptionDao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        require(transcription.state == TranscriptionStateValue.COMPLETED) {
            "AI summary requires a completed transcription"
        }

        val segments = transcriptionDao.loadSegments(transcriptionId)
        val segmentsById = segments.associateBy { it.id }
        val alignment =
            database.diarizationDao().loadLatestCompletedAlignment(transcriptionId)

        val drafts =
            if (revisionId != null) {
                val revision =
                    contentDao.findTranscriptionRevision(revisionId)
                        ?: error("transcription revision not found")
                require(revision.transcriptionId == transcriptionId) {
                    "transcription revision does not belong to transcription"
                }
                buildRevisionDrafts(
                    revisionId = revisionId,
                    alignment = alignment,
                    segments = segments,
                    segmentsById = segmentsById,
                )
            } else if (alignment != null) {
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
            "completed transcription has no non-blank effective text"
        }

        val anchoredDrafts = drafts.filter { it.hasAudioAnchor }
        val refs =
            EvidenceRefGenerator().assign(
                anchoredDrafts.map { draft ->
                    TranscriptEvidenceSeed(
                        sourceKind = checkNotNull(draft.sourceKind),
                        sourceId = checkNotNull(draft.sourceId),
                        speakerId = draft.speakerId,
                        speakerDisplayName = draft.speakerDisplayName,
                        assignmentQuality = draft.assignmentQuality,
                        overlap = draft.overlap,
                        ambiguous = draft.ambiguous,
                        startSampleIndex = checkNotNull(draft.startSampleIndex),
                        endSampleIndexExclusive = checkNotNull(draft.endSampleIndexExclusive),
                    )
                },
            )
        var anchoredIndex = 0
        val units =
            drafts.map { draft ->
                val evidence =
                    if (draft.hasAudioAnchor) {
                        refs[anchoredIndex++]
                    } else {
                        null
                    }
                StructuredTranscriptUnit(
                    evidence = evidence,
                    text = draft.text,
                    detectedLanguage = draft.detectedLanguage,
                )
            }
        val effectiveText = units.joinToString("\n") { it.text }

        return StructuredTranscriptInput(
            recordingId = transcription.recordingId,
            transcriptionId = transcription.id,
            transcriptionRevisionId = revisionId,
            inputContentDigest = sha256(effectiveText),
            transcriptionMode = transcription.mode,
            canonicalAssetId = transcription.sourceCanonicalAssetId,
            canonicalSha256 = transcription.sourceCanonicalSha256,
            canonicalProfileId = transcription.canonicalProfileId,
            totalSampleCount = transcription.totalSampleCount,
            languageConfig = transcription.languageConfig,
            alignmentId = alignment?.id,
            units = units,
        )
    }

    private suspend fun buildRevisionDrafts(
        revisionId: String,
        alignment: TranscriptSpeakerAlignmentEntity?,
        segments: List<TranscriptSegmentEntity>,
        segmentsById: Map<String, TranscriptSegmentEntity>,
    ): List<Draft> {
        val contentDao = database.stage12cContentDao()
        val speakerNames =
            if (alignment == null) {
                emptyMap()
            } else {
                database.diarizationDao().loadSpeakers(alignment.diarizationRunId)
                    .associate { speaker ->
                        speaker.id to
                            (speaker.displayName?.takeIf { it.isNotBlank() }
                                ?: "说话人 ${speaker.speakerOrdinal}")
                    }
            }
        return contentDao.loadTranscriptionRevisionParagraphs(revisionId)
            .mapNotNull { paragraph ->
                val text = paragraph.text.trim()
                if (text.isBlank()) return@mapNotNull null
                val start = paragraph.anchorStartSampleIndex
                val end = paragraph.anchorEndSampleIndexExclusive
                val sourceSegment =
                    if (start != null && end != null) {
                        decodeAnchorRefs(paragraph.sourceAnchorRefsJson)
                            .firstNotNullOfOrNull { ref ->
                                ref.removePrefix(SEGMENT_PREFIX)
                                    .takeIf { ref.startsWith(SEGMENT_PREFIX) }
                                    ?.let(segmentsById::get)
                            }
                            ?: segments.firstOrNull { segment ->
                                segment.startSampleIndex < end &&
                                    segment.endSampleIndexExclusive > start
                            }
                    } else {
                        null
                    }
                Draft(
                    sourceKind =
                        if (start != null && end != null && sourceSegment != null) {
                            TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT
                        } else {
                            null
                        },
                    sourceId =
                        if (start != null && end != null && sourceSegment != null) {
                            sourceSegment.id
                        } else {
                            null
                        },
                    speakerId = paragraph.speakerId,
                    speakerDisplayName = paragraph.speakerId?.let(speakerNames::get),
                    assignmentQuality =
                        if (start != null && end != null && sourceSegment != null) {
                            USER_REVISION_ANCHOR
                        } else {
                            null
                        },
                    overlap = false,
                    ambiguous = false,
                    startSampleIndex =
                        if (sourceSegment != null) start else null,
                    endSampleIndexExclusive =
                        if (sourceSegment != null) end else null,
                    text = text,
                    detectedLanguage = sourceSegment?.detectedLanguage,
                )
            }
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

    private fun decodeAnchorRefs(raw: String): List<String> =
        Regex("\"([^\"]+)\"")
            .findAll(raw)
            .map { it.groupValues[1] }
            .toList()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class Draft(
        val sourceKind: TranscriptEvidenceSourceKind?,
        val sourceId: String?,
        val speakerId: String?,
        val speakerDisplayName: String?,
        val assignmentQuality: String?,
        val overlap: Boolean,
        val ambiguous: Boolean,
        val startSampleIndex: Long?,
        val endSampleIndexExclusive: Long?,
        val text: String,
        val detectedLanguage: String?,
    ) {
        val hasAudioAnchor: Boolean
            get() =
                sourceKind != null &&
                    sourceId != null &&
                    startSampleIndex != null &&
                    endSampleIndexExclusive != null
    }

    private companion object {
        const val SEGMENT_PREFIX = "SEGMENT:"
        const val USER_REVISION_ANCHOR = "USER_REVISION_ANCHOR"
    }
}
