package io.github.ioannes78.voica.database

/** The currently loaded playback media identity, supplied by the existing single PlaybackController. */
data class TimelinePlaybackMediaIdentity(
    val recordingId: String,
    val sourceAssetId: String,
    val durationSampleCount: Long,
)

enum class TimelineAnnotationSeekUnavailableReason {
    PENDING_SESSION,
    RECORDING_MISMATCH,
    PLAYBACK_SOURCE_UNAVAILABLE,
    CANONICAL_ASSET_MISMATCH,
    CANONICAL_SHA_MISMATCH,
    CANONICAL_PROFILE_MISMATCH,
    SAMPLE_OUT_OF_RANGE,
    REALTIME_LINEAGE_MISSING,
    REALTIME_SEGMENT_MISMATCH,
    REALTIME_SEGMENTED_CAPTURE,
    REALTIME_EPOCH_UNPROVEN,
}

sealed interface TimelineAnnotationSeekResolution {
    data class Exact(val sampleIndex: Long) : TimelineAnnotationSeekResolution

    data class Unavailable(
        val reason: TimelineAnnotationSeekUnavailableReason,
    ) : TimelineAnnotationSeekResolution
}

/**
 * Converts a durable annotation anchor to the sample coordinate accepted by PlaybackController.
 *
 * This resolver is intentionally evidence-gated. In particular, a realtime-media anchor is never
 * projected by concatenating segment lengths. It is eligible only when the existing Stage 16A
 * realtime integration has already produced a playback-compatible single-segment transcription
 * lineage and the durable epoch markers prove the exact offset inside that canonical WAV.
 */
