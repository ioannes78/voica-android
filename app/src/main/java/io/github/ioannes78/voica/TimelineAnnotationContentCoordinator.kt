package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.TimelineAnnotationRepository

data class TimelineAnnotationDeleteResult(
    val deleted: Boolean,
    val failedPaths: List<String> = emptyList(),
)

/** Coordinates DB ownership with physical attachment cleanup for one user annotation. */
class TimelineAnnotationContentCoordinator(
    private val repository: TimelineAnnotationRepository,
    private val attachmentStore: TimelineAnnotationAttachmentStore,
) {
    suspend fun delete(annotationId: String): TimelineAnnotationDeleteResult {
        require(annotationId.isNotBlank())
        if (repository.get(annotationId) == null) {
            return TimelineAnnotationDeleteResult(deleted = true)
        }
        val failed = attachmentStore.deleteAttachmentsForAnnotation(annotationId)
        if (failed.isNotEmpty()) {
            return TimelineAnnotationDeleteResult(
                deleted = false,
                failedPaths = failed,
            )
        }
        return TimelineAnnotationDeleteResult(
            deleted = repository.delete(annotationId),
        )
    }
}
