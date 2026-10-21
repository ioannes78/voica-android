package io.github.ioannes78.voica.sherpa

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrCtcTokenTimingTest {
    @Test
    fun compactsSplitUtf8CharacterAcrossItsRealAcousticSpan() {
        val vocab = createFixtureVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val ids = tokenizer.encode("我叫郗明。你好。")
            val alignment = aligned(ids, step = 100L, width = 80L)

            val tokens =
                requireNotNull(
                    buildFunAsrCtcRelativeTokens(
                        tokenizer = tokenizer,
                        targetTokenIds = ids,
                        alignment = alignment,
                        acceptedSampleCount = 2_000L,
                    ),
                )

            assertEquals(listOf("我", "叫", "郗", "明", "。", "你", "好", "。"), tokens.map { it.text })
            val fragmented = tokens.single { it.text == "郗" }
            assertEquals(200L, fragmented.startSampleOffset)
            assertEquals(480L, fragmented.endSampleOffsetExclusive)

            // Internal punctuation is zero-width at the end of the preceding spoken token.
            val middlePunctuation = tokens[4]
            assertEquals(580L, middlePunctuation.startSampleOffset)
            assertEquals(580L, middlePunctuation.endSampleOffsetExclusive)

            // Official policy leaves trailing punctuation untouched.
            val trailingPunctuation = tokens.last()
            assertEquals(900L, trailingPunctuation.startSampleOffset)
            assertEquals(980L, trailingPunctuation.endSampleOffsetExclusive)
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun keepsMultiCharacterBpeAsOneAcousticToken() {
        val vocab = createBaseByteVocab(extra = listOf("你好".toByteArray() to 256))
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val ids = tokenizer.encode("你好")
            assertEquals(1, ids.size)

            val tokens =
                requireNotNull(
                    buildFunAsrCtcRelativeTokens(
                        tokenizer = tokenizer,
                        targetTokenIds = ids,
                        alignment =
                            listOf(
                                CtcAlignedTokenSample(
                                    targetIndex = 0,
                                    tokenId = ids[0],
                                    startSampleOffset = 120L,
                                    endSampleOffsetExclusive = 720L,
                                    peakLogProbability = 4.0F,
                                ),
                            ),
                        acceptedSampleCount = 1_000L,
                    ),
                )

            assertEquals(1, tokens.size)
            assertEquals("你好", tokens.single().text)
            assertEquals(120L, tokens.single().startSampleOffset)
            assertEquals(720L, tokens.single().endSampleOffsetExclusive)
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun spokenSemanticSymbolKeepsItsNonZeroSpan() {
        val vocab = createBaseByteVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val ids = tokenizer.encode("A$B")
            val tokens =
                requireNotNull(
                    buildFunAsrCtcRelativeTokens(
                        tokenizer = tokenizer,
                        targetTokenIds = ids,
                        alignment = aligned(ids, step = 100L, width = 80L),
                        acceptedSampleCount = 500L,
                    ),
                )

            val dollar = tokens.single { it.text == "$" }
            assertTrue(dollar.endSampleOffsetExclusive!! > dollar.startSampleOffset!!)
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun refusesIncompleteUtf8TargetSequence() {
        val vocab = createBaseByteVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val ids = intArrayOf(0xE9)

            assertNull(
                buildFunAsrCtcRelativeTokens(
                    tokenizer = tokenizer,
                    targetTokenIds = ids,
                    alignment = aligned(ids, step = 100L, width = 80L),
                    acceptedSampleCount = 500L,
                ),
            )
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun refusesAlignmentWhoseTargetIdentityDoesNotMatchTokenizerSequence() {
        val vocab = createBaseByteVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val ids = tokenizer.encode("AB")
            val bad = aligned(ids, step = 100L, width = 80L).toMutableList()
            bad[1] = bad[1].copy(tokenId = ids[1] + 1)

            assertNull(
                buildFunAsrCtcRelativeTokens(
                    tokenizer = tokenizer,
                    targetTokenIds = ids,
                    alignment = bad,
                    acceptedSampleCount = 500L,
                ),
            )
        } finally {
            vocab.delete()
        }
    }

    private fun aligned(
        ids: IntArray,
        step: Long,
        width: Long,
    ): List<CtcAlignedTokenSample> =
        ids.mapIndexed { index, id ->
            val start = index * step
            CtcAlignedTokenSample(
                targetIndex = index,
                tokenId = id,
                startSampleOffset = start,
                endSampleOffsetExclusive = start + width,
                peakLogProbability = 1.0F,
            )
        }

    private fun createFixtureVocab(): File {
        val lines = baseByteLines().toMutableList()
        var nextRank = 256
        for (char in "我叫明你好。，") {
            val bytes = char.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size == 3)
            lines += encodeLine(bytes.copyOfRange(0, 2), nextRank++)
            lines += encodeLine(bytes, nextRank++)
        }
        return writeTempVocab(lines)
    }

    private fun createBaseByteVocab(extra: List<Pair<ByteArray, Int>> = emptyList()): File {
        val lines = baseByteLines().toMutableList()
        extra.forEach { (bytes, rank) -> lines += encodeLine(bytes, rank) }
        return writeTempVocab(lines)
    }

    private fun baseByteLines(): List<String> =
        List(256) { byte -> encodeLine(byteArrayOf(byte.toByte()), byte) }

    private fun encodeLine(
        bytes: ByteArray,
        rank: Int,
    ): String = "${Base64.getEncoder().encodeToString(bytes)} $rank"

    private fun writeTempVocab(lines: List<String>): File =
        File.createTempFile("funasr-ctc-timing-", ".tiktoken").apply {
            writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        }
}