class TimelineAnnotationAnchorResolver(
    private val database: VoicaDatabase,
) {
    private val recordingDao = database.recordingDao()
    private val realtimeDao = database.realtimeTranscriptionDao()
    private val transcriptionDao = database.transcriptionDao()

    suspend fun resolve(
        annotation: TimelineAnnotationEntity,
        playback: TimelinePlaybackMediaIdentity,
    ): TimelineAnnotationSeekResolution {
        if (
            annotation.ownershipState != TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING ||
                annotation.recordingId == null
        ) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.PENDING_SESSION)
        }
        if (annotation.recordingId != playback.recordingId) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.RECORDING_MISMATCH)
        }
        if (playback.sourceAssetId.isBlank() || playback.durationSampleCount <= 0L) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        }

        return when (annotation.anchorDomain) {
            TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING ->
                resolveCanonical(annotation, playback)
            TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT ->
                resolveRealtimeSegment(annotation, playback)
            else -> unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        }
    }

    private suspend fun resolveCanonical(
        annotation: TimelineAnnotationEntity,
        playback: TimelinePlaybackMediaIdentity,
    ): TimelineAnnotationSeekResolution {
        val canonical = verifiedCanonicalPlaybackAsset(playback)
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        annotation.anchorCanonicalAssetIdSnapshot?.let { expected ->
            if (expected != canonical.assetId) {
                return unavailable(TimelineAnnotationSeekUnavailableReason.CANONICAL_ASSET_MISMATCH)
            }
        }
        annotation.anchorCanonicalSha256Snapshot?.let { expected ->
            if (!expected.equals(canonical.sha256, ignoreCase = true)) {
                return unavailable(TimelineAnnotationSeekUnavailableReason.CANONICAL_SHA_MISMATCH)
            }
        }
        annotation.anchorCanonicalProfileIdSnapshot?.let { expectedProfile ->
            val row = recordingDao.findWithAssets(playback.recordingId)
                ?: return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
            val profileMatches =
                row.derivations.any { derivation ->
                    derivation.outputAssetId == canonical.assetId &&
                        derivation.state == AudioDerivationState.READY &&
                        derivation.profileId == expectedProfile
                }
            if (!profileMatches) {
                return unavailable(TimelineAnnotationSeekUnavailableReason.CANONICAL_PROFILE_MISMATCH)
            }
        }
        return exactIfInRange(annotation.anchorSampleIndex, playback.durationSampleCount)
    }

    private suspend fun resolveRealtimeSegment(
        annotation: TimelineAnnotationEntity,
        playback: TimelinePlaybackMediaIdentity,
    ): TimelineAnnotationSeekResolution {
        val runId = annotation.anchorRealtimeRunIdSnapshot
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        val segmentId = annotation.anchorRealtimeSegmentIdSnapshot
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        val segmentIndex = annotation.anchorRealtimeSegmentIndexSnapshot
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        val sessionId = annotation.anchorCanonicalSessionIdSnapshot
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        val epochId = annotation.anchorCanonicalEpochIdSnapshot
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_EPOCH_UNPROVEN)

        val canonical = verifiedCanonicalPlaybackAsset(playback)
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.PLAYBACK_SOURCE_UNAVAILABLE)
        val run = realtimeDao.getRun(runId)
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        if (run.recordingId != playback.recordingId) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.RECORDING_MISMATCH)
        }

        val segments = realtimeDao.getSegments(runId)
        if (segments.size != 1) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_SEGMENTED_CAPTURE)
        }
        val segment = segments.single()
        if (
            segment.id != segmentId ||
                segment.segmentIndex != segmentIndex ||
                segment.canonicalSessionId != sessionId
        ) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_SEGMENT_MISMATCH)
        }

        // This deterministic ID is already the durable Stage 16A projection contract used by the
        // realtime integration and recording lifecycle.
        val projection = transcriptionDao.findTranscription("stage16a-realtime-$runId")
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_LINEAGE_MISSING)
        if (
            projection.recordingId != playback.recordingId ||
                projection.state != TranscriptionStateValue.COMPLETED ||
                projection.canonicalProfileId == RealtimeTranscriptProfileValue.SEGMENTED_CAPTURE
        ) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_SEGMENTED_CAPTURE)
        }
        if (projection.sourceCanonicalAssetId != canonical.assetId) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.CANONICAL_ASSET_MISMATCH)
        }
        if (
            projection.totalSampleCount != segment.canonicalSampleCount ||
                projection.totalSampleCount != playback.durationSampleCount
        ) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.CANONICAL_ASSET_MISMATCH)
        }

        val utterances = realtimeDao.getUtterances(segment.id)
        val markers = realtimeDao.getTimelineMarkers(runId)
        val offsets = resolveRealtimeCanonicalEpochOffsets(segment, utterances, markers)
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_EPOCH_UNPROVEN)
        val base = offsets[epochId]
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.REALTIME_EPOCH_UNPROVEN)

        val pauseLimit =
            markers
                .asSequence()
                .filter { it.segmentId == segment.id }
                .filter { it.type == RealtimeTimelineMarkerTypeValue.PAUSE }
                .firstOrNull { it.canonicalEpochId == epochId }
                ?.sampleIndex
        val localLimit = pauseLimit ?: (segment.canonicalSampleCount - base)
        if (annotation.anchorSampleIndex < 0L || annotation.anchorSampleIndex > localLimit) {
            return unavailable(TimelineAnnotationSeekUnavailableReason.SAMPLE_OUT_OF_RANGE)
        }
        val absolute = runCatching { Math.addExact(base, annotation.anchorSampleIndex) }.getOrNull()
            ?: return unavailable(TimelineAnnotationSeekUnavailableReason.SAMPLE_OUT_OF_RANGE)
        return exactIfInRange(absolute, playback.durationSampleCount)
    }

    private suspend fun verifiedCanonicalPlaybackAsset(
        playback: TimelinePlaybackMediaIdentity,
    ): AudioAssetEntity? {
        val row = recordingDao.findWithAssets(playback.recordingId) ?: return null
        return row.assets.firstOrNull { asset ->
            asset.assetId == playback.sourceAssetId &&
                asset.role == AudioAssetRole.CANONICAL_WAV &&
                asset.integrityState == AudioIntegrityState.VERIFIED &&
                asset.formatValidationState == AudioValidationState.VALID
        }
    }

    private fun exactIfInRange(
        sampleIndex: Long,
        durationSampleCount: Long,
    ): TimelineAnnotationSeekResolution =
        if (sampleIndex in 0L..durationSampleCount) {
            TimelineAnnotationSeekResolution.Exact(sampleIndex)
        } else {
            unavailable(TimelineAnnotationSeekUnavailableReason.SAMPLE_OUT_OF_RANGE)
        }

    private fun unavailable(
        reason: TimelineAnnotationSeekUnavailableReason,
    ): TimelineAnnotationSeekResolution = TimelineAnnotationSeekResolution.Unavailable(reason)
}
