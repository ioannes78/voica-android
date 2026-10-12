package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object RealtimeAudioSourceTypeValue {
    const val RECORDER_CARD = "RECORDER_CARD"
    const val PHONE_MICROPHONE = "PHONE_MICROPHONE"
}

object RealtimeTranscriptionStateValue {
    const val ACTIVE = "ACTIVE"
    const val FINALIZING = "FINALIZING"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED = "INTERRUPTED"
    const val CANCELLED = "CANCELLED"
    const val FAILED = "FAILED"
}

object RealtimeMediaSegmentStateValue {
    const val ACTIVE = "ACTIVE"
    const val FINALIZED = "FINALIZED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
}

object RealtimeTimelineMarkerTypeValue {
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val DISCONTINUITY = "DISCONTINUITY"
    const val TRANSPORT_CLOSED = "TRANSPORT_CLOSED"
    const val RECONNECTED = "RECONNECTED"
}

@Entity(
    tableName = "realtime_transcription_runs",
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
        Index(value = ["deviceRecordingIdentity"]),
        Index(value = ["state"]),
        Index(value = ["startedAtMs"]),
    ],
)
data class RealtimeTranscriptionRunEntity(
    @PrimaryKey val id: String,
    val recordingId: String?,
    val deviceRecordingIdentity: String?,
    val audioSourceType: String,
    val state: String,
    val modelId: String,
    val modelVersion: String,
    val modelRevision: Long,
    val runtimeId: String,
    val runtimeVersion: String,
    val vadModelId: String?,
    val vadModelVersion: String?,
    val vadModelRevision: Long?,
    val punctuationModelId: String?,
    val punctuationModelVersion: String?,
    val punctuationModelRevision: Long?,
    val languageConfig: String,
    val configSnapshotSchemaVersion: Int,
    val requestedConfigSnapshot: String,
    val effectiveConfigSnapshot: String,
    val modelManifestDigest: String,
    val createdAtMs: Long,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long?,
    val endReason: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Entity(
    tableName = "realtime_media_segments",
    foreignKeys = [
        ForeignKey(
            entity = RealtimeTranscriptionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["runId", "segmentIndex"], unique = true),
        Index(value = ["runId", "canonicalSessionId"], unique = true),
        Index(value = ["state"]),
    ],
)
data class RealtimeMediaSegmentEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val segmentIndex: Int,
    val transportGeneration: Long?,
    val canonicalSessionId: Long,
    val state: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val endReason: String?,
    val canonicalSampleCount: Long,
    val localRelativePath: String?,
    val localFilename: String?,
    val deviceFilename: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val isCompleteDeviceRecording: Boolean,
)

@Entity(
    tableName = "realtime_utterances",
    foreignKeys = [
        ForeignKey(
            entity = RealtimeMediaSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["segmentId"]),
        Index(value = ["segmentId", "utteranceIndex"], unique = true),
        Index(value = ["segmentId", "canonicalEpochId", "startSampleIndex"]),
    ],
)
data class RealtimeUtteranceEntity(
    @PrimaryKey val id: String,
    val segmentId: String,
    val utteranceIndex: Int,
    val canonicalEpochId: Long,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val rawFinalText: String,
    val finalText: String,
    val detectedLanguage: String?,
    val createdAtMs: Long,
)

@Entity(
    tableName = "realtime_timeline_markers",
    foreignKeys = [
        ForeignKey(
            entity = RealtimeTranscriptionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RealtimeMediaSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["segmentId"]),
        Index(value = ["runId", "markerIndex"], unique = true),
        Index(value = ["type"]),
    ],
)
data class RealtimeTimelineMarkerEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val segmentId: String?,
    val markerIndex: Int,
    val type: String,
    val canonicalSessionId: Long?,
    val canonicalEpochId: Long?,
    val sampleIndex: Long?,
    val createdAtMs: Long,
    val detail: String?,
)
