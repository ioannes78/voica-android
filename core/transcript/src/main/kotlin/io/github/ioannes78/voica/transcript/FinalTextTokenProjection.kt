package io.github.ioannes78.voica.transcript

enum class TextProjectionQuality {
    EXACT,
    HEURISTIC,
    UNAVAILABLE,
}

data class FinalTextTokenProjection(
    val boundaries: IntArray,
    val quality: TextProjectionQuality,
) {
    init {
        require(boundaries.isNotEmpty())
    }
}


internal fun isPunctuationOnlyToken(text: String): Boolean =
    text.isNotEmpty() &&
        text.all { ch ->
            ch.isWhitespace() ||
                when (Character.getType(ch)) {
                    Character.CONNECTOR_PUNCTUATION.toInt(),
                    Character.DASH_PUNCTUATION.toInt(),
                    Character.START_PUNCTUATION.toInt(),
                    Character.END_PUNCTUATION.toInt(),
                    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                    Character.OTHER_PUNCTUATION.toInt(),
                    -> true
                    else -> false
                }
        }

fun projectTokensToFinalText(
    finalText: String,
    tokens: List<String>,
): FinalTextTokenProjection {
    if (tokens.isEmpty()) {
        return FinalTextTokenProjection(
            boundaries = intArrayOf(0),
            quality = TextProjectionQuality.UNAVAILABLE,
        )
    }

    exactTokenTextBoundaries(finalText, tokens)?.let { boundaries ->
        return FinalTextTokenProjection(
            boundaries = boundaries,
            quality = TextProjectionQuality.EXACT,
        )
    }

    val weights = tokens.map { it.length.coerceAtLeast(1) }
    val totalWeight = weights.sumOf { it.toLong() }
    val boundaries = IntArray(tokens.size + 1)
    boundaries[0] = 0
    boundaries[tokens.size] = finalText.length

    var cumulativeWeight = 0L
    for (index in 1 until tokens.size) {
        cumulativeWeight += weights[index - 1].toLong()
        boundaries[index] =
            ((finalText.length.toLong() * cumulativeWeight) / totalWeight)
                .toInt()
                .coerceIn(boundaries[index - 1], finalText.length)
    }

    return FinalTextTokenProjection(
        boundaries = boundaries,
        quality = TextProjectionQuality.HEURISTIC,
    )
}

private fun exactTokenTextBoundaries(
    finalText: String,
    tokens: List<String>,
): IntArray? {
    val starts = IntArray(tokens.size)
    var cursor = 0

    tokens.forEachIndexed { index, token ->
        if (token.isEmpty()) return null
        val found = finalText.indexOf(token, startIndex = cursor)
        if (found < 0) return null
        starts[index] = found
        cursor = found + token.length
    }

    val boundaries = IntArray(tokens.size + 1)
    boundaries[0] = 0
    for (index in 1 until tokens.size) {
        boundaries[index] = starts[index]
        if (boundaries[index] < boundaries[index - 1]) return null
    }
    boundaries[tokens.size] = finalText.length
    return boundaries
}
