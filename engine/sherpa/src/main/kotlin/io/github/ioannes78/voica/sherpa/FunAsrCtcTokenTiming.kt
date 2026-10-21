package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.transcript.RelativeTimedToken
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Converts aligned SenseVoice CTC target ids into product timed tokens without fabricating
 * character-level precision.
 *
 * Tiktoken mergeable tokens are byte sequences. A Unicode character can therefore span several
 * target ids. We compact adjacent ids only until their concatenated bytes form valid UTF-8, using
 * the first aligned start and last aligned end. Conversely, a single valid BPE token may decode to
 * several characters; it remains one product token with one acoustic span and is never split by
 * interpolation.
 */
internal fun buildFunAsrCtcRelativeTokens(
    tokenizer: SenseVoiceCtcTokenizer,
    targetTokenIds: IntArray,
    alignment: List<CtcAlignedTokenSample>,
    acceptedSampleCount: Long,
): List<RelativeTimedToken>? {
    require(acceptedSampleCount >= 0L)
    if (targetTokenIds.isEmpty()) {
        return if (alignment.isEmpty()) emptyList() else null
    }
    if (alignment.size != targetTokenIds.size) return null

    var previousStart = -1L
    alignment.forEachIndexed { index, token ->
        if (token.targetIndex != index || token.tokenId != targetTokenIds[index]) return null
        if (
            token.startSampleOffset < 0L ||
            token.endSampleOffsetExclusive > acceptedSampleCount ||
            token.endSampleOffsetExclusive <= token.startSampleOffset ||
            token.startSampleOffset < previousStart
        ) {
            return null
        }
        previousStart = token.startSampleOffset
    }

    val compact = ArrayList<MutableCtcProductToken>()
    val pendingBytes = ByteArrayOutputStream()
    var pendingStart = -1L
    var pendingEnd = -1L

    targetTokenIds.forEachIndexed { index, tokenId ->
        val span = alignment[index]
        if (pendingStart < 0L) pendingStart = span.startSampleOffset
        pendingEnd = span.endSampleOffsetExclusive
        pendingBytes.write(tokenizer.tokenBytes(tokenId))

        val decoded = decodeUtf8Strict(pendingBytes.toByteArray())
        if (decoded != null) {
            if (decoded.isEmpty()) return null
            compact +=
                MutableCtcProductToken(
                    text = decoded,
                    startSampleOffset = pendingStart,
                    endSampleOffsetExclusive = pendingEnd,
                )
            pendingBytes.reset()
            pendingStart = -1L
            pendingEnd = -1L
        }
    }

    // An incomplete UTF-8 sequence means target ids/tokenizer or alignment metadata disagree.
    if (pendingBytes.size() != 0) return null

    val decodedFull = tokenizer.decode(targetTokenIds)
    if (compact.joinToString(separator = "") { it.text } != decodedFull) return null

    anchorCtcSentencePunctuation(compact)

    return compact.map { token ->
        RelativeTimedToken(
            text = token.text,
            startSampleOffset = token.startSampleOffset,
            endSampleOffsetExclusive = token.endSampleOffsetExclusive,
        )
    }
}

private data class MutableCtcProductToken(
    val text: String,
    var startSampleOffset: Long,
    var endSampleOffsetExclusive: Long,
)

/** Mirrors the current FunASR punctuation anchoring policy without collapsing spoken symbols. */
private fun anchorCtcSentencePunctuation(tokens: MutableList<MutableCtcProductToken>) {
    var lastSpokenEnd: Long? = null
    tokens.forEachIndexed { index, token ->
        when (classifyCtcProductToken(token.text)) {
            CtcProductTokenKind.SPOKEN -> lastSpokenEnd = token.endSampleOffsetExclusive
            CtcProductTokenKind.SPECIAL -> Unit
            CtcProductTokenKind.SENTENCE_PUNCTUATION -> {
                val anchor = lastSpokenEnd ?: return@forEachIndexed
                val hasFollowingSpoken =
                    tokens.subList(index + 1, tokens.size).any {
                        classifyCtcProductToken(it.text) == CtcProductTokenKind.SPOKEN
                    }
                if (hasFollowingSpoken) {
                    token.startSampleOffset = anchor
                    token.endSampleOffsetExclusive = anchor
                }
            }
        }
    }
}

private enum class CtcProductTokenKind {
    SPOKEN,
    SENTENCE_PUNCTUATION,
    SPECIAL,
}

private fun classifyCtcProductToken(text: String): CtcProductTokenKind {
    if (text.length >= 2 && text.first() == '<' && text.last() == '>' && '>' !in text.dropLast(1)) {
        return CtcProductTokenKind.SPECIAL
    }
    if (text.isNotEmpty() && text.all { it in CTC_SENTENCE_PUNCTUATION }) {
        return CtcProductTokenKind.SENTENCE_PUNCTUATION
    }
    return CtcProductTokenKind.SPOKEN
}

private fun decodeUtf8Strict(bytes: ByteArray): String? =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

private val CTC_SENTENCE_PUNCTUATION = setOf('。', '！', '？', '，', '、', '；', '：', '.', ',', '!', '?', ';', ':')
