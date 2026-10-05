package io.github.ioannes78.voica.ai

data class SummaryInputChunk(
    val index: Int,
    val sourceStartOrdinal: Int,
    val sourceEndOrdinalExclusive: Int,
    val startSampleIndex: Long?,
    val endSampleIndexExclusive: Long?,
    val units: List<StructuredTranscriptUnit>,
) {
    init {
        require(index >= 0)
        require(sourceStartOrdinal >= 0)
        require(sourceEndOrdinalExclusive > sourceStartOrdinal)
        require((startSampleIndex == null) == (endSampleIndexExclusive == null))
        if (startSampleIndex != null && endSampleIndexExclusive != null) {
            require(startSampleIndex >= 0)
            require(endSampleIndexExclusive > startSampleIndex)
        }
        require(units.isNotEmpty())
    }

    val evidenceRefs: Set<String>
        get() = units.mapNotNullTo(LinkedHashSet()) { it.evidence?.ref }

    val payload: String
        get() = TranscriptPayloadFormatter.formatUnits(units)
}

class SummaryChunkPlanner(
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
) {
    fun plan(
        input: StructuredTranscriptInput,
        targetChunkTokens: Int,
    ): List<SummaryInputChunk> {
        require(targetChunkTokens > 0)
        val pieces =
            input.units.flatMapIndexed { sourceOrdinal, unit ->
                splitOversizedUnit(
                    sourceOrdinal = sourceOrdinal,
                    unit = unit,
                    targetChunkTokens = targetChunkTokens,
                )
            }

        val chunks = mutableListOf<MutableList<Piece>>()
        var current = mutableListOf<Piece>()
        var currentTokens = 0
        pieces.forEach { piece ->
            val tokens = estimator.estimate(TranscriptPayloadFormatter.formatUnit(piece.unit))
            if (
                current.isNotEmpty() &&
                currentTokens + tokens > targetChunkTokens
            ) {
                chunks += current
                current = mutableListOf()
                currentTokens = 0
            }
            current += piece
            currentTokens += tokens
        }
        if (current.isNotEmpty()) {
            chunks += current
        }

        return chunks.mapIndexed { index, group ->
            val evidence = group.mapNotNull { it.unit.evidence }
            SummaryInputChunk(
                index = index,
                sourceStartOrdinal = group.minOf { it.sourceOrdinal },
                sourceEndOrdinalExclusive = group.maxOf { it.sourceOrdinal } + 1,
                startSampleIndex = evidence.minOfOrNull { it.startSampleIndex },
                endSampleIndexExclusive = evidence.maxOfOrNull { it.endSampleIndexExclusive },
                units = group.map { it.unit },
            )
        }
    }

    private fun splitOversizedUnit(
        sourceOrdinal: Int,
        unit: StructuredTranscriptUnit,
        targetChunkTokens: Int,
    ): List<Piece> {
        val formattedTokens = estimator.estimate(TranscriptPayloadFormatter.formatUnit(unit))
        if (formattedTokens <= targetChunkTokens || unit.text.length <= 1) {
            return listOf(Piece(sourceOrdinal, unit))
        }

        val overhead =
            estimator.estimate(
                TranscriptPayloadFormatter.formatUnit(unit.copy(text = "x")),
            ).coerceAtLeast(1)
        val textBudget = (targetChunkTokens - overhead).coerceAtLeast(1)
        val fragments = mutableListOf<String>()
        var cursor = 0
        while (cursor < unit.text.length) {
            var end = cursor
            var bestEnd = cursor
            while (end < unit.text.length) {
                val next = end + 1
                val estimate = estimator.estimate(unit.text.substring(cursor, next))
                if (estimate > textBudget && bestEnd > cursor) break
                bestEnd = next
                end = next
                if (estimate >= textBudget) break
            }
            if (bestEnd <= cursor) {
                bestEnd = (cursor + 1).coerceAtMost(unit.text.length)
            }
            fragments += unit.text.substring(cursor, bestEnd)
            cursor = bestEnd
        }
        return fragments.map { fragment ->
            Piece(
                sourceOrdinal,
                unit.copy(text = fragment),
            )
        }
    }

    private data class Piece(
        val sourceOrdinal: Int,
        val unit: StructuredTranscriptUnit,
    )
}