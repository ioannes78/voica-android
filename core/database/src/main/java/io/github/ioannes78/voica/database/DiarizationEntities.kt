package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object DiarizationStateValue {
    const val PREPARING = "PREPARING"
    const val VAD_ANALYZING = "VAD_ANALYZING"
    const val DIARIZING = "DIARIZING"
    const val STITCHING = "STITCHING"
    const val ALIGNING = "ALIGNING"
    const val PERSISTING = "PERSISTING"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED_RECOVERABLE = "FAILED_RECOVERABLE"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"

    val ACTIVE = listOf(
        PREPARING,
        VAD_ANALYZING,
        DIARIZING,
        STITCHING,
        ALIGNING,
        PERSISTING,
    )
}

object TranscriptSpeakerAlignmentStateValue {
    const val PREPARING = "PREPARING"
    const val ALIGNING = "ALIGNING"
    const val PERSISTING = "PERSISTING"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED_RECOVERABLE = "FAILED_RECOVERABLE"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"

    val ACTIVE = listOf(
        PREPARING,
        ALIGNING,
        PERSISTING,
    )
}

object SpeakerAssignmentQualityValue {
    const val ASSIGNED = "ASSIGNED"
    const val ASSIGNED_WITH_OVERLAP = "ASSIGNED_WITH_OVERLAP"
    const val OVERLAP_AMBIGUOUS = "OVERLAP_AMBIGUOUS"
    const val UNRESOLVED = "UNRESOLVED"
}

@Entity(
    tableName = "diarization_runs",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["state"]),
        Index(value = ["recordingId", "createdAtMs"]),
    ],
)
data class DiarizationRunEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val state: String,
    val sourceCanonicalAssetId: String,
    val sourceCanonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val pipelineVersion: Int,
    val runtimeId: String,
    val runtimeVersion: String,
    val vadModelId: String,
    val vadModelVersion: String,
    val vadModelRevision: Long,
    val segmentationModelId: String,
    val segmentationModelVersion: String,
    val segmentationModelRevision: Long,
    val embeddingModelId: String,
    val embeddingModelVersion: String,
    val embeddingModelRevision: Long,
    val modelManifestDigest: String,
    val configSnapshot: String,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Entity(
    tableName = "diarization_speakers",
    foreignKeys = [
        ForeignKey(
            entity = DiarizationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["diarizationRunId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["diarizationRunId"]),
        Index(value = ["diarizationRunId", "speakerOrdinal"], unique = true),
    ],
)
data class DiarizationSpeakerEntity(
    @PrimaryKey val id: String,
    val diarizationRunId: String,
    val speakerOrdinal: Int,
    val displayName: String?,
)

@Entity(
    tableName = "speaker_turns",
    foreignKeys = [
        ForeignKey(
            entity = DiarizationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["diarizationRunId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DiarizationSpeakerEntity::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["diarizationRunId"]),
        Index(value = ["speakerId"]),
        Index(value = ["diarizationRunId", "turnIndex"], unique = true),
        Index(value = ["diarizationRunId", "startSampleIndex"]),
    ],
)
data class SpeakerTurnEntity(
    @PrimaryKey val id: String,
    val diarizationRunId: String,
    val turnIndex: Int,
    val speakerId: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val confidence: Float?,
    val overlap: Boolean,
    val overlapMetadata: String?,
)

@Entity(
    tableName = "transcript_speaker_alignments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DiarizationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["diarizationRunId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["transcriptionId"]),
        Index(value = ["diarizationRunId"]),
        Index(value = ["transcriptionId", "diarizationRunId"]),
        Index(value = ["state"]),
    ],
)
data class TranscriptSpeakerAlignmentEntity(
    @PrimaryKey val id: String,
    val transcriptionId: String,
    val diarizationRunId: String,
    val alignmentVersion: Int,
    val configSnapshot: String,
    val state: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Entity(
    tableName = "transcript_speaker_spans",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptSpeakerAlignmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["alignmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TranscriptSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceTranscriptSegmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DiarizationSpeakerEntity::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["alignmentId"]),
        Index(value = ["sourceTranscriptSegmentId"]),
        Index(value = ["speakerId"]),
        Index(value = ["alignmentId", "spanIndex"], unique = true),
    ],
)
data class TranscriptSpeakerSpanEntity(
    @PrimaryKey val id: String,
    val alignmentId: String,
    val spanIndex: Int,
    val sourceTranscriptSegmentId: String,
    val speakerId: String?,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val tokenSource: String?,
    val tokenStartIndex: Int?,
    val tokenEndIndexExclusive: Int?,
    val finalTextStartOffset: Int,
    val finalTextEndOffsetExclusive: Int,
    val assignmentQuality: String,
    val overlap: Boolean,
    val ambiguous: Boolean,
)
