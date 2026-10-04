package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object TranscriptionModeValue {
    const val FAST = "FAST"
    const val HIGH_QUALITY = "HIGH_QUALITY"
}

object TranscriptionStateValue {
    const val PREPARING = "PREPARING"
    const val VAD_ANALYZING = "VAD_ANALYZING"
    const val FIRST_PASS_TRANSCRIBING = "FIRST_PASS_TRANSCRIBING"
    const val SECOND_PASS_TRANSCRIBING = "SECOND_PASS_TRANSCRIBING"
    const val PUNCTUATING = "PUNCTUATING"
    const val PERSISTING = "PERSISTING"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED_RECOVERABLE = "FAILED_RECOVERABLE"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"

    val ACTIVE = listOf(
        PREPARING,
        VAD_ANALYZING,
        FIRST_PASS_TRANSCRIBING,
        SECOND_PASS_TRANSCRIBING,
        PUNCTUATING,
        PERSISTING,
    )
}

object TranscriptTokenSourceValue {
    const val FIRST_PASS = "FIRST_PASS"
    const val SECOND_PASS = "SECOND_PASS"
}

@Entity(
    tableName = "transcriptions",
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
data class TranscriptionEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val mode: String,
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
    val firstPassAsrModelId: String,
    val firstPassAsrModelVersion: String,
    val secondPassAsrModelId: String?,
    val secondPassAsrModelVersion: String?,
    val punctuationModelId: String?,
    val punctuationModelVersion: String?,
    val languageConfig: String,
    val configSnapshot: String,
    val modelManifestDigest: String,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val vadModelRevision: Long = 1L,
    val firstPassAsrModelRevision: Long = 1L,
    val secondPassAsrModelRevision: Long? = null,
    val punctuationModelRevision: Long? = null,
    val configSnapshotSchemaVersion: Int = 1,
    val requestedConfigSnapshot: String = configSnapshot,
    val effectiveConfigSnapshot: String = configSnapshot,
)

@Entity(
    tableName = "transcript_segments",
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
        Index(value = ["transcriptionId", "segmentIndex"], unique = true),
    ],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val transcriptionId: String,
    val segmentIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val firstPassRawText: String,
    val secondPassRawText: String?,
    val finalText: String,
    val detectedLanguage: String?,
    val confidence: Float?,
)

@Entity(
    tableName = "transcript_tokens",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptSegmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["transcriptSegmentId"]),
        Index(
            value = ["transcriptSegmentId", "source", "tokenIndex"],
            unique = true,
        ),
    ],
)
data class TranscriptTokenEntity(
    @PrimaryKey val id: String,
    val transcriptSegmentId: String,
    val tokenIndex: Int,
    val text: String,
    val startSampleIndex: Long?,
    val endSampleIndexExclusive: Long?,
    val source: String,
)
