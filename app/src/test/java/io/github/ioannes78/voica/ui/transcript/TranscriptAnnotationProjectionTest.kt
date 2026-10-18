package io.github.ioannes78.voica.ui.transcript

import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import io.github.ioannes78.voica.database.TimelineAnnotationOwnershipStateValue
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationUiItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAnnotationProjectionTest {
    @Test
    fun assignsRecordingOwnedSamplesToCurrentTranscriptRangesWithoutTextInference() {
        val first = projection("a", 10_000L)
        val second = projection("b", 30_000L)
        val outside = projection("c", 90_000L)
        val result =
            assignTranscriptAnnotations(
                projections = listOf(outside, second, first),
                ranges =
                    listOf(
                        TranscriptAnnotationRange("p1", 0L, 20_000L),
                        TranscriptAnnotationRange("p2", 20_000L, 40_000L),
                    ),
            )
        assertEquals(listOf("a"), result.byRangeId["p1"]?.map { it.item.annotation.id })
        assertEquals(listOf("b"), result.byRangeId["p2"]?.map { it.item.annotation.id })
        assertEquals(listOf("c"), result.unassigned.map { it.item.annotation.id })
    }

    @Test
    fun boundaryUsesEndExclusiveAndNeverGuessesIntoMissingRange() {
        val atBoundary = projection("x", 20_000L)
        val result =
            assignTranscriptAnnotations(
                projections = listOf(atBoundary),
                ranges = listOf(TranscriptAnnotationRange("p1", 0L, 20_000L)),
            )
        assertEquals(emptyMap<String, List<TranscriptAnnotationProjection>>(), result.byRangeId)
        assertEquals(listOf("x"), result.unassigned.map { it.item.annotation.id })
    }

    private fun projection(id: String, sample: Long): TranscriptAnnotationProjection =
        TranscriptAnnotationProjection(
            item =
                TimelineAnnotationUiItem(
                    annotation =
                        TimelineAnnotationEntity(
                            id = id,
                            ownershipState = TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING,
                            recordingId = "recording",
                            pendingOwnerKind = null,
                            pendingOwnerId = null,
                            anchorDomain = "CANONICAL_RECORDING",
                            anchorSampleIndex = sample,
                            createdAtMs = 1L,
                            updatedAtMs = 1L,
                        ),
                    attachments = emptyList(),
                ),
            sampleIndex = sample,
        )
}
