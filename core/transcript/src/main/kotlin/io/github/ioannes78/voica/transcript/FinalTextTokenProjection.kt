package io.github.ioannes78.voica.transcript

import java.text.Normalizer

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

/**
 * Maps native ASR token text to the product text without inventing lexical equivalence.
 *
 * The product finalizer may add punctuation/spacing, and some tokenizers expose a leading U+2581
 * word-boundary marker. ASCII case can also be normalized by a downstream text formatter. Those
 * presentation-only differences are safe to ignore because the underlying lexical characters are
 * unchanged. Content-changing transforms (for example ITN: "一百" -> "100") still fail this path
 * and therefore remain HEURISTIC rather than claiming exact timing.
 */
private fun exactTokenTextBoundaries(
    finalText: String,
    tokens: List<String>,
): IntArray? {
    if (tokens.any(String::isEmpty)) return null

    val finalUnits = lexicalUnits(finalText, keepOriginalOffsets = true)
    if (finalUnits.isEmpty()) return null

    val tokenUnits = tokens.map { token -> lexicalUnits(token, keepOriginalOffsets = false) }
    if (tokenUnits.any { it.isEmpty() }) return null
    if (tokenUnits.sumOf { it.size } != finalUnits.size) return null

    val boundaries = IntArray(tokens.size + 1)
    boundaries[0] = 0
    boundaries[tokens.size] = finalText.length

    var finalIndex = 0
    tokens.indices.forEach { tokenIndex ->
        val units = tokenUnits[tokenIndex]
        if (tokenIndex > 0) {
            boundaries[tokenIndex] = finalUnits[finalIndex].originalOffset
        }
        units.forEach { tokenUnit ->
            val finalUnit = finalUnits.getOrNull(finalIndex) ?: return null
            if (tokenUnit.normalized != finalUnit.normalized) return null
            finalIndex += 1
        }
    }

    if (finalIndex != finalUnits.size) return null
    return boundaries
}

private data class LexicalUnit(
    val normalized: String,
    val originalOffset: Int,
)

private fun lexicalUnits(
    text: String,
    keepOriginalOffsets: Boolean,
): List<LexicalUnit> {
    val output = ArrayList<LexicalUnit>(text.length)
    text.forEachIndexed { index, ch ->
        if (isPresentationOnlyCharacter(ch)) return@forEachIndexed
        val normalized =
            Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
                .lowercase()
        if (normalized.isEmpty()) return@forEachIndexed
        normalized.forEach { normalizedChar ->
            if (!isPresentationOnlyCharacter(normalizedChar)) {
                output +=
                    LexicalUnit(
                        normalized = normalizedChar.toString(),
                        originalOffset = if (keepOriginalOffsets) index else output.size,
                    )
            }
        }
    }
    return output
}

private fun isPresentationOnlyCharacter(ch: Char): Boolean =
    ch == '\u2581' ||
        ch.isWhitespace() ||
        when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.FORMAT.toInt(),
            -> true
            else -> false
        }
