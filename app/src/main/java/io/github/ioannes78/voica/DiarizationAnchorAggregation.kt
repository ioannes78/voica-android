package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn
import io.github.ioannes78.voica.transcript.SpeakerAnchorEmbedding
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine

internal suspend fun buildSpeakerAnchorEmbeddings(
    windowStartSampleIndex: Long,
    windowSamples: ShortArray,
    turns: List<DiarizationSpeakerTurn>,
    embeddingEngine: SpeakerEmbeddingEngine,
    config: DiarizationConfig,
): List<SpeakerAnchorEmbedding> {
    val windowEndSampleIndex =
        Math.addExact(windowStartSampleIndex, windowSamples.size.toLong())

    return turns
        .filter { !it.overlap }
        .groupBy { it.speakerIndex }
        .entries
        .sortedBy { it.key }
        .mapNotNull { entry ->
            val ranges =
                entry.value
                    .mapNotNull { turn ->
                        val start =
                            maxOf(turn.startSampleIndex, windowStartSampleIndex)
                        val end =
                            minOf(turn.endSampleIndexExclusive, windowEndSampleIndex)
                        if (end > start) {
                            SpeakerAnchorRange(
                                speakerIndex = entry.key,
                                startSampleIndex = start,
                                endSampleIndexExclusive = end,
                            )
                        } else {
                            null
                        }
                    }
                    .filter { it.sampleCount >= config.stitchingMinimumAnchorSamples }
                    .sortedByDescending { it.sampleCount }
                    .take(config.stitchingMaxAnchorsPerSpeaker)

            if (ranges.isEmpty()) {
                return@mapNotNull null
            }

            var totalWeight = 0L
            var weightedEmbedding: DoubleArray? = null
            ranges.forEach { range ->
                val startOffset =
                    Math.toIntExact(range.startSampleIndex - windowStartSampleIndex)
                val endOffset =
                    Math.toIntExact(range.endSampleIndexExclusive - windowStartSampleIndex)
                val normalized =
                    normalizeSpeakerAnchorEmbedding(
                        embeddingEngine.embed(
                            samples = windowSamples.copyOfRange(startOffset, endOffset),
                            sampleRateHz = config.sampleRateHz,
                        ),
                    )
                val accumulator =
                    weightedEmbedding ?: DoubleArray(normalized.size).also {
                        weightedEmbedding = it
                    }
                require(accumulator.size == normalized.size) {
                    "speaker embedding dimension changed while aggregating anchors"
                }
                normalized.indices.forEach { index ->
                    accumulator[index] +=
                        normalized[index].toDouble() * range.sampleCount.toDouble()
                }
                totalWeight = Math.addExact(totalWeight, range.sampleCount)
            }

            val combined =
                checkNotNull(weightedEmbedding) {
                    "speaker anchor aggregation produced no embedding"
                }
            SpeakerAnchorEmbedding(
                localSpeakerIndex = entry.key,
                embedding =
                    normalizeSpeakerAnchorEmbedding(
                        FloatArray(combined.size) { index ->
                            combined[index].toFloat()
                        },
                    ),
                anchorSampleCount = totalWeight,
            )
        }
}

private data class SpeakerAnchorRange(
    val speakerIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

private fun normalizeSpeakerAnchorEmbedding(values: FloatArray): FloatArray {
    require(values.isNotEmpty())
    var sumSquares = 0.0
    values.forEach { value ->
        require(value.isFinite())
        sumSquares += value.toDouble() * value.toDouble()
    }
    require(sumSquares > 0.0) { "speaker embedding norm must be positive" }
    val norm = kotlin.math.sqrt(sumSquares).toFloat()
    return FloatArray(values.size) { index -> values[index] / norm }
}
