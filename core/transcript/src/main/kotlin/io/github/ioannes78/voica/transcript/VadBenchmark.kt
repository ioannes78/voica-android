package io.github.ioannes78.voica.transcript

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Immutable Stage 16B VAD Gate A configuration identity.
 *
 * The benchmark records policy parameters separately from the model ID so a difference caused by
 * forced-split length is not misattributed to VAD-model quality.
 */
data class VadBenchmarkProfile(
    val id: String,
    val displayName: String,
    val modelId: String,
    val maxSpeechDurationSeconds: Float,
    val primaryGateAProfile: Boolean,
    val notes: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(modelId.isNotBlank())
        require(maxSpeechDurationSeconds > 0.0F)
    }
}

data class VadBenchmarkObservation(
    val profile: VadBenchmarkProfile,
    val modelVersion: String,
    val modelRevision: Long,
    val sampleRateHz: Int,
    val totalSampleCount: Long,
    val wallTimeMillis: Long,
    val peakPssKb: Long?,
    val segments: List<SpeechSegment>,
    val shortFragmentThresholdMillis: Long = DEFAULT_SHORT_FRAGMENT_THRESHOLD_MS,
    val forcedSplitToleranceMillis: Long = DEFAULT_FORCED_SPLIT_TOLERANCE_MS,
) {
    init {
        require(modelVersion.isNotBlank())
        require(modelRevision >= 1L)
        require(sampleRateHz > 0)
        require(totalSampleCount >= 0L)
        require(wallTimeMillis >= 0L)
        require(peakPssKb == null || peakPssKb >= 0L)
        require(shortFragmentThresholdMillis >= 0L)
        require(forcedSplitToleranceMillis >= 0L)
        var previousEnd = 0L
        segments.forEach { segment ->
            require(segment.startSampleIndex >= previousEnd) {
                "VAD benchmark segments must be monotonic and non-overlapping"
            }
            require(segment.endSampleIndexExclusive <= totalSampleCount) {
                "VAD benchmark segment exceeds canonical PCM"
            }
            previousEnd = segment.endSampleIndexExclusive
        }
    }

    val audioDurationSeconds: Double
        get() = totalSampleCount.toDouble() / sampleRateHz.toDouble()

    val realTimeFactor: Double?
        get() =
            if (totalSampleCount == 0L) {
                null
            } else {
                (wallTimeMillis / 1000.0) / audioDurationSeconds
            }

    val segmentCount: Int
        get() = segments.size

    val speechSampleCount: Long
        get() = segments.sumOf { it.endSampleIndexExclusive - it.startSampleIndex }

    val speechDurationMillis: Long
        get() = samplesToMillis(speechSampleCount)

    val speechRatio: Double?
        get() =
            if (totalSampleCount == 0L) null
            else speechSampleCount.toDouble() / totalSampleCount.toDouble()

    val averageSegmentDurationMillis: Double?
        get() =
            if (segments.isEmpty()) null
            else segmentDurationsMillis().average()

    val medianSegmentDurationMillis: Double?
        get() {
            val durations = segmentDurationsMillis().sorted()
            if (durations.isEmpty()) return null
            val middle = durations.size / 2
            return if (durations.size % 2 == 1) {
                durations[middle].toDouble()
            } else {
                (durations[middle - 1] + durations[middle]) / 2.0
            }
        }

    val shortFragmentCount: Int
        get() = segmentDurationsMillis().count { it < shortFragmentThresholdMillis }

    /**
     * Observable proxy for a maximum-duration forced split.
     *
     * The generic VadEngine contract does not expose the native split reason. Gate A therefore
     * counts segments whose duration lands within a small tolerance of the configured maximum and
     * labels the metric as a proxy rather than fabricated ground truth.
     */
    val maxDurationSplitProxyCount: Int
        get() {
            val configuredMaxMs = (profile.maxSpeechDurationSeconds * 1000.0F).roundToLong()
            return segmentDurationsMillis().count { durationMs ->
                abs(durationMs - configuredMaxMs) <= forcedSplitToleranceMillis
            }
        }

    fun segmentDurationsMillis(): List<Long> =
        segments.map { segment ->
            samplesToMillis(segment.endSampleIndexExclusive - segment.startSampleIndex)
        }

    private fun samplesToMillis(samples: Long): Long =
        ((samples * 1000.0) / sampleRateHz.toDouble()).roundToLong()

    companion object {
        const val DEFAULT_SHORT_FRAGMENT_THRESHOLD_MS = 500L
        const val DEFAULT_FORCED_SPLIT_TOLERANCE_MS = 150L
    }
}

data class VadReferenceMetrics(
    val referenceSpeechSamples: Long,
    val truePositiveSpeechSamples: Long,
    val falsePositiveSpeechSamples: Long,
    val falseNegativeSpeechSamples: Long,
    val speechRecall: Double?,
    val speechPrecision: Double?,
)

/** Optional labelled-reference scoring used when Gate A samples have trusted speech intervals. */
object VadBenchmarkScoring {
    fun againstReference(
        hypothesis: List<SpeechSegment>,
        reference: List<SpeechSegment>,
    ): VadReferenceMetrics {
        requireMonotonic(reference)
        requireMonotonic(hypothesis)

        val referenceSamples = reference.sumOf(::length)
        val hypothesisSamples = hypothesis.sumOf(::length)
        val overlapSamples = overlapSamples(reference, hypothesis)
        val falsePositive = (hypothesisSamples - overlapSamples).coerceAtLeast(0L)
        val falseNegative = (referenceSamples - overlapSamples).coerceAtLeast(0L)
        return VadReferenceMetrics(
            referenceSpeechSamples = referenceSamples,
            truePositiveSpeechSamples = overlapSamples,
            falsePositiveSpeechSamples = falsePositive,
            falseNegativeSpeechSamples = falseNegative,
            speechRecall =
                if (referenceSamples == 0L) null
                else overlapSamples.toDouble() / referenceSamples.toDouble(),
            speechPrecision =
                if (hypothesisSamples == 0L) null
                else overlapSamples.toDouble() / hypothesisSamples.toDouble(),
        )
    }

    private fun overlapSamples(
        left: List<SpeechSegment>,
        right: List<SpeechSegment>,
    ): Long {
        var leftIndex = 0
        var rightIndex = 0
        var total = 0L
        while (leftIndex < left.size && rightIndex < right.size) {
            val a = left[leftIndex]
            val b = right[rightIndex]
            val start = maxOf(a.startSampleIndex, b.startSampleIndex)
            val end = minOf(a.endSampleIndexExclusive, b.endSampleIndexExclusive)
            if (end > start) total += end - start
            if (a.endSampleIndexExclusive <= b.endSampleIndexExclusive) {
                leftIndex += 1
            } else {
                rightIndex += 1
            }
        }
        return total
    }

    private fun requireMonotonic(segments: List<SpeechSegment>) {
        var end = 0L
        segments.forEach { segment ->
            require(segment.startSampleIndex >= end) {
                "reference/hypothesis segments must be monotonic and non-overlapping"
            }
            end = segment.endSampleIndexExclusive
        }
    }

    private fun length(segment: SpeechSegment): Long =
        segment.endSampleIndexExclusive - segment.startSampleIndex
}
