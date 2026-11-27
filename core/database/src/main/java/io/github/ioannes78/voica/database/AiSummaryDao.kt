package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSummaryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSummary(summary: AiSummaryEntity)

    @Update
    suspend fun updateSummary(summary: AiSummaryEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidence(evidence: List<AiSummaryEvidenceEntity>)

    @Query("SELECT * FROM ai_summaries WHERE id = :summaryId LIMIT 1")
    suspend fun findSummary(summaryId: String): AiSummaryEntity?

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE promptVersion = :promptVersion AND status IN (:statuses)
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun loadByPromptVersionAndStatuses(
        promptVersion: Int,
        statuses: List<String>,
    ): List<AiSummaryEntity>

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE recordingId = :recordingId
        ORDER BY createdAtMs DESC
        """,
    )
    fun observeForRecording(recordingId: String): Flow<List<AiSummaryEntity>>

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE recordingId = :recordingId AND status = 'COMPLETED'
        ORDER BY completedAtMs DESC, createdAtMs DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestCompleted(recordingId: String): AiSummaryEntity?

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE recordingId = :recordingId
          AND status = 'COMPLETED'
          AND id != :excludedId
        ORDER BY completedAtMs DESC, createdAtMs DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestCompletedExcluding(
        recordingId: String,
        excludedId: String,
    ): AiSummaryEntity?

    @Query("SELECT COUNT(*) FROM ai_summaries WHERE transcriptionId = :transcriptionId")
    suspend fun countForTranscription(transcriptionId: String): Int

    @Query(
        """
        SELECT * FROM ai_summary_evidence
        WHERE aiSummaryId = :summaryId
        ORDER BY summaryItemId ASC, startSampleIndex ASC
        """,
    )
    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity>

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE status = 'COMPLETED'
        ORDER BY completedAtMs DESC, createdAtMs DESC
        """,
    )
    suspend fun loadAllCompleted(): List<AiSummaryEntity>

    @Query("DELETE FROM ai_summaries WHERE id = :summaryId")
    suspend fun deleteVersion(summaryId: String): Int
}
