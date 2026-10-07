package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSummaryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSummary(summary: AiSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidence(evidence: List<AiSummaryEvidenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: AiSummaryChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomTemplate(template: AiCustomTemplateEntity)

    @Query("SELECT * FROM ai_summaries WHERE id = :summaryId LIMIT 1")
    suspend fun findSummary(summaryId: String): AiSummaryEntity?

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
        WHERE transcriptionId = :transcriptionId
        ORDER BY createdAtMs DESC
        """,
    )
    fun observeForTranscription(transcriptionId: String): Flow<List<AiSummaryEntity>>

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE status IN (:activeStates)
           OR (executionGeneration > 0 AND status IN (:terminalAttentionStates) AND terminalAcknowledgedAtMs IS NULL)
        ORDER BY updatedAtMs DESC, createdAtMs DESC
        """,
    )
    fun observeDurableTasks(
        activeStates: List<String>,
        terminalAttentionStates: List<String>,
    ): Flow<List<AiSummaryEntity>>

    @Query(
        """
        SELECT * FROM ai_summaries
        WHERE status IN (:activeStates)
        ORDER BY updatedAtMs ASC, createdAtMs ASC
        """,
    )
    suspend fun loadActiveSummaries(activeStates: List<String>): List<AiSummaryEntity>

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
        SELECT * FROM ai_summary_chunks
        WHERE aiSummaryId = :summaryId
        ORDER BY level ASC, chunkIndex ASC
        """,
    )
    suspend fun loadChunks(summaryId: String): List<AiSummaryChunkEntity>

    @Query(
        """
        SELECT * FROM ai_summary_chunks
        WHERE aiSummaryId = :summaryId
          AND level = :level
          AND chunkIndex = :chunkIndex
        LIMIT 1
        """,
    )
    suspend fun findChunk(
        summaryId: String,
        level: Int,
        chunkIndex: Int,
    ): AiSummaryChunkEntity?

    @Query(
        """
        UPDATE ai_summaries
        SET remoteDispatchState = 'READY_TO_SEND',
            remoteRequestId = :requestId,
            remoteStepKind = :stepKind,
            remoteStepKey = :stepKey,
            remoteStartedAtMs = NULL,
            updatedAtMs = :nowMs
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
          AND remoteDispatchState = 'NONE'
        """,
    )
    suspend fun prepareRemoteCall(
        summaryId: String,
        generation: Long,
        requestId: String,
        stepKind: String,
        stepKey: String,
        nowMs: Long,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET remoteDispatchState = 'REQUEST_IN_FLIGHT',
            remoteStartedAtMs = :nowMs,
            remoteCallOrdinal = remoteCallOrdinal + 1,
            updatedAtMs = :nowMs
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
          AND remoteDispatchState = 'READY_TO_SEND'
          AND remoteRequestId = :requestId
          AND remoteStepKind = :stepKind
          AND remoteStepKey = :stepKey
        """,
    )
    suspend fun markRemoteRequestInFlight(
        summaryId: String,
        generation: Long,
        requestId: String,
        stepKind: String,
        stepKey: String,
        nowMs: Long,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET remoteDispatchState = 'NONE',
            remoteRequestId = NULL,
            remoteStepKind = NULL,
            remoteStepKey = NULL,
            remoteStartedAtMs = NULL,
            updatedAtMs = :nowMs
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
          AND remoteDispatchState = 'REQUEST_IN_FLIGHT'
          AND remoteRequestId = :requestId
        """,
    )
    suspend fun clearRemoteDispatch(
        summaryId: String,
        generation: Long,
        requestId: String,
        nowMs: Long,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'AMBIGUOUS_REMOTE_RESULT',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'REMOTE_RESULT_UNKNOWN',
            sanitizedErrorMessage = :sanitizedErrorMessage
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
          AND remoteDispatchState = 'REQUEST_IN_FLIGHT'
        """,
    )
    suspend fun markAmbiguousRemoteResult(
        summaryId: String,
        generation: Long,
        nowMs: Long,
        sanitizedErrorMessage: String,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'CANCELLED',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'USER_CANCELLED',
            sanitizedErrorMessage = :sanitizedErrorMessage
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
        """,
    )
    suspend fun cancelForGeneration(
        summaryId: String,
        generation: Long,
        nowMs: Long,
        sanitizedErrorMessage: String,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET terminalAcknowledgedAtMs = :nowMs,
            updatedAtMs = :nowMs
        WHERE id = :summaryId
          AND status IN (:terminalStates)
          AND terminalAcknowledgedAtMs IS NULL
        """,
    )
    suspend fun acknowledgeTerminal(
        summaryId: String,
        nowMs: Long,
        terminalStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = :status,
            startedAtMs = COALESCE(startedAtMs, :startedAtMs),
            updatedAtMs = :updatedAtMs,
            completedAtMs = :completedAtMs,
            errorCode = :errorCode,
            sanitizedErrorMessage = :sanitizedErrorMessage
        WHERE id = :summaryId AND status IN (:activeStates)
        """,
    )
    suspend fun updateState(
        summaryId: String,
        status: String,
        startedAtMs: Long?,
        updatedAtMs: Long,
        completedAtMs: Long?,
        errorCode: String?,
        sanitizedErrorMessage: String?,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'COMPLETED',
            contentType = :contentType,
            classificationConfidence = :classificationConfidence,
            structuredPayloadJson = :structuredPayloadJson,
            displayText = :displayText,
            usageSnapshot = :usageSnapshot,
            updatedAtMs = :completedAtMs,
            completedAtMs = :completedAtMs,
            errorCode = NULL,
            sanitizedErrorMessage = NULL
        WHERE id = :summaryId AND status IN (:activeStates)
        """,
    )
    suspend fun complete(
        summaryId: String,
        contentType: String,
        classificationConfidence: Double?,
        structuredPayloadJson: String,
        displayText: String,
        usageSnapshot: String?,
        completedAtMs: Long,
        activeStates: List<String>,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'PREPARING',
            startedAtMs = :nowMs,
            updatedAtMs = :nowMs,
            completedAtMs = NULL,
            errorCode = NULL,
            sanitizedErrorMessage = NULL
        WHERE id = :summaryId AND status = 'INTERRUPTED'
        """,
    )
    suspend fun resumeInterrupted(
        summaryId: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'INTERRUPTED',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'PROCESS_INTERRUPTED',
            sanitizedErrorMessage = 'previous AI summary generation was interrupted'
        WHERE status IN (:activeStates)
        """,
    )
    suspend fun markActiveInterrupted(
        activeStates: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM ai_custom_templates
        ORDER BY updatedAtMs DESC, name ASC
        """,
    )
    fun observeCustomTemplates(): Flow<List<AiCustomTemplateEntity>>

    @Query("SELECT * FROM ai_custom_templates WHERE id = :templateId LIMIT 1")
    suspend fun findCustomTemplate(templateId: String): AiCustomTemplateEntity?

    @Query("DELETE FROM ai_custom_templates WHERE id = :templateId")
    suspend fun deleteCustomTemplate(templateId: String): Int

    @Query("""
        SELECT * FROM ai_summaries
        WHERE status = 'COMPLETED'
        ORDER BY completedAtMs DESC, createdAtMs DESC
    """)
    suspend fun loadAllCompleted(): List<AiSummaryEntity>

    @Query("DELETE FROM ai_summaries WHERE id = :summaryId")
    suspend fun deleteVersion(summaryId: String): Int
}