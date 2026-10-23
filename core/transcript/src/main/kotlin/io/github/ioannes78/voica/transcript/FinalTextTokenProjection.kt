package io.github.ioannes78.voica.transcript

import java.text.Normalizer

enum class TextProjectionQuality {
    EXACT,
    HEURISTIC,
    UNAVAILABLE,
}

data class TokenTextProjection(
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val quality: TextProjectionQuality,
) {
    init {
        require(startOffset >= 0)
        require(endOffsetExclusive > startOffset)
    }
}

data class FinalTextTokenProjection(
    val boundaries: IntArray,
    val quality: TextProjectionQuality,
    val tokenProjections: List<TokenTextProjection?> = emptyList(),
) {
    init {
        require(boundaries.isNotEmpty())
        require(tokenProjections.isEmpty() || tokenProjections.size == boundaries.size - 1)
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
            tokenProjections =
                List(tokens.size) { index ->
                    val start = boundaries[index]
                    val end = boundaries[index + 1]
                    if (end > start) {
                        TokenTextProjection(start, end, TextProjectionQuality.EXACT)
                    } else {
                        null
                    }
                },
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
        tokenProjections = partialExactTokenProjections(finalText, tokens),
    )
}

/**
 * Maps native ASR token text to the product text without inventing lexical equivalence.
 * Presentation-only differences (punctuation, whitespace, tokenizer U+2581 boundaries, width and
 * ASCII case) are ignored. Content-changing transforms still fail the whole-exact path.
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

/**
 * When one lexical transform prevents a whole-segment exact projection, preserve only the provably
 * unchanged prefix and suffix tokens. We deliberately do not search across the ambiguous middle:
 * repeated Chinese characters could otherwise be attached to the wrong occurrence and create a
 * false word timestamp.
 */
private fun partialExactTokenProjections(
    finalText: String,
    tokens: List<String>,
): List<TokenTextProjection?> {
    val finalUnits = lexicalUnits(finalText, keepOriginalOffsets = true)
    val tokenUnits = tokens.map { lexicalUnits(it, keepOriginalOffsets = false) }
    if (finalUnits.isEmpty() || tokenUnits.any { it.isEmpty() }) {
        return List(tokens.size) { null }
    }

    val output = MutableList<TokenTextProjection?>(tokens.size) { null }
    var finalPrefixIndex = 0
    var firstUnmatchedToken = tokens.size
    for (tokenIndex in tokens.indices) {
        val units = tokenUnits[tokenIndex]
        if (!matchesAt(finalUnits, finalPrefixIndex, units)) {
            firstUnmatchedToken = tokenIndex
            break
        }
        val startIndex = finalPrefixIndex
        finalPrefixIndex += units.size
        output[tokenIndex] = exactRange(finalUnits, startIndex, finalPrefixIndex)
    }
    if (firstUnmatchedToken == tokens.size) return output

    var finalSuffixExclusive = finalUnits.size
    for (tokenIndex in tokens.lastIndex downTo firstUnmatchedToken) {
        val units = tokenUnits[tokenIndex]
        val startIndex = finalSuffixExclusive - units.size
        if (startIndex < finalPrefixIndex || !matchesAt(finalUnits, startIndex, units)) break
        output[tokenIndex] = exactRange(finalUnits, startIndex, finalSuffixExclusive)
        finalSuffixExclusive = startIndex
    }
    return output
}

private fun matchesAt(
    finalUnits: List<LexicalUnit>,
    startIndex: Int,
    tokenUnits: List<LexicalUnit>,
): Boolean {
    if (startIndex < 0 || startIndex + tokenUnits.size > finalUnits.size) return false
    return tokenUnits.indices.all { offset ->
        tokenUnits[offset].normalized == finalUnits[startIndex + offset].normalized
    }
}

private fun exactRange(
    finalUnits: List<LexicalUnit>,
    startIndex: Int,
    endIndexExclusive: Int,
): TokenTextProjection {
    val start = finalUnits[startIndex].originalOffset
    val end = finalUnits[endIndexExclusive - 1].originalOffset + 1
    return TokenTextProjection(
        startOffset = start,
        endOffsetExclusive = end,
        quality = TextProjectionQuality.EXACT,
    )
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
