package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object AiSummaryInputModeValue {
    const val TRANSCRIPT_TEXT = "TRANSCRIPT_TEXT"
    const val DIRECT_AUDIO = "DIRECT_AUDIO"
}

object AiSummaryModeValue {
    const val SMART = "SMART"
    const val PRESET = "PRESET"
    const val CUSTOM = "CUSTOM"
}

object AiSummaryStateValue {
    const val CREATED = "CREATED"
    const val PREPARING = "PREPARING"
    const val ANALYZING = "ANALYZING"
    const val PLANNING = "PLANNING"
    const val MAPPING = "MAPPING"
    const val REDUCING = "REDUCING"
    const val VALIDATING = "VALIDATING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"

    val ACTIVE =
        listOf(
            CREATED,
            PREPARING,
            ANALYZING,
            PLANNING,
            MAPPING,
            REDUCING,
            VALIDATING,
        )
}

@Entity(
    tableName = "ai_summaries",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TranscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["transcriptionId"]),
        Index(value = ["status"]),
        Index(value = ["recordingId", "createdAtMs"]),
    ],
)
data class AiSummaryEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val transcriptionId: String?,
    val inputMode: String,
    val mode: String,
    val templateId: String?,
    val templateSnapshot: String?,
    val providerProfileId: String,
    val providerNameSnapshot: String,
    val baseUrlSnapshot: String,
    val model: String,
    val promptVersion: Int,
    val resultSchemaVersion: Int,
    val contentType: String?,
    val classificationConfidence: Double?,
    val structuredPayloadJson: String?,
    val displayText: String?,
    val status: String,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorCode: String?,
    val sanitizedErrorMessage: String?,
    val requestConfigSnapshot: String,
    val usageSnapshot: String?,
    val alignmentIdSnapshot: String?,
    val sourceLineageSnapshot: String,
)

@Entity(
    tableName = "ai_custom_templates",
    indices = [
        Index(value = ["name"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class AiCustomTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val sectionsConfigJson: String,
    val focus: String?,
    val userInstruction: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "ai_summary_evidence",
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
        Index(value = ["summaryItemId"]),
        Index(value = ["aiSummaryId", "summaryItemId", "sourceRef"], unique = true),
        Index(value = ["aiSummaryId", "startSampleIndex"]),
    ],
)
data class AiSummaryEvidenceEntity(
    @PrimaryKey val id: String,
    val aiSummaryId: String,
    val summaryItemId: String,
    val sourceRef: String,
    val sourceKind: String,
    val sourceId: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val speakerId: String?,
    val assignmentQuality: String?,
)

@Entity(
    tableName = "ai_summary_chunks",
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
        Index(value = ["aiSummaryId", "level", "chunkIndex"], unique = true),
        Index(value = ["aiSummaryId", "status"]),
    ],
)
data class AiSummaryChunkEntity(
    @PrimaryKey val id: String,
    val aiSummaryId: String,
    val level: Int,
    val chunkIndex: Int,
    val sourceStartOrdinal: Int,
    val sourceEndOrdinalExclusive: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val inputDigest: String,
    val status: String,
    val attempt: Int,
    val structuredResultJson: String?,
    val errorCode: String?,
    val sanitizedErrorMessage: String?,
    val updatedAtMs: Long,
)
