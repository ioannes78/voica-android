package io.github.ioannes78.voica.database

/**
 * Narrow mutation boundary for human correction of transcript-speaker alignment.
 *
 * Acoustic overlap evidence stays immutable here. The user may only choose which speaker owns the
 * displayed text span; this updates speakerId/assignment quality/ambiguity on an existing completed
 * alignment without changing timing, text or the underlying diarization turns.
 */
class TranscriptSpeakerAssignmentRepository(
    private val database: VoicaDatabase,
) {
    private val dao = database.diarizationDao()

    suspend fun assignSpeaker(
        alignmentId: String,
        spanId: String,
        speakerId: String,
    ): Boolean {
        require(alignmentId.isNotBlank())
        require(spanId.isNotBlank())
        require(speakerId.isNotBlank())

        val alignment = dao.findAlignment(alignmentId) ?: return false
        if (alignment.state != TranscriptSpeakerAlignmentStateValue.COMPLETED) return false

        val span = dao.loadSpans(alignmentId).firstOrNull { it.id == spanId } ?: return false
        val speaker =
            dao.loadSpeakers(alignment.diarizationRunId)
                .firstOrNull { it.id == speakerId }
                ?: return false

        check(speaker.diarizationRunId == alignment.diarizationRunId)
        val assignmentQuality =
            if (span.overlap) {
                SpeakerAssignmentQualityValue.ASSIGNED_WITH_OVERLAP
            } else {
                SpeakerAssignmentQualityValue.ASSIGNED
            }
        return dao.updateSpanSpeakerAssignment(
            alignmentId = alignmentId,
            spanId = spanId,
            speakerId = speaker.id,
            assignmentQuality = assignmentQuality,
            ambiguous = false,
        ) == 1
    }

    suspend fun restoreAmbiguousOverlap(
        alignmentId: String,
        spanId: String,
    ): Boolean {
        require(alignmentId.isNotBlank())
        require(spanId.isNotBlank())

        val alignment = dao.findAlignment(alignmentId) ?: return false
        if (alignment.state != TranscriptSpeakerAlignmentStateValue.COMPLETED) return false
        val span = dao.loadSpans(alignmentId).firstOrNull { it.id == spanId } ?: return false
        if (!span.overlap) return false

        return dao.updateSpanSpeakerAssignment(
            alignmentId = alignmentId,
            spanId = spanId,
            speakerId = null,
            assignmentQuality = SpeakerAssignmentQualityValue.OVERLAP_AMBIGUOUS,
            ambiguous = true,
        ) == 1
    }
}
