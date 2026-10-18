package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.protocol.RecordingStatus

/** Exact active-phone target: writtenSamples is the final WAV coordinate and excludes pause time. */
internal fun phoneRecordingAnnotationTarget(
    recording: PhoneMicrophoneRecordingSnapshot,
): RealtimeAnnotationCaptureTarget? {
    if (
        recording.phase != PhoneMicrophoneRecordingPhase.RECORDING &&
        recording.phase != PhoneMicrophoneRecordingPhase.PAUSED
    ) {
        return null
    }
    val recordingId = recording.recordingId?.takeIf { it.isNotBlank() } ?: return null
    if (recording.writtenSamples <= 0L) return null
    return RealtimeAnnotationCaptureTarget(
        owner =
            TimelineAnnotationOwner.Pending(
                kind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                ownerId = recordingId,
            ),
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                sampleIndex = recording.writtenSamples,
            ),
    )
}

/**
 * Ordinary QS668 capture has no phone PCM clock. Use only fresh device-confirmed media duration and
 * exact active filename identity; never synthesize a position from UI/wall-clock elapsed time.
 * Device duration is second-granularity, so this is intentionally coarser than phone PCM capture.
 */
internal fun recorderRecordingAnnotationTarget(
    recording: RecordingDeviceState,
): RealtimeAnnotationCaptureTarget? {
    if (recording.freshness != RecordingFreshness.FRESH) return null
    if (
        recording.status != RecordingStatus.Recording &&
        recording.status != RecordingStatus.Paused
    ) {
        return null
    }
    val remoteIdentity = recording.filename?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val durationSeconds = recording.durationSeconds?.takeIf { it >= 0 } ?: return null
    val sampleIndex =
        runCatching {
            Math.multiplyExact(
                durationSeconds.toLong(),
                CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong(),
            )
        }.getOrNull() ?: return null
    return RealtimeAnnotationCaptureTarget(
        owner =
            TimelineAnnotationOwner.Pending(
                kind = TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE,
                ownerId = remoteIdentity,
            ),
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                sampleIndex = sampleIndex,
            ),
    )
}
