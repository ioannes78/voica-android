package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AiSummaryOwnershipDao {
    @Query(
        """
        UPDATE ai_summaries
        SET status = 'PREPARING',
            ownerTaskId = :ownerTaskId,
            startedAtMs = :nowMs,
            updatedAtMs = :nowMs,
            completedAtMs = NULL,
            errorCode = NULL,
            sanitizedErrorMessage = NULL,
            executionGeneration = CASE
                WHEN executionGeneration < 1 THEN 1
                ELSE executionGeneration + 1
            END
        WHERE id = :summaryId
          AND status = 'INTERRUPTED'
          AND remoteDispatchState = 'NONE'
        """,
    )
    suspend fun resumeInterrupted(
        summaryId: String,
        ownerTaskId: Int,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE ai_summaries
        SET status = 'INTERRUPTED',
            remoteDispatchState = 'NONE',
            remoteRequestId = NULL,
            remoteStepKind = NULL,
            remoteStepKey = NULL,
            remoteStartedAtMs = NULL,
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'APP_TASK_REMOVED',
            sanitizedErrorMessage = :sanitizedErrorMessage
        WHERE id = :summaryId
          AND executionGeneration = :generation
          AND status IN (:activeStates)
          AND remoteDispatchState IN ('NONE', 'READY_TO_SEND')
        """,
    )
    suspend fun interruptMissingOwnerBeforeSend(
        summaryId: String,
        generation: Long,
        nowMs: Long,
        sanitizedErrorMessage: String,
        activeStates: List<String>,
    ): Int
}
