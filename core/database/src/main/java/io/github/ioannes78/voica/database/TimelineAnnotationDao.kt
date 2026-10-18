package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineAnnotationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnnotation(annotation: TimelineAnnotationEntity)

    @Update
    suspend fun updateAnnotation(annotation: TimelineAnnotationEntity)

    @Query("SELECT * FROM timeline_annotations WHERE id = :annotationId LIMIT 1")
    suspend fun getAnnotation(annotationId: String): TimelineAnnotationEntity?

    @Query(
        """
        SELECT * FROM timeline_annotations
        WHERE recordingId = :recordingId
          AND ownershipState = 'ATTACHED_RECORDING'
        ORDER BY anchorSampleIndex ASC, createdAtMs ASC
        """,
    )
    fun observeAnnotationsForRecording(recordingId: String): Flow<List<TimelineAnnotationEntity>>

    @Query(
        """
        SELECT * FROM timeline_annotations
        WHERE ownershipState = 'PENDING_SESSION'
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun getAllPendingAnnotations(): List<TimelineAnnotationEntity>

    @Query(
        """
        SELECT * FROM timeline_annotations
        WHERE ownershipState = 'PENDING_SESSION'
          AND pendingOwnerKind = :ownerKind
          AND pendingOwnerId = :ownerId
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun getPendingAnnotations(ownerKind: String, ownerId: String): List<TimelineAnnotationEntity>

    @Query(
        """
        UPDATE timeline_annotations
        SET recordingId = :recordingId,
            ownershipState = 'ATTACHED_RECORDING',
            pendingOwnerKind = NULL,
            pendingOwnerId = NULL,
            updatedAtMs = :updatedAtMs
        WHERE id = :annotationId
          AND ownershipState = 'PENDING_SESSION'
        """,
    )
    suspend fun attachToRecording(annotationId: String, recordingId: String, updatedAtMs: Long): Int

    @Query("DELETE FROM timeline_annotations WHERE id = :annotationId")
    suspend fun deleteAnnotation(annotationId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachment(attachment: TimelineAnnotationAttachmentEntity)

    @Update
    suspend fun updateAttachment(attachment: TimelineAnnotationAttachmentEntity)

    @Query("SELECT * FROM timeline_annotation_attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun getAttachment(attachmentId: String): TimelineAnnotationAttachmentEntity?

    @Query(
        """
        SELECT * FROM timeline_annotation_attachments
        WHERE annotationId = :annotationId
        ORDER BY createdAtMs ASC
        """,
    )
    fun observeAttachments(annotationId: String): Flow<List<TimelineAnnotationAttachmentEntity>>

    @Query(
        """
        SELECT * FROM timeline_annotation_attachments
        WHERE annotationId = :annotationId
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun getAttachments(annotationId: String): List<TimelineAnnotationAttachmentEntity>

    @Query(
        """
        SELECT * FROM timeline_annotation_attachments
        WHERE lifecycleState = 'DELETING'
        ORDER BY updatedAtMs ASC
        """,
    )
    suspend fun getDeletingAttachments(): List<TimelineAnnotationAttachmentEntity>

    @Query("DELETE FROM timeline_annotation_attachments WHERE id = :attachmentId")
    suspend fun deleteAttachment(attachmentId: String): Int
}
