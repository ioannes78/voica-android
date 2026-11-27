package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface OfflineSummaryLongDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: OfflineSummaryTaskEntity)

    @Update
    suspend fun updateTask(task: OfflineSummaryTaskEntity): Int

    @Query("SELECT * FROM offline_summary_tasks WHERE aiSummaryId = :summaryId LIMIT 1")
    suspend fun findTask(summaryId: String): OfflineSummaryTaskEntity?

    @Query(
        """
        SELECT * FROM offline_summary_tasks
        WHERE status IN (:statuses)
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun loadTasksByStatuses(statuses: List<String>): List<OfflineSummaryTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNodes(nodes: List<OfflineSummaryNodeEntity>)

    @Update
    suspend fun updateNode(node: OfflineSummaryNodeEntity): Int

    @Query("SELECT * FROM offline_summary_nodes WHERE id = :nodeId LIMIT 1")
    suspend fun findNode(nodeId: String): OfflineSummaryNodeEntity?

    @Query(
        """
        SELECT * FROM offline_summary_nodes
        WHERE aiSummaryId = :summaryId
        ORDER BY level ASC, ordinal ASC
        """,
    )
    suspend fun loadNodes(summaryId: String): List<OfflineSummaryNodeEntity>

    @Query(
        """
        SELECT * FROM offline_summary_nodes
        WHERE aiSummaryId = :summaryId AND status IN (:statuses)
        ORDER BY level ASC, ordinal ASC
        """,
    )
    suspend fun loadNodesByStatuses(
        summaryId: String,
        statuses: List<String>,
    ): List<OfflineSummaryNodeEntity>

    @Query("DELETE FROM offline_summary_nodes WHERE aiSummaryId = :summaryId")
    suspend fun deleteNodes(summaryId: String): Int

    @Query("DELETE FROM offline_summary_tasks WHERE aiSummaryId = :summaryId")
    suspend fun deleteTask(summaryId: String): Int
}
