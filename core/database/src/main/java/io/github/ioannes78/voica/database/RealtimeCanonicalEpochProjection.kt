package io.github.ioannes78.voica.database

/**
 * Resolves epoch-local realtime sample coordinates onto one physically concatenated canonical WAV.
 *
 * Canonical epochs restart sampleIndex at zero after PAUSE/RESUME. A verified WAV, however, contains
 * only received PCM concatenated back-to-back, with no synthetic pause silence. Therefore a later
 * epoch may be projected onto canonical playback only when durable PAUSE/RESUME markers prove every
 * preceding epoch length. Missing or inconsistent evidence returns null instead of fabricating media
 * coordinates.
 */
internal fun resolveRealtimeCanonicalEpochOffsets(
    segment: RealtimeMediaSegmentEntity,
    utterances: List<RealtimeUtteranceEntity>,
    markers: List<RealtimeTimelineMarkerEntity>,
): Map<Long, Long>? {
    require(segment.canonicalSampleCount >= 0L)

    val relevantMarkers = markers.filter { it.segmentId == segment.id }.sortedBy { it.markerIndex }
    val observedEpochIds =
        buildSet {
            utterances.forEach { add(it.canonicalEpochId) }
            relevantMarkers.forEach { marker -> marker.canonicalEpochId?.let(::add) }
        }.sorted()
    if (observedEpochIds.isEmpty()) return emptyMap()

    val initialEpochId = observedEpochIds.first()
    val offsets = linkedMapOf(initialEpochId to 0L)
    val completedEpochLengths = mutableMapOf<Long, Long>()
    var activeEpochId: Long? = initialEpochId
    var accumulatedSamples = 0L
    var lastEpochId = initialEpochId

    relevantMarkers.forEach { marker ->
        when (marker.type) {
            RealtimeTimelineMarkerTypeValue.PAUSE -> {
                val epochId = marker.canonicalEpochId ?: return null
                val localEnd = marker.sampleIndex ?: return null
                if (localEnd < 0L || activeEpochId != epochId) return null
                if (completedEpochLengths.putIfAbsent(epochId, localEnd) != null) return null
                val base = offsets[epochId] ?: return null
                val absoluteEnd = runCatching { Math.addExact(base, localEnd) }.getOrNull() ?: return null
                if (absoluteEnd > segment.canonicalSampleCount) return null
                accumulatedSamples = absoluteEnd
                activeEpochId = null
            }

            RealtimeTimelineMarkerTypeValue.RESUME -> {
                val epochId = marker.canonicalEpochId ?: return null
                val localStart = marker.sampleIndex ?: return null
                if (localStart != 0L || activeEpochId != null) return null
                if (epochId <= lastEpochId || offsets.containsKey(epochId)) return null
                offsets[epochId] = accumulatedSamples
                activeEpochId = epochId
                lastEpochId = epochId
            }
        }
    }

    if (observedEpochIds.any { offsets[it] == null }) return null

    if (activeEpochId == null) {
        if (accumulatedSamples != segment.canonicalSampleCount) return null
    } else {
        val finalBase = offsets[activeEpochId] ?: return null
        if (finalBase > segment.canonicalSampleCount) return null
    }

    utterances.forEach { utterance ->
        val base = offsets[utterance.canonicalEpochId] ?: return null
        val localLimit =
            completedEpochLengths[utterance.canonicalEpochId]
                ?: (segment.canonicalSampleCount - base)
        if (
            utterance.startSampleIndex < 0L ||
                utterance.endSampleIndexExclusive < utterance.startSampleIndex ||
                utterance.endSampleIndexExclusive > localLimit
        ) {
            return null
        }
    }

    return offsets
}
