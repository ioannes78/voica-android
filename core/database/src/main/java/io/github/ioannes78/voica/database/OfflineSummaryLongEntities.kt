package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object OfflineSummaryTaskRouteValue {
    const val LONG = "LONG"
}

object OfflineSummaryTaskStateValue {
    const val PLANNING = "PLANNING"
    const val MAPPING = "MAPPING"
    const val REDUCING = "REDUCING"
    const val VALIDATING = "VALIDATING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"

    val ACTIVE = setOf(PLANNING, MAPPING, REDUCING, VALIDATING)
}

object OfflineSummaryNodeTypeValue {
    const val MAP = "MAP"
    const val REDUCE = "REDUCE"
    const val FINAL = "FINAL"
}

object OfflineSummaryNodeStateValue {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val VALIDATED = "VALIDATED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
}

@Entity(
    tableName = "offline_summary_tasks",
    foreignKeys = [
        ForeignKey(
            entity = AiSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["aiSummaryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class OfflineSummaryTaskEntity(
    @PrimaryKey val aiSummaryId: String,
    val pipelineVersion: Int,
    val plannerVersion: Int,
    val mapPromptVersion: Int,
    val reducePromptVersion: Int,
    val finalPromptVersion: Int,
    val intermediateSchemaVersion: Int,
    val inputContentDigest: String,
    val route: String,
    val directPromptEstimatedTokens: Int,
    val status: String,
    val currentLevel: Int,
    val totalLeafChunks: Int,
    val completedLeafChunks: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "offline_summary_nodes",
    foreignKeys = [
        ForeignKey(
            entity = AiSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["aiSummaryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["aiSummaryId"]),
        Index(value = ["aiSummaryId", "level", "ordinal"], unique = true),
        Index(value = ["aiSummaryId", "status"]),
    ],
)
data class OfflineSummaryNodeEntity(
    @PrimaryKey val id: String,
    val aiSummaryId: String,
    val nodeType: String,
    val level: Int,
    val ordinal: Int,
    val sourceStartUnitOrdinal: Int,
    val sourceEndUnitOrdinalExclusive: Int,
    val inputNodeIdsJson: String,
    val sourceRefsJson: String,
    val evidenceRefsJson: String,
    val inputDigest: String,
    val estimatedPromptTokens: Int,
    val status: String,
    val attemptCount: Int,
    val validatedPayloadJson: String?,
    val errorCode: String?,
    val sanitizedErrorMessage: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
)
