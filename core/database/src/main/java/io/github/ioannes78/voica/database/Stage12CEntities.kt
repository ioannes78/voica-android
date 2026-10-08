package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object TranscriptRevisionTimingQualityValue {
    const val EXACT = "EXACT"
    const val ANCHORED = "ANCHORED"
    const val APPROXIMATE = "APPROXIMATE"
    val ALL = setOf(EXACT, ANCHORED, APPROXIMATE)
}

object TranscriptRevisionSpeakerDisplayModeValue {
    const val INHERIT = "INHERIT"
    const val SHOW = "SHOW"
    const val HIDE = "HIDE"
    val ALL = setOf(INHERIT, SHOW, HIDE)
}

@Entity(
    tableName = "transcription_user_metadata",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["currentRevisionId"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class TranscriptionUserMetadataEntity(
    @PrimaryKey val transcriptionId: String,
    val displayName: String?,
    val currentRevisionId: String?,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "transcription_revisions",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["transcriptionId"]),
        Index(value = ["transcriptionId", "revisionNumber"], unique = true),
        Index(value = ["createdAtMs"]),
    ],
)
data class TranscriptionRevisionEntity(
    @PrimaryKey val id: String,
    val transcriptionId: String,
    val revisionNumber: Int,
    val parentRevisionId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "transcription_revision_paragraphs",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["revisionId"]),
        Index(value = ["revisionId", "paragraphIndex"], unique = true),
        Index(value = ["anchorStartSampleIndex"]),
    ],
)
data class TranscriptionRevisionParagraphEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val paragraphIndex: Int,
    val text: String,
    val sourceAnchorRefsJson: String,
    val anchorStartSampleIndex: Long?,
    val anchorEndSampleIndexExclusive: Long?,
    val speakerId: String?,
    val speakerDisplayMode: String,
    val timingQuality: String,
    val isUserModified: Boolean,
)

@Entity(
    tableName = "ai_summary_user_metadata",
    foreignKeys = [
        ForeignKey(
            entity = AiSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["aiSummaryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["currentRevisionId"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class AiSummaryUserMetadataEntity(
    @PrimaryKey val aiSummaryId: String,
    val currentRevisionId: String?,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "ai_summary_revisions",
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
        Index(value = ["aiSummaryId", "revisionNumber"], unique = true),
        Index(value = ["createdAtMs"]),
    ],
)
data class AiSummaryRevisionEntity(
    @PrimaryKey val id: String,
    val aiSummaryId: String,
    val revisionNumber: Int,
    val parentRevisionId: String?,
    val revisionSchemaVersion: Int,
    val revisionPayloadJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "recording_content_selection",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["currentTranscriptionId"]),
        Index(value = ["currentAiSummaryId"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class RecordingContentSelectionEntity(
    @PrimaryKey val recordingId: String,
    val currentTranscriptionId: String?,
    val currentAiSummaryId: String?,
    val updatedAtMs: Long,
    val dismissedTranscriptionCandidateId: String? = null,
    val dismissedAiSummaryCandidateId: String? = null,
)
