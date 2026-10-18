package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface TimelineAnnotationOwner {
    data class Recording(val recordingId: String) : TimelineAnnotationOwner

    data class Pending(
        val kind: String,
        val ownerId: String,
    ) : TimelineAnnotationOwner
}

data class TimelineAnnotationAnchor(
    val domain: String,
    val sampleIndex: Long,
    val canonicalAssetIdSnapshot: String? = null,
    val canonicalSha256Snapshot: String? = null,
    val canonicalProfileIdSnapshot: String? = null,
    val realtimeRunIdSnapshot: String? = null,
    val realtimeSegmentIdSnapshot: String? = null,
    val realtimeSegmentIndexSnapshot: Int? = null,
    val canonicalSessionIdSnapshot: Long? = null,
    val canonicalEpochIdSnapshot: Long? = null,
)

data class TimelineAnnotationProvenance(
    val sourceTextSnapshot: String? = null,
    val sourceTranscriptionIdSnapshot: String? = null,
    val sourceRevisionIdSnapshot: String? = null,
    val sourceTranscriptSegmentIdSnapshot: String? = null,
    val sourceTimingQualitySnapshot: String? = null,
    val speakerLabelSnapshot: String? = null,
)

data class PendingTimelineAnnotationReconciliation(
    val attachedCount: Int,
    val unresolvedCount: Int,
)

/**
 * Durable Stage 16A.2 annotation owner.
 *
 * Product code creates user annotations through this repository rather than constructing Room rows
 * directly. That keeps the temporary PENDING_SESSION state strict and prevents transcript,
 * diarization, or realtime transport state from becoming hidden ownership authorities.
 */
class TimelineAnnotationRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val annotationDao = database.timelineAnnotationDao()
    private val recordingDao = database.recordingDao()
    private val realtimeDao = database.realtimeTranscriptionDao()

    fun observeRecording(recordingId: String): Flow<List<TimelineAnnotationEntity>> {
        require(recordingId.isNotBlank()) { "recordingId must not be blank" }
        return annotationDao.observeAnnotationsForRecording(recordingId)
    }

    fun observeAttachments(annotationId: String): Flow<List<TimelineAnnotationAttachmentEntity>> {
        require(annotationId.isNotBlank()) { "annotationId must not be blank" }
        return annotationDao.observeAttachments(annotationId)
    }

    suspend fun create(
        owner: TimelineAnnotationOwner,
        anchor: TimelineAnnotationAnchor,
        noteText: String? = null,
        provenance: TimelineAnnotationProvenance = TimelineAnnotationProvenance(),
    ): TimelineAnnotationEntity {
        validateAnchor(anchor)
        val normalizedNote = noteText?.trim()?.takeIf { it.isNotEmpty() }
        val now = nowMs()
        val entity =
            when (owner) {
                is TimelineAnnotationOwner.Recording -> {
                    require(owner.recordingId.isNotBlank()) { "recordingId must not be blank" }
                    require(recordingDao.isRecordingActive(owner.recordingId)) {
                        "annotation recording must exist and be active"
                    }
                    entity(
                        id = idFactory(),
                        recordingId = owner.recordingId,
                        ownershipState = TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING,
                        pendingOwnerKind = null,
                        pendingOwnerId = null,
                        anchor = anchor,
                        noteText = normalizedNote,
                        provenance = provenance,
                        now = now,
                    )
                }

                is TimelineAnnotationOwner.Pending -> {
                    validatePendingOwner(owner.kind, owner.ownerId)
                    entity(
                        id = idFactory(),
                        recordingId = null,
                        ownershipState = TimelineAnnotationOwnershipStateValue.PENDING_SESSION,
                        pendingOwnerKind = owner.kind,
                        pendingOwnerId = owner.ownerId,
                        anchor = anchor,
                        noteText = normalizedNote,
                        provenance = provenance,
                        now = now,
                    )
                }
            }
        annotationDao.insertAnnotation(entity)
        return entity
    }

    suspend fun get(annotationId: String): TimelineAnnotationEntity? {
        require(annotationId.isNotBlank()) { "annotationId must not be blank" }
        return annotationDao.getAnnotation(annotationId)
    }

    suspend fun updateNote(annotationId: String, noteText: String?): TimelineAnnotationEntity {
        val current = requireAnnotation(annotationId)
        val updated =
            current.copy(
                noteText = noteText?.trim()?.takeIf { it.isNotEmpty() },
                updatedAtMs = nowMs(),
            )
        annotationDao.updateAnnotation(updated)
        return updated
    }

    suspend fun moveAnchor(
        annotationId: String,
        anchor: TimelineAnnotationAnchor,
    ): TimelineAnnotationEntity {
        validateAnchor(anchor)
        val current = requireAnnotation(annotationId)
        val updated =
            current.copy(
                anchorDomain = anchor.domain,
                anchorSampleIndex = anchor.sampleIndex,
                anchorCanonicalAssetIdSnapshot = anchor.canonicalAssetIdSnapshot,
                anchorCanonicalSha256Snapshot = anchor.canonicalSha256Snapshot,
                anchorCanonicalProfileIdSnapshot = anchor.canonicalProfileIdSnapshot,
                anchorRealtimeRunIdSnapshot = anchor.realtimeRunIdSnapshot,
                anchorRealtimeSegmentIdSnapshot = anchor.realtimeSegmentIdSnapshot,
                anchorRealtimeSegmentIndexSnapshot = anchor.realtimeSegmentIndexSnapshot,
                anchorCanonicalSessionIdSnapshot = anchor.canonicalSessionIdSnapshot,
                anchorCanonicalEpochIdSnapshot = anchor.canonicalEpochIdSnapshot,
                updatedAtMs = nowMs(),
            )
        annotationDao.updateAnnotation(updated)
        return updated
    }

    suspend fun delete(annotationId: String): Boolean {
        require(annotationId.isNotBlank()) { "annotationId must not be blank" }
        return annotationDao.deleteAnnotation(annotationId) == 1
    }

    /**
     * Promotes all annotations for one durable pending owner after the Recording row materializes.
     * Realtime runs must already be attached by the existing realtime lifecycle before this method
     * may promote their user annotations.
     */
    suspend fun attachPendingOwner(
        ownerKind: String,
        ownerId: String,
        recordingId: String,
        updatedAtMs: Long = nowMs(),
    ): Int =
        database.withTransaction {
            validatePendingOwner(ownerKind, ownerId)
            require(recordingId.isNotBlank()) { "recordingId must not be blank" }
            require(recordingDao.isRecordingActive(recordingId)) {
                "target recording must exist and be active"
            }
            when (ownerKind) {
                TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ->
                    require(ownerId == recordingId) {
                        "phone capture pending owner must promote to its future recordingId"
                    }

                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN -> {
                    val run = realtimeDao.getRun(ownerId)
                        ?: throw IllegalArgumentException("realtime pending owner does not exist")
                    require(run.recordingId == recordingId) {
                        "realtime run must be attached to the target recording before annotation promotion"
                    }
                }
            }
            annotationDao.getPendingAnnotations(ownerKind, ownerId).sumOf { annotation ->
                annotationDao.attachToRecording(annotation.id, recordingId, updatedAtMs)
            }
        }

    /** Startup reconciliation is deliberately conservative: unresolved owners are preserved. */
    suspend fun reconcilePending(): PendingTimelineAnnotationReconciliation =
        database.withTransaction {
            val pending = annotationDao.getAllPendingAnnotations()
            var attached = 0
            var unresolved = 0
            pending.groupBy { it.pendingOwnerKind to it.pendingOwnerId }.forEach { (owner, annotations) ->
                val kind = owner.first
                val ownerId = owner.second
                val targetRecordingId =
                    when {
                        kind == null || ownerId.isNullOrBlank() -> null
                        kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE &&
                            recordingDao.isRecordingActive(ownerId) -> ownerId
                        kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN ->
                            realtimeDao.getRun(ownerId)?.recordingId?.takeIf {
                                recordingDao.isRecordingActive(it)
                            }
                        else -> null
                    }
                if (targetRecordingId == null) {
                    unresolved += annotations.size
                } else {
                    annotations.forEach { annotation ->
                        attached += annotationDao.attachToRecording(
                            annotationId = annotation.id,
                            recordingId = targetRecordingId,
                            updatedAtMs = nowMs(),
                        )
                    }
                }
            }
            PendingTimelineAnnotationReconciliation(attached, unresolved)
        }

    suspend fun addAttachment(attachment: TimelineAnnotationAttachmentEntity) {
        require(attachment.id.isNotBlank()) { "attachment id must not be blank" }
        require(attachment.annotationId.isNotBlank()) { "annotationId must not be blank" }
        require(attachment.kind == TimelineAnnotationAttachmentKindValue.PHOTO) {
            "Stage 16A.2 supports photo attachments only"
        }
        require(
            attachment.source == TimelineAnnotationAttachmentSourceValue.CAMERA ||
                attachment.source == TimelineAnnotationAttachmentSourceValue.GALLERY,
        ) { "unsupported annotation attachment source" }
        require(attachment.relativePath.isNotBlank()) { "attachment path must not be blank" }
        require(attachment.mimeType.startsWith("image/")) { "attachment must be an image" }
        require(attachment.sizeBytes > 0L) { "attachment must not be empty" }
        require(attachment.sha256.isNotBlank()) { "attachment sha256 must not be blank" }
        require(
            attachment.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.READY ||
                attachment.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.DELETING,
        ) { "unsupported attachment lifecycle state" }
        requireAnnotation(attachment.annotationId)
        annotationDao.insertAttachment(attachment)
    }

    suspend fun getAttachments(annotationId: String): List<TimelineAnnotationAttachmentEntity> =
        annotationDao.getAttachments(annotationId)

    private suspend fun requireAnnotation(annotationId: String): TimelineAnnotationEntity {
        require(annotationId.isNotBlank()) { "annotationId must not be blank" }
        return annotationDao.getAnnotation(annotationId)
            ?: throw IllegalArgumentException("annotation does not exist")
    }

    private fun validatePendingOwner(kind: String, ownerId: String) {
        require(ownerId.isNotBlank()) { "pending owner id must not be blank" }
        require(
            kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ||
                kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
        ) { "unsupported pending owner kind" }
    }

    private fun validateAnchor(anchor: TimelineAnnotationAnchor) {
        require(anchor.sampleIndex >= 0L) { "annotation sample index must be non-negative" }
        when (anchor.domain) {
            TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING -> {
                require(anchor.realtimeSegmentIdSnapshot == null) {
                    "canonical recording anchor must not claim realtime segment ownership"
                }
                require(anchor.realtimeSegmentIndexSnapshot == null) {
                    "canonical recording anchor must not claim realtime segment index"
                }
            }

            TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT -> {
                require(!anchor.realtimeRunIdSnapshot.isNullOrBlank()) {
                    "realtime segment anchor requires run identity"
                }
                require(!anchor.realtimeSegmentIdSnapshot.isNullOrBlank()) {
                    "realtime segment anchor requires segment identity"
                }
                require((anchor.realtimeSegmentIndexSnapshot ?: -1) >= 0) {
                    "realtime segment anchor requires non-negative segment index"
                }
                require(anchor.canonicalSessionIdSnapshot != null) {
                    "realtime segment anchor requires canonical session identity"
                }
            }

            else -> throw IllegalArgumentException("unsupported annotation anchor domain")
        }
    }

    private fun entity(
        id: String,
        recordingId: String?,
        ownershipState: String,
        pendingOwnerKind: String?,
        pendingOwnerId: String?,
        anchor: TimelineAnnotationAnchor,
        noteText: String?,
        provenance: TimelineAnnotationProvenance,
        now: Long,
    ) = TimelineAnnotationEntity(
        id = id,
        recordingId = recordingId,
        ownershipState = ownershipState,
        pendingOwnerKind = pendingOwnerKind,
        pendingOwnerId = pendingOwnerId,
        anchorDomain = anchor.domain,
        anchorSampleIndex = anchor.sampleIndex,
        anchorCanonicalAssetIdSnapshot = anchor.canonicalAssetIdSnapshot,
        anchorCanonicalSha256Snapshot = anchor.canonicalSha256Snapshot,
        anchorCanonicalProfileIdSnapshot = anchor.canonicalProfileIdSnapshot,
        anchorRealtimeRunIdSnapshot = anchor.realtimeRunIdSnapshot,
        anchorRealtimeSegmentIdSnapshot = anchor.realtimeSegmentIdSnapshot,
        anchorRealtimeSegmentIndexSnapshot = anchor.realtimeSegmentIndexSnapshot,
        anchorCanonicalSessionIdSnapshot = anchor.canonicalSessionIdSnapshot,
        anchorCanonicalEpochIdSnapshot = anchor.canonicalEpochIdSnapshot,
        noteText = noteText,
        sourceTextSnapshot = provenance.sourceTextSnapshot,
        sourceTranscriptionIdSnapshot = provenance.sourceTranscriptionIdSnapshot,
        sourceRevisionIdSnapshot = provenance.sourceRevisionIdSnapshot,
        sourceTranscriptSegmentIdSnapshot = provenance.sourceTranscriptSegmentIdSnapshot,
        sourceTimingQualitySnapshot = provenance.sourceTimingQualitySnapshot,
        speakerLabelSnapshot = provenance.speakerLabelSnapshot,
        createdAtMs = now,
        updatedAtMs = now,
    )
}
