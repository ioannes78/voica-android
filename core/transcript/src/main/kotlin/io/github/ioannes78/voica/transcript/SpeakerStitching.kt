package io.github.ioannes78.voica.transcript

import kotlin.math.sqrt

data class SpeakerAnchorEmbedding(
    val localSpeakerIndex: Int,
    val embedding: FloatArray,
    val anchorSampleCount: Long,
) {
    init {
        require(localSpeakerIndex >= 0)
        require(embedding.isNotEmpty())
        require(embedding.all { it.isFinite() })
        require(embedding.any { it != 0F })
        require(anchorSampleCount > 0L)
    }
}

data class DiarizationChunkStitchInput(
    val chunkIndex: Int,
    val turns: List<DiarizationSpeakerTurn>,
    val anchors: List<SpeakerAnchorEmbedding>,
) {
    init {
        require(chunkIndex >= 0)
        require(turns.zipWithNext().all { (left, right) ->
            left.startSampleIndex <= right.startSampleIndex
        })
        require(anchors.map { it.localSpeakerIndex }.distinct().size == anchors.size)
    }
}

data class ChunkSpeakerMapping(
    val chunkIndex: Int,
    val localSpeakerIndex: Int,
    val globalSpeakerIndex: Int,
)

data class GlobalSpeakerTurn(
    val globalSpeakerIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val confidence: Float?,
    val overlap: Boolean,
) {
    init {
        require(globalSpeakerIndex >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(confidence == null || (confidence.isFinite() && confidence in -1F..1F))
    }
}

data class SpeakerStitchingResult(
    val speakerCount: Int,
    val mappings: List<ChunkSpeakerMapping>,
    val turns: List<GlobalSpeakerTurn>,
)

fun stitchDiarizationChunks(
    chunks: List<DiarizationChunkStitchInput>,
    config: DiarizationConfig,
): SpeakerStitchingResult {
    if (chunks.isEmpty()) {
        return SpeakerStitchingResult(
            speakerCount = 0,
            mappings = emptyList(),
            turns = emptyList(),
        )
    }

    val orderedChunks =
        chunks.sortedWith(
            compareBy<DiarizationChunkStitchInput> { it.chunkIndex }
                .thenBy { it.turns.minOfOrNull(DiarizationSpeakerTurn::startSampleIndex) ?: Long.MAX_VALUE },
        )

    val states = mutableListOf<GlobalSpeakerState>()
    val mappings = mutableListOf<ChunkSpeakerMapping>()

    orderedChunks.forEach { chunk ->
        val anchors = chunk.anchors.associateBy { it.localSpeakerIndex }
        val localGroups =
            chunk.turns
                .groupBy { it.speakerIndex }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<Int, List<DiarizationSpeakerTurn>>> {
                        it.value.minOf(DiarizationSpeakerTurn::startSampleIndex)
                    }.thenBy { it.key },
                )

        val usedGlobalIndices = mutableSetOf<Int>()

        localGroups.forEach { (localSpeakerIndex, localTurns) ->
            val anchor = anchors[localSpeakerIndex]
            val normalizedAnchor = anchor?.embedding?.let(::normalizedCopy)
            val match =
                findBestGlobalMatch(
                    localTurns = localTurns,
                    normalizedAnchor = normalizedAnchor,
                    states = states,
                    excludedGlobalIndices = usedGlobalIndices,
                    config = config,
                )
            val state =
                match ?: GlobalSpeakerState(globalSpeakerIndex = states.size).also(states::add)

            usedGlobalIndices += state.globalSpeakerIndex
            mappings +=
                ChunkSpeakerMapping(
                    chunkIndex = chunk.chunkIndex,
                    localSpeakerIndex = localSpeakerIndex,
                    globalSpeakerIndex = state.globalSpeakerIndex,
                )

            if (normalizedAnchor != null && anchor.anchorSampleCount >= config.stitchingMinimumAnchorSamples) {
                state.updateCentroid(
                    normalizedEmbedding = normalizedAnchor,
                    weight = anchor.anchorSampleCount,
                )
            }

            localTurns.forEach { turn ->
                state.turns +=
                    GlobalSpeakerTurn(
                        globalSpeakerIndex = state.globalSpeakerIndex,
                        startSampleIndex = turn.startSampleIndex,
                        endSampleIndexExclusive = turn.endSampleIndexExclusive,
                        confidence = turn.confidence,
                        overlap = turn.overlap,
                    )
                state.lastEndSampleIndex =
                    maxOf(state.lastEndSampleIndex, turn.endSampleIndexExclusive)
            }
        }
    }

    val normalizedTurns =
        states
            .flatMap { mergeDuplicateTurns(it.turns) }
            .sortedWith(
                compareBy<GlobalSpeakerTurn> { it.startSampleIndex }
                    .thenBy { it.endSampleIndexExclusive }
                    .thenBy { it.globalSpeakerIndex },
            )

    return SpeakerStitchingResult(
        speakerCount = states.size,
        mappings = mappings,
        turns = normalizedTurns,
    )
}

