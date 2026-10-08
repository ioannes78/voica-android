package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID
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
}

class Stage13B5Qa4Repository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.stage13B5Qa4Dao()
    private val transcriptionDao = database.transcriptionDao()

    fun observeCandidateAttention(recordingId: String): Flow<RecordingCandidateAttentionEntity?> =
        dao.observeCandidateAttention(recordingId)

    fun observeTranscriptionAttention(): Flow<List<TranscriptionEntity>> =
        transcriptionDao.observeDurableAttention(TranscriptionStateValue.ATTENTION)

    fun observeTranscriptionAttentionForRecording(recordingId: String): Flow<TranscriptionEntity?> =
        transcriptionDao.observeDurableAttentionForRecording(
            recordingId = recordingId,
            states = TranscriptionStateValue.ATTENTION,
        )

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
}
