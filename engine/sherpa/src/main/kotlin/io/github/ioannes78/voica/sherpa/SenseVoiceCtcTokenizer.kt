package io.github.ioannes78.voica.sherpa

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.Pattern

/**
 * Minimal SenseVoice-compatible tiktoken encoder for FunASR Nano CTC forced alignment.
 *
 * Official FunASR re-encodes the final LLM transcription through SenseVoiceTokenizer before CTC
 * forced alignment. That production path uses only the mergeable ranks from multilingual.tiktoken
 * for ordinary text. Special-token strings are deliberately treated as ordinary text during this
 * re-encoding, so this Android implementation intentionally implements ordinary-text BPE only.
 *
 * The model package owns the exact .tiktoken resource and its digest. This class does not embed or
 * guess a vocabulary and is not activated by revision-1 FunASR packages.
 */
internal class SenseVoiceCtcTokenizer private constructor(
    private val rankByBytes: Map<ByteKey, Int>,
    private val bytesByRank: Map<Int, ByteArray>,
) {
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()
        val output = ArrayList<Int>()
        val matcher = TOKEN_PATTERN.matcher(text)
        var consumed = 0
        while (matcher.find()) {
            check(matcher.start() == consumed) {
                "SenseVoice tokenizer regex left unmatched text at UTF-16 index $consumed"
            }
            val piece = matcher.group()
            output += encodePiece(piece.toByteArray(StandardCharsets.UTF_8)).toList()
            consumed = matcher.end()
        }
        check(consumed == text.length) {
            "SenseVoice tokenizer regex left unmatched trailing text at UTF-16 index $consumed"
        }
        return output.toIntArray()
    }

    fun decode(tokenIds: IntArray): String {
        val out = ByteArrayOutputStream()
        tokenIds.forEach { tokenId ->
            val bytes = bytesByRank[tokenId]
                ?: error("Unknown SenseVoice CTC mergeable token id: $tokenId")
            out.write(bytes)
        }
        return out.toByteArray().toString(StandardCharsets.UTF_8)
    }

    fun decodeToken(tokenId: Int): String {
        val bytes = bytesByRank[tokenId]
            ?: error("Unknown SenseVoice CTC mergeable token id: $tokenId")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun encodePiece(piece: ByteArray): IntArray {
        if (piece.isEmpty()) return intArrayOf()
        rankByBytes[ByteKey(piece)]?.let { return intArrayOf(it) }

        val parts = ArrayList<ByteArray>(piece.size)
        piece.forEach { byte ->
            val single = byteArrayOf(byte)
            require(rankByBytes.containsKey(ByteKey(single))) {
                "SenseVoice CTC vocabulary is missing base byte 0x${byte.toUByte().toString(16)}"
            }
            parts += single
        }

        while (parts.size > 1) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for (index in 0 until parts.lastIndex) {
                val merged = concat(parts[index], parts[index + 1])
                val rank = rankByBytes[ByteKey(merged)] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            parts[bestIndex] = concat(parts[bestIndex], parts[bestIndex + 1])
            parts.removeAt(bestIndex + 1)
        }

        return IntArray(parts.size) { index ->
            rankByBytes[ByteKey(parts[index])]
                ?: error("SenseVoice CTC BPE produced an unknown byte piece")
        }
    }

    companion object {
        fun fromTiktokenFile(file: File): SenseVoiceCtcTokenizer {
            require(file.isFile) { "SenseVoice CTC tokenizer file is missing: ${file.path}" }
            val rankByBytes = HashMap<ByteKey, Int>()
            val bytesByRank = HashMap<Int, ByteArray>()
            file.useLines(StandardCharsets.UTF_8) { lines ->
                lines.forEachIndexed { zeroBasedLine, rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEachIndexed
                    val split = line.lastIndexOf(' ')
                    require(split > 0 && split < line.lastIndex) {
                        "Invalid .tiktoken line ${zeroBasedLine + 1}"
                    }
                    val bytes =
                        try {
                            Base64.getDecoder().decode(line.substring(0, split))
                        } catch (error: IllegalArgumentException) {
                            throw IllegalArgumentException(
                                "Invalid base64 in .tiktoken line ${zeroBasedLine + 1}",
                                error,
                            )
                        }
                    val rank = line.substring(split + 1).toIntOrNull()
                        ?: error("Invalid rank in .tiktoken line ${zeroBasedLine + 1}")
                    require(rank >= 0) { "Negative rank in .tiktoken line ${zeroBasedLine + 1}" }
                    val key = ByteKey(bytes)
                    require(rankByBytes.put(key, rank) == null) {
                        "Duplicate byte token in .tiktoken line ${zeroBasedLine + 1}"
                    }
                    require(bytesByRank.put(rank, bytes) == null) {
                        "Duplicate rank $rank in .tiktoken line ${zeroBasedLine + 1}"
                    }
                }
            }
            require(rankByBytes.isNotEmpty()) { "SenseVoice CTC tokenizer vocabulary is empty" }
            return SenseVoiceCtcTokenizer(rankByBytes, bytesByRank)
        }

        // Exact ordinary-text split pattern used by current SenseVoiceTokenizer/tiktoken encoding.
        private val TOKEN_PATTERN =
            Pattern.compile(
                "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
            )
    }
}

private class ByteKey(
    private val value: ByteArray,
) {
    private val hash = value.contentHashCode()

    override fun equals(other: Any?): Boolean =
        other is ByteKey && value.contentEquals(other.value)

    override fun hashCode(): Int = hash
}

private fun concat(
    left: ByteArray,
    right: ByteArray,
): ByteArray = ByteArray(left.size + right.size).also { merged ->
    left.copyInto(merged, 0)
    right.copyInto(merged, left.size)
}
