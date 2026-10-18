package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object TimelineAnnotationOwnershipStateValue {
    const val PENDING_SESSION = "PENDING_SESSION"
    const val ATTACHED_RECORDING = "ATTACHED_RECORDING"
}

object TimelineAnnotationPendingOwnerKindValue {
    const val PHONE_CAPTURE = "PHONE_CAPTURE"
    const val RECORDER_CAPTURE = "RECORDER_CAPTURE"
    const val REALTIME_RUN = "REALTIME_RUN"
}

data class RecorderCapturePendingIdentity(
    val deviceAddress: String,
    val filename: String,
)

object RecorderCapturePendingOwnerId {
    private const val SEPARATOR = "\u001f"

    fun encode(deviceAddress: String, filename: String): String {
        require(deviceAddress.isNotBlank())
        require(filename.isNotBlank())
        require(SEPARATOR !in deviceAddress && SEPARATOR !in filename)
        return deviceAddress.trim() + SEPARATOR + filename.trim()
    }

    fun decode(value: String): RecorderCapturePendingIdentity? {
        val index = value.indexOf(SEPARATOR)
        if (index <= 0 || index >= value.lastIndex) return null
        val address = value.substring(0, index).trim()
        val filename = value.substring(index + SEPARATOR.length).trim()
        if (address.isBlank() || filename.isBlank()) return null
        return RecorderCapturePendingIdentity(address, filename)
    }
}

object TimelineAnnotationAnchorDomainValue {
    const val CANONICAL_RECORDING = "CANONICAL_RECORDING"
    const val REALTIME_MEDIA_SEGMENT = "REALTIME_MEDIA_SEGMENT"
}

object TimelineAnnotationAttachmentKindValue {
    const val PHOTO = "PHOTO"
}

object TimelineAnnotationAttachmentSourceValue {
    const val CAMERA = "CAMERA"
    const val GALLERY = "GALLERY"
}

object TimelineAnnotationAttachmentLifecycleStateValue {
    const val READY = "READY"
    const val DELETING = "DELETING"
}

/**
 * Recording-owned user annotation anchored to durable audio sample coordinates.
 *
 * A row may temporarily use PENDING_SESSION while the active capture already has a durable session
 * identity but its final Recording row has not yet materialized. Transcript/diarization identifiers
 * below are snapshots only: they are deliberately not foreign keys and never own the annotation.
 */
@Entity(
    tableName = "timeline_annotations",
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
        Index(value = ["recordingId", "anchorDomain", "anchorSampleIndex"]),
        Index(value = ["ownershipState"]),
        Index(value = ["pendingOwnerKind", "pendingOwnerId"]),
        Index(value = ["recordingId", "createdAtMs"]),
        Index(value = ["recordingId", "anchorRealtimeSegmentIdSnapshot", "anchorSampleIndex"]),
    ],
)
data class TimelineAnnotationEntity(
    @PrimaryKey val id: String,
    val recordingId: String?,
    val ownershipState: String,
    val pendingOwnerKind: String?,
    val pendingOwnerId: String?,
    val anchorDomain: String,
    val anchorSampleIndex: Long,
    val anchorCanonicalAssetIdSnapshot: String?,
    val anchorCanonicalSha256Snapshot: String?,
    val anchorCanonicalProfileIdSnapshot: String?,
    val anchorRealtimeRunIdSnapshot: String?,
    val anchorRealtimeSegmentIdSnapshot: String?,
    val anchorRealtimeSegmentIndexSnapshot: Int?,
    val anchorCanonicalSessionIdSnapshot: Long?,
    val anchorCanonicalEpochIdSnapshot: Long?,
    val noteText: String?,
    val sourceTextSnapshot: String?,
    val sourceTranscriptionIdSnapshot: String?,
    val sourceRevisionIdSnapshot: String?,
    val sourceTranscriptSegmentIdSnapshot: String?,
    val sourceTimingQualitySnapshot: String?,
    val speakerLabelSnapshot: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "timeline_annotation_attachments",
    foreignKeys = [
        ForeignKey(
            entity = TimelineAnnotationEntity::class,
            parentColumns = ["id"],
            childColumns = ["annotationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["annotationId"]),
        Index(value = ["lifecycleState"]),
    ],
)
data class TimelineAnnotationAttachmentEntity(
    @PrimaryKey val id: String,
    val annotationId: String,
    val kind: String,
    val source: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val lifecycleState: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
