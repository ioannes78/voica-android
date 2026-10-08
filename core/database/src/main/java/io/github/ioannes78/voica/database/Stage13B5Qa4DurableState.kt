package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "recording_candidate_attention",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecordingCandidateAttentionEntity(
    @PrimaryKey val recordingId: String,
    val dismissedTranscriptionCandidateId: String?,
    val dismissedAiSummaryCandidateId: String?,
    val updatedAtMs: Long,
    val dismissedStaleSummaryFingerprint: String? = null,
)

@Dao
interface Stage13B5Qa4Dao {
    @Query(
        """
        SELECT * FROM recording_candidate_attention
        WHERE recordingId = :recordingId
        LIMIT 1
        """,
    )
    fun observeCandidateAttention(recordingId: String): Flow<RecordingCandidateAttentionEntity?>

    @Query(
        """
        SELECT * FROM recording_candidate_attention
        WHERE recordingId = :recordingId
        LIMIT 1
        """,
    )
    suspend fun findCandidateAttention(recordingId: String): RecordingCandidateAttentionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidateAttention(entity: RecordingCandidateAttentionEntity)

    @Query(
        """
        SELECT candidate.*
        FROM transcriptions AS candidate
        INNER JOIN recording_content_selection AS selection
            ON selection.recordingId = candidate.recordingId
        INNER JOIN transcriptions AS selected_current
            ON selected_current.id = selection.currentTranscriptionId
        LEFT JOIN recording_candidate_attention AS attention
            ON attention.recordingId = candidate.recordingId
        WHERE candidate.state = 'COMPLETED'
          AND candidate.id != selected_current.id
          AND candidate.id = (
              SELECT latest.id
              FROM transcriptions AS latest
              WHERE latest.recordingId = candidate.recordingId
                AND latest.state = 'COMPLETED'
                AND latest.id != selected_current.id
              ORDER BY latest.completedAtMs DESC, latest.createdAtMs DESC
              LIMIT 1
          )
          AND (
              COALESCE(candidate.completedAtMs, candidate.createdAtMs) >
                  COALESCE(selected_current.completedAtMs, selected_current.createdAtMs)
              OR (
                  COALESCE(candidate.completedAtMs, candidate.createdAtMs) =
                      COALESCE(selected_current.completedAtMs, selected_current.createdAtMs)
                  AND candidate.createdAtMs > selected_current.createdAtMs
              )
          )
          AND (
              attention.dismissedTranscriptionCandidateId IS NULL
              OR attention.dismissedTranscriptionCandidateId != candidate.id
          )
        ORDER BY candidate.updatedAtMs DESC, candidate.createdAtMs DESC
        """,
    )
    fun observeTranscriptionCandidates(): Flow<List<TranscriptionEntity>>

    @Query(
        """
        SELECT candidate.*
        FROM ai_summaries AS candidate
        INNER JOIN recording_content_selection AS selection
            ON selection.recordingId = candidate.recordingId
        INNER JOIN ai_summaries AS selected_current
            ON selected_current.id = selection.currentAiSummaryId
        LEFT JOIN recording_candidate_attention AS attention
            ON attention.recordingId = candidate.recordingId
        WHERE candidate.status = 'COMPLETED'
          AND candidate.id != selected_current.id
          AND candidate.id = (
              SELECT latest.id
              FROM ai_summaries AS latest
              WHERE latest.recordingId = candidate.recordingId
                AND latest.status = 'COMPLETED'
                AND latest.id != selected_current.id
              ORDER BY latest.completedAtMs DESC, latest.createdAtMs DESC
              LIMIT 1
          )
          AND (
              COALESCE(candidate.completedAtMs, candidate.createdAtMs) >
                  COALESCE(selected_current.completedAtMs, selected_current.createdAtMs)
              OR (
                  COALESCE(candidate.completedAtMs, candidate.createdAtMs) =
                      COALESCE(selected_current.completedAtMs, selected_current.createdAtMs)
                  AND candidate.createdAtMs > selected_current.createdAtMs
              )
          )
          AND (
              attention.dismissedAiSummaryCandidateId IS NULL
              OR attention.dismissedAiSummaryCandidateId != candidate.id
          )
        ORDER BY candidate.updatedAtMs DESC, candidate.createdAtMs DESC
        """,
    )
    fun observeAiSummaryCandidates(): Flow<List<AiSummaryEntity>>

    @Query(
        """
        SELECT summary.*
        FROM ai_summaries AS summary
        LEFT JOIN recording_content_selection AS selection
            ON selection.recordingId = summary.recordingId
        LEFT JOIN ai_summaries AS selected
            ON selected.id = selection.currentAiSummaryId
        WHERE summary.executionGeneration > 0
          AND summary.status IN (:states)
          AND summary.terminalAcknowledgedAtMs IS NULL
          AND summary.id NOT IN (
              SELECT retryOfSummaryId
              FROM ai_summaries
              WHERE retryOfSummaryId IS NOT NULL
          )
          AND NOT (
              selected.id IS NOT NULL
              AND selected.status = 'COMPLETED'
              AND selected.createdAtMs > summary.createdAtMs
          )
          AND NOT (
              summary.status IN ('FAILED', 'INTERRUPTED')
              AND EXISTS (
                  SELECT 1 FROM ai_summaries AS newer
                  WHERE newer.recordingId = summary.recordingId
                    AND newer.createdAtMs > summary.createdAtMs
              )
          )
        ORDER BY summary.updatedAtMs DESC, summary.createdAtMs DESC
        """,
    )
    fun observeAiSummaryAttention(states: List<String>): Flow<List<AiSummaryEntity>>
}

