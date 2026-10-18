package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorResolver
import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import io.github.ioannes78.voica.database.TimelineAnnotationSeekResolution
import io.github.ioannes78.voica.database.TimelineAnnotationSeekUnavailableReason
import io.github.ioannes78.voica.database.TimelinePlaybackMediaIdentity

/**
 * Seeks one durable user annotation through the existing single PlaybackController.
 *
 * This path is intentionally different from transcript playback: it never calls play() or pause().
 * Therefore the playback engine itself preserves PLAYING, PAUSED, and non-playing READY semantics.
 */
class TimelineAnnotationPlaybackCoordinator(
    private val controller: PlaybackController,
    private val resolveAnchor:
        suspend (TimelineAnnotationEntity, TimelinePlaybackMediaIdentity) ->
            TimelineAnnotationSeekResolution,
) {
    constructor(
        controller: PlaybackController,
        resolver: TimelineAnnotationAnchorResolver,
    ) : this(
        controller = controller,
        resolveAnchor = resolver::resolve,
    )

    suspend fun resolve(
        annotation: TimelineAnnotationEntity,
        media: TimelinePlaybackMediaIdentity,
    ): TimelineAnnotationSeekResolution = resolveAnchor(annotation, media)

    suspend fun seek(annotation: TimelineAnnotationEntity): TimelineAnnotationSeekResolution {
        val recordingId =
            annotation.recordingId
                ?: return unavailable(TimelineAnnotationSeekUnavailableReason.PENDING_SESSION)
        if (controller.snapshot.value.state == PlaybackState.RELEASED) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        }

        val before = controller.snapshot.value
        if (before.recordingId != recordingId || before.state == PlaybackState.ERROR) {
            controller.load(recordingId)
        }

        val loaded = controller.snapshot.value
        if (
            loaded.recordingId != recordingId ||
                loaded.state == PlaybackState.ERROR ||
                loaded.state == PlaybackState.RELEASED
        ) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        }
        val sourceAssetId =
            loaded.sourceAssetId?.takeIf(String::isNotBlank)
                ?: return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        if (loaded.durationSampleCount <= 0L) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        }

        val resolution =
            resolveAnchor(
                annotation,
                TimelinePlaybackMediaIdentity(
                    recordingId = recordingId,
                    sourceAssetId = sourceAssetId,
                    durationSampleCount = loaded.durationSampleCount,
                ),
            )
        if (resolution is TimelineAnnotationSeekResolution.Exact) {
            controller.seekToSample(resolution.sampleIndex)
        }
        return resolution
    }

    private fun unavailable(
        reason: TimelineAnnotationSeekUnavailableReason,
    ): TimelineAnnotationSeekResolution = TimelineAnnotationSeekResolution.Unavailable(reason)
}