private fun findBestGlobalMatch(
    localTurns: List<DiarizationSpeakerTurn>,
    normalizedAnchor: FloatArray?,
    states: List<GlobalSpeakerState>,
    excludedGlobalIndices: Set<Int>,
    config: DiarizationConfig,
): GlobalSpeakerState? {
    val candidates =
        states
            .asSequence()
            .filter { it.globalSpeakerIndex !in excludedGlobalIndices }
            .map { state ->
                val similarity =
                    if (normalizedAnchor != null && state.centroid != null) {
                        cosineSimilarityNormalized(normalizedAnchor, state.centroid!!)
                    } else {
                        null
                    }
                val overlapSamples =
                    totalOverlapSamples(
                        localTurns = localTurns,
                        globalTurns = state.turns,
                    )
                GlobalMatchCandidate(
                    state = state,
                    similarity = similarity,
                    overlapSamples = overlapSamples,
                )
            }
            .toList()

    if (normalizedAnchor != null) {
        val embeddingMatches =
            candidates.filter { candidate ->
                candidate.similarity != null &&
                    candidate.similarity >= config.stitchingCosineThreshold
            }
        if (embeddingMatches.isNotEmpty()) {
            return embeddingMatches
                .sortedWith(
                    compareByDescending<GlobalMatchCandidate> { it.similarity }
                        .thenByDescending { it.overlapSamples }
                        .thenByDescending { it.state.lastEndSampleIndex }
                        .thenBy { it.state.globalSpeakerIndex },
                )
                .first()
                .state
        }
    }

    val overlapMatches =
        candidates.filter {
            it.overlapSamples >= config.stitchingMinimumOverlapSamples &&
                (it.similarity == null || it.similarity >= config.stitchingCosineThreshold)
        }
    return overlapMatches
        .sortedWith(
            compareByDescending<GlobalMatchCandidate> { it.overlapSamples }
                .thenByDescending { it.state.lastEndSampleIndex }
                .thenBy { it.state.globalSpeakerIndex },
        )
        .firstOrNull()
        ?.state
}

private data class GlobalMatchCandidate(
    val state: GlobalSpeakerState,
    val similarity: Float?,
    val overlapSamples: Long,
)

private class GlobalSpeakerState(
    val globalSpeakerIndex: Int,
) {
    var centroid: FloatArray? = null
    var centroidWeight: Long = 0L
    var lastEndSampleIndex: Long = -1L
    val turns = mutableListOf<GlobalSpeakerTurn>()

    fun updateCentroid(
        normalizedEmbedding: FloatArray,
        weight: Long,
    ) {
        require(weight > 0L)
        val current = centroid
        if (current == null) {
            centroid = normalizedEmbedding.copyOf()
            centroidWeight = weight
            return
        }
        require(current.size == normalizedEmbedding.size) {
            "speaker embedding dimension changed within one diarization run"
        }

        val previousWeight = centroidWeight.toDouble()
        val newWeight = weight.toDouble()
        val combined =
            FloatArray(current.size) { index ->
                ((current[index] * previousWeight +
                    normalizedEmbedding[index] * newWeight) /
                    (previousWeight + newWeight)).toFloat()
            }
        centroid = normalizedCopy(combined)
        centroidWeight = Math.addExact(centroidWeight, weight)
    }
}

private fun totalOverlapSamples(
    localTurns: List<DiarizationSpeakerTurn>,
    globalTurns: List<GlobalSpeakerTurn>,
): Long {
    var total = 0L
    localTurns.forEach { local ->
        globalTurns.forEach { global ->
            val start = maxOf(local.startSampleIndex, global.startSampleIndex)
            val end = minOf(local.endSampleIndexExclusive, global.endSampleIndexExclusive)
            if (end > start) {
                total = Math.addExact(total, end - start)
            }
        }
    }
    return total
}

private fun mergeDuplicateTurns(
    turns: List<GlobalSpeakerTurn>,
): List<GlobalSpeakerTurn> {
    if (turns.isEmpty()) return emptyList()
    val ordered =
        turns.sortedWith(
            compareBy<GlobalSpeakerTurn> { it.startSampleIndex }
                .thenBy { it.endSampleIndexExclusive },
        )
    val output = mutableListOf<GlobalSpeakerTurn>()
    ordered.forEach { turn ->
        val previous = output.lastOrNull()
        if (previous == null || turn.startSampleIndex > previous.endSampleIndexExclusive) {
            output += turn
        } else {
            output[output.lastIndex] =
                previous.copy(
                    endSampleIndexExclusive =
                        maxOf(previous.endSampleIndexExclusive, turn.endSampleIndexExclusive),
                    confidence = maxConfidence(previous.confidence, turn.confidence),
                    overlap = previous.overlap || turn.overlap,
                )
        }
    }
    return output
}

private fun maxConfidence(
    left: Float?,
    right: Float?,
): Float? =
    when {
        left == null -> right
        right == null -> left
        else -> maxOf(left, right)
    }

private fun normalizedCopy(values: FloatArray): FloatArray {
    var sumSquares = 0.0
    values.forEach { value ->
        require(value.isFinite())
        sumSquares += value.toDouble() * value.toDouble()
    }
    require(sumSquares > 0.0) { "speaker embedding norm must be positive" }
    val norm = sqrt(sumSquares).toFloat()
    return FloatArray(values.size) { index -> values[index] / norm }
}

private fun cosineSimilarityNormalized(
    left: FloatArray,
    right: FloatArray,
): Float {
    require(left.size == right.size)
    var dot = 0.0
    left.indices.forEach { index ->
        dot += left[index].toDouble() * right[index].toDouble()
    }
    return dot.toFloat().coerceIn(-1F, 1F)
}