class Stage13B5Qa4Repository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.stage13B5Qa4Dao()
    private val transcriptionDao = database.transcriptionDao()

    fun observeCandidateAttention(recordingId: String): Flow<RecordingCandidateAttentionEntity?> =
        dao.observeCandidateAttention(recordingId)

    fun observeTranscriptionCandidates(): Flow<List<TranscriptionEntity>> =
        dao.observeTranscriptionCandidates()

    fun observeAiSummaryCandidates(): Flow<List<AiSummaryEntity>> =
        dao.observeAiSummaryCandidates()

    fun observeTranscriptionAttention(): Flow<List<TranscriptionEntity>> =
        transcriptionDao.observeDurableAttention(TranscriptionStateValue.ATTENTION)

    fun observeTranscriptionAttentionForRecording(recordingId: String): Flow<TranscriptionEntity?> =
        transcriptionDao.observeDurableAttentionForRecording(
            recordingId = recordingId,
            states = TranscriptionStateValue.ATTENTION,
        )

    fun observeAiSummaryAttention(): Flow<List<AiSummaryEntity>> =
        dao.observeAiSummaryAttention(QA4_AI_ATTENTION_STATES)

    suspend fun dismissTranscriptionCandidate(
        recordingId: String,
        candidateId: String,
    ) {
        require(recordingId.isNotBlank())
        require(candidateId.isNotBlank())
        val current = dao.findCandidateAttention(recordingId)
        dao.upsertCandidateAttention(
            (current ?: RecordingCandidateAttentionEntity(
                recordingId = recordingId,
                dismissedTranscriptionCandidateId = null,
                dismissedAiSummaryCandidateId = null,
                updatedAtMs = nowMs(),
            )).copy(
                dismissedTranscriptionCandidateId = candidateId,
                updatedAtMs = nowMs(),
            ),
        )
    }

    suspend fun dismissAiSummaryCandidate(
        recordingId: String,
        candidateId: String,
    ) {
        require(recordingId.isNotBlank())
        require(candidateId.isNotBlank())
        val current = dao.findCandidateAttention(recordingId)
        dao.upsertCandidateAttention(
            (current ?: RecordingCandidateAttentionEntity(
                recordingId = recordingId,
                dismissedTranscriptionCandidateId = null,
                dismissedAiSummaryCandidateId = null,
                updatedAtMs = nowMs(),
            )).copy(
                dismissedAiSummaryCandidateId = candidateId,
                updatedAtMs = nowMs(),
            ),
        )
    }

    suspend fun dismissStaleSummary(
        recordingId: String,
        fingerprint: String,
    ) {
        require(recordingId.isNotBlank())
        require(fingerprint.isNotBlank())
        val current = dao.findCandidateAttention(recordingId)
        dao.upsertCandidateAttention(
            (current ?: RecordingCandidateAttentionEntity(
                recordingId = recordingId,
                dismissedTranscriptionCandidateId = null,
                dismissedAiSummaryCandidateId = null,
                updatedAtMs = nowMs(),
            )).copy(
                dismissedStaleSummaryFingerprint = fingerprint,
                updatedAtMs = nowMs(),
            ),
        )
    }

    suspend fun clearTranscriptionCandidateDismissal(recordingId: String) {
        val current = dao.findCandidateAttention(recordingId) ?: return
        if (current.dismissedTranscriptionCandidateId == null) return
        dao.upsertCandidateAttention(
            current.copy(
                dismissedTranscriptionCandidateId = null,
                updatedAtMs = nowMs(),
            ),
        )
    }

    suspend fun clearAiSummaryCandidateDismissal(recordingId: String) {
        val current = dao.findCandidateAttention(recordingId) ?: return
        if (current.dismissedAiSummaryCandidateId == null) return
        dao.upsertCandidateAttention(
            current.copy(
                dismissedAiSummaryCandidateId = null,
                updatedAtMs = nowMs(),
            ),
        )
    }

    suspend fun acknowledgeTranscriptionAttention(transcriptionId: String): Boolean =
        transcriptionDao.acknowledgeTerminal(
            transcriptionId = transcriptionId,
            states = TranscriptionStateValue.ATTENTION,
            nowMs = nowMs(),
        ) == 1

    suspend fun acknowledgePreviousTranscriptionAttention(
        recordingId: String,
        replacementTranscriptionId: String,
    ): Int =
        transcriptionDao.acknowledgePreviousAttention(
            recordingId = recordingId,
            exceptTranscriptionId = replacementTranscriptionId,
            states = TranscriptionStateValue.ATTENTION,
            nowMs = nowMs(),
        )

    private companion object {
        val QA4_AI_ATTENTION_STATES =
            listOf(
                AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                AiSummaryStateValue.FAILED,
                AiSummaryStateValue.INTERRUPTED,
            )
    }
}
