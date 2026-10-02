package io.github.ioannes78.voica.transcript

data class DiarizationWindowRange(
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    init {
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }

    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

fun planDiarizationWindows(
    speechSegments: List<SpeechSegment>,
    totalSampleCount: Long,
    config: DiarizationConfig,
): List<DiarizationWindowRange> {
    require(totalSampleCount >= 0L)
    if (speechSegments.isEmpty()) return emptyList()

    val padded =
        speechSegments
            .sortedBy { it.startSampleIndex }
            .map { segment ->
                require(segment.endSampleIndexExclusive <= totalSampleCount) {
                    "speech segment exceeds canonical PCM length"
                }
                val start =
                    (segment.startSampleIndex - config.vadContextPaddingSamples)
                        .coerceAtLeast(0L)
                val end =
                    addClamped(
                        segment.endSampleIndexExclusive,
                        config.vadContextPaddingSamples,
                        totalSampleCount,
                    )
                DiarizationWindowRange(start, end)
            }

    val merged = mutableListOf<DiarizationWindowRange>()
    padded.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.startSampleIndex > previous.endSampleIndexExclusive) {
            merged += range
        } else {
            merged[merged.lastIndex] =
                DiarizationWindowRange(
                    startSampleIndex = previous.startSampleIndex,
                    endSampleIndexExclusive =
                        maxOf(previous.endSampleIndexExclusive, range.endSampleIndexExclusive),
                )
        }
    }

    val step = config.chunkSizeSamples - config.chunkOverlapSamples
    val output = mutableListOf<DiarizationWindowRange>()
    merged.forEach { region ->
        var start = region.startSampleIndex
        while (start < region.endSampleIndexExclusive) {
            val end =
                addClamped(
                    start,
                    config.chunkSizeSamples,
                    region.endSampleIndexExclusive,
                )
            output += DiarizationWindowRange(start, end)
            if (end == region.endSampleIndexExclusive) break
            start = Math.addExact(start, step)
        }
    }
    return output
}

private fun addClamped(
    base: Long,
    delta: Long,
    maximum: Long,
): Long {
    require(base >= 0L)
    require(delta >= 0L)
    require(maximum >= 0L)
    if (base >= maximum) return maximum
    val remaining = maximum - base
    return if (delta >= remaining) maximum else base + delta
}
