package io.github.ioannes78.voica.sherpa

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceCtcTokenizerTest {
    @Test
    fun encodesAndDecodesOfficialStyleByteFragmentFixture() {
        val vocab = createFixtureVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val text = "我叫郗明。你好。"

            val ids = tokenizer.encode(text)

            assertArrayEquals(
                intArrayOf(
                    257,
                    259,
                    0xE9,
                    0x83,
                    0x97,
                    261,
                    267,
                    263,
                    265,
                    267,
                ),
                ids,
            )
            assertEquals(text, tokenizer.decode(ids))
            assertEquals("�", tokenizer.decodeToken(0xE9))
            assertEquals("�", tokenizer.decodeToken(0x83))
            assertEquals("�", tokenizer.decodeToken(0x97))
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun appliesLowestRankMergeBeforeLaterMerge() {
        val vocab = createAsciiMergeVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)

            // a+b must merge first (rank 256), enabling ab+c (rank 258).
            // b+c exists too but has the later rank 257 and therefore must lose.
            assertArrayEquals(intArrayOf(258), tokenizer.encode("abc"))
            assertEquals("abc", tokenizer.decode(intArrayOf(258)))
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun regexPreservesSpacesNumbersLettersAndPunctuation() {
        val vocab = createBaseByteVocab()
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
            val text = " OpenAI 3.5，中文!\nnext"

            val ids = tokenizer.encode(text)

            assertTrue(ids.isNotEmpty())
            assertEquals(text, tokenizer.decode(ids))
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun parsesPythonPermissiveBase64LikeTiktokenLoader() {
        val lines = baseByteLines().toMutableList()
        // Python base64.b64decode(validate=false) ignores '-' and decodes this as "BC".
        lines += "Qk-M= 256"
        // It also accepts an all-padding field as the empty byte sequence.
        lines += "= 257"
        val vocab = writeTempVocab(lines)
        try {
            val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)

            assertArrayEquals(intArrayOf(256), tokenizer.encode("BC"))
            assertArrayEquals(byteArrayOf(), tokenizer.tokenBytes(257))
        } finally {
            vocab.delete()
        }
    }

    @Test
    fun matchesPinnedModelscopeTiktokenReferenceWhenProvided() {
        val vocabPath = System.getenv("VOICA_FUNASR_CTC_TIKTOKEN") ?: return
        val vectorsPath =
            System.getenv("VOICA_FUNASR_CTC_PARITY_VECTORS")
                ?: error("VOICA_FUNASR_CTC_PARITY_VECTORS is required when real tokenizer parity is enabled")
        val vocab = File(vocabPath)
        val vectors = File(vectorsPath)
        assertTrue("Pinned multilingual.tiktoken is missing: $vocabPath", vocab.isFile)
        assertTrue("Reference parity vectors are missing: $vectorsPath", vectors.isFile)

        val tokenizer = SenseVoiceCtcTokenizer.fromTiktokenFile(vocab)
        var checked = 0
        vectors.readLines(Charsets.UTF_8).forEachIndexed { zeroBasedLine, rawLine ->
            val line = rawLine.trimEnd()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2) {
                "Invalid A3 tokenizer parity vector line ${zeroBasedLine + 1}"
            }
            val text =
                Base64.getDecoder().decode(parts[0]).toString(Charsets.UTF_8)
            val expected =
                if (parts[1].isEmpty()) {
                    intArrayOf()
                } else {
                    parts[1].split(',').map(String::toInt).toIntArray()
                }

            assertArrayEquals(
                "SenseVoice target-id parity mismatch for fixture line ${zeroBasedLine + 1}: $text",
                expected,
                tokenizer.encode(text),
            )
            assertEquals(
                "SenseVoice decode parity mismatch for fixture line ${zeroBasedLine + 1}",
                text,
                tokenizer.decode(expected),
            )
            checked += 1
        }
        assertTrue("A3 tokenizer parity vector set must not be empty", checked > 0)
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

    private fun createAsciiMergeVocab(): File {
        val lines = baseByteLines().toMutableList()
        lines += encodeLine("ab".toByteArray(), 256)
        lines += encodeLine("bc".toByteArray(), 257)
        lines += encodeLine("abc".toByteArray(), 258)
        return writeTempVocab(lines)
    }

    private fun createBaseByteVocab(): File = writeTempVocab(baseByteLines())

    private fun baseByteLines(): List<String> =
        List(256) { byte -> encodeLine(byteArrayOf(byte.toByte()), byte) }

    private fun encodeLine(
        bytes: ByteArray,
        rank: Int,
    ): String = "${Base64.getEncoder().encodeToString(bytes)} $rank"

    private fun writeTempVocab(lines: List<String>): File =
        File.createTempFile("sensevoice-ctc-", ".tiktoken").apply {
            writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        }
}
