package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.transcript.RealtimeProcessedCanonicalBoundary

internal enum class RealtimeAnnotationCaptureSource {
    RECORDER_CARD,
    PHONE_MICROPHONE,
}

internal data class RealtimeAnnotationRuntimeBoundary(
    val source: RealtimeAnnotationCaptureSource,
    val runId: String,
    val pendingRecordingId: String?,
    val canonicalSessionId: Long,
    val canonicalEpochId: Long,
    val sampleIndex: Long,
)

internal data class RealtimeAnnotationCaptureTarget(
    val owner: TimelineAnnotationOwner.Pending,
    val anchor: TimelineAnnotationAnchor,
)

internal fun recorderRealtimeAnnotationBoundary(
    runId: String,
    boundary: RealtimeProcessedCanonicalBoundary?,
): RealtimeAnnotationRuntimeBoundary? {
    if (runId.isBlank() || boundary == null) return null
    return RealtimeAnnotationRuntimeBoundary(
        source = RealtimeAnnotationCaptureSource.RECORDER_CARD,
        runId = runId,
        pendingRecordingId = null,
        canonicalSessionId = boundary.sessionId,
        canonicalEpochId = boundary.epochId,
        sampleIndex = boundary.endSampleIndexExclusive,
    )
}

internal fun phoneRealtimeAnnotationBoundary(
    runId: String,
    recording: PhoneMicrophoneRecordingSnapshot,
): RealtimeAnnotationRuntimeBoundary? {
    if (runId.isBlank()) return null
    val recordingId = recording.recordingId?.takeIf { it.isNotBlank() } ?: return null
    val sessionId = recording.canonicalSessionId ?: return null
    val epochId = recording.activeEpochId ?: return null
    if (recording.activeEpochSamples <= 0L) return null
    return RealtimeAnnotationRuntimeBoundary(
        source = RealtimeAnnotationCaptureSource.PHONE_MICROPHONE,
        runId = runId,
        pendingRecordingId = recordingId,
        canonicalSessionId = sessionId,
        canonicalEpochId = epochId,
        sampleIndex = recording.activeEpochSamples,
    )
}

internal fun buildRealtimeAnnotationCaptureTarget(
    boundary: RealtimeAnnotationRuntimeBoundary,
    segments: List<RealtimeMediaSegmentEntity>,
): RealtimeAnnotationCaptureTarget? {
    if (boundary.sampleIndex <= 0L) return null
    val segment =
        segments.singleOrNull { candidate ->
            candidate.runId == boundary.runId &&
                candidate.canonicalSessionId == boundary.canonicalSessionId
        } ?: return null
    val owner =
        when (boundary.source) {
            RealtimeAnnotationCaptureSource.RECORDER_CARD ->
                TimelineAnnotationOwner.Pending(
                    kind = TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                    ownerId = boundary.runId,
                )
            RealtimeAnnotationCaptureSource.PHONE_MICROPHONE -> {
                val recordingId =
                    boundary.pendingRecordingId?.takeIf { it.isNotBlank() } ?: return null
                TimelineAnnotationOwner.Pending(
                    kind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                    ownerId = recordingId,
                )
            }
        }
    return RealtimeAnnotationCaptureTarget(
        owner = owner,
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                sampleIndex = boundary.sampleIndex,
                realtimeRunIdSnapshot = boundary.runId,
                realtimeSegmentIdSnapshot = segment.id,
                realtimeSegmentIndexSnapshot = segment.segmentIndex,
                canonicalSessionIdSnapshot = boundary.canonicalSessionId,
                canonicalEpochIdSnapshot = boundary.canonicalEpochId,
            ),
    )
}

internal class RealtimeTimelineAnnotationCaptureResolver(
    private val persistenceRepository: RealtimeTranscriptPersistenceRepository,
    private val recorderBoundary: () -> RealtimeAnnotationRuntimeBoundary?,
    private val phoneBoundary: () -> RealtimeAnnotationRuntimeBoundary?,
) {
    suspend fun resolve(source: RealtimeAnnotationCaptureSource): RealtimeAnnotationCaptureTarget? {
        val boundary =
            when (source) {
                RealtimeAnnotationCaptureSource.RECORDER_CARD -> recorderBoundary()
                RealtimeAnnotationCaptureSource.PHONE_MICROPHONE -> phoneBoundary()
            } ?: return null
        return buildRealtimeAnnotationCaptureTarget(
            boundary = boundary,
            segments = persistenceRepository.getSegments(boundary.runId),
        )
    }
}
