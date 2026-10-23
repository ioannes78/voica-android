package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
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
    const val AMBIGUOUS_REMOTE_RESULT = "AMBIGUOUS_REMOTE_RESULT"

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

/** Temporary source-compatibility shim for the v15 schema capture commit only. */
object AiSummaryRemoteDispatchStateValue {
    const val NONE = "NONE"
    const val READY_TO_SEND = "READY_TO_SEND"
    const val REQUEST_IN_FLIGHT = "REQUEST_IN_FLIGHT"

    val ALL = setOf(NONE, READY_TO_SEND, REQUEST_IN_FLIGHT)
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
    val alignmentIdSnapshot: String?,
    val sourceLineageSnapshot: String,
) {
    /**
     * Transitional constructor used only while the Room v15 schema is captured from CI.
     * Provider/remote execution arguments are intentionally not persisted in v15.
     */
    @Ignore
    @Suppress("UNUSED_PARAMETER")
    constructor(
        id: String,
        recordingId: String,
        transcriptionId: String?,
        inputMode: String,
        mode: String,
        templateId: String?,
        templateSnapshot: String?,
        providerProfileId: String,
        providerNameSnapshot: String,
        baseUrlSnapshot: String,
        model: String,
        promptVersion: Int,
        resultSchemaVersion: Int,
        contentType: String?,
        classificationConfidence: Double?,
        structuredPayloadJson: String?,
        displayText: String?,
        status: String,
        createdAtMs: Long,
        startedAtMs: Long?,
        updatedAtMs: Long,
        completedAtMs: Long?,
        errorCode: String?,
        sanitizedErrorMessage: String?,
        requestConfigSnapshot: String,
        usageSnapshot: String?,
        alignmentIdSnapshot: String?,
        sourceLineageSnapshot: String,
        executionGeneration: Long = 0L,
        ownerTaskId: Int? = null,
        remoteDispatchState: String = AiSummaryRemoteDispatchStateValue.NONE,
        remoteRequestId: String? = null,
        remoteStepKind: String? = null,
        remoteStepKey: String? = null,
        remoteCallOrdinal: Int = 0,
        remoteStartedAtMs: Long? = null,
        terminalAcknowledgedAtMs: Long? = null,
        retryOfSummaryId: String? = null,
    ) : this(
        id = id,
        recordingId = recordingId,
        transcriptionId = transcriptionId,
        inputMode = inputMode,
        mode = mode,
        model = model,
        promptVersion = promptVersion,
        resultSchemaVersion = resultSchemaVersion,
        contentType = contentType,
        classificationConfidence = classificationConfidence,
        structuredPayloadJson = structuredPayloadJson,
        displayText = displayText,
        status = status,
        createdAtMs = createdAtMs,
        startedAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        completedAtMs = completedAtMs,
        errorCode = errorCode,
        sanitizedErrorMessage = sanitizedErrorMessage,
        alignmentIdSnapshot = alignmentIdSnapshot,
        sourceLineageSnapshot = sourceLineageSnapshot,
    )
}

/** Retained temporarily for source compatibility; no longer registered in VoicaDatabase v15. */
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

/** Retained temporarily for source compatibility; no longer registered in VoicaDatabase v15. */
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
