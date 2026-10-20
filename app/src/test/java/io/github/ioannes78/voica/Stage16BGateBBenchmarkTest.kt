package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateBBenchmarkTest {
    @Test
    fun `B1 first pass corpus stays frozen at eight discriminatory profiles`() {
        assertEquals(
            listOf(
                "ZH_BASE",
                "ZH_LONG",
                "EN_BASE",
                "ZH_EN_MIXED",
                "NUMBERS_ITN_RAW",
                "PROPER_NOUNS",
                "BOUNDARY",
                "PAUSE",
            ),
            Stage16BGateBB1Profile.entries.map { it.name },
        )
    }

    @Test
    fun `Chinese CER ignores punctuation and whitespace`() {
        val reference = Stage16BGateBB1Profile.ZH_BASE.referenceText
        val hypothesis = reference.replace("，", " ").replace("。", "")

        val quality = scoreStage16BGateBB1(Stage16BGateBB1Profile.ZH_BASE, hypothesis)

        assertEquals(0.0, quality.characterErrorRate ?: error("CER missing"), 0.0)
        assertEquals(0, quality.character.errors)
        assertFalse(quality.wholeProfileBlank)
    }

    @Test
    fun `SenseVoice language metadata is excluded from B1 scoring`() {
        val reference = Stage16BGateBB1Profile.ZH_BASE.referenceText
        val hypothesis = "<zh>$reference\n<yue>"

        val quality = scoreStage16BGateBB1(Stage16BGateBB1Profile.ZH_BASE, hypothesis)

        assertEquals(0.0, quality.characterErrorRate ?: error("CER missing"), 0.0)
        assertEquals(0, quality.character.errors)
        assertFalse(quality.wholeProfileBlank)
    }

    @Test
    fun `SenseVoice metadata only output remains blank for B1`() {
        val quality =
            scoreStage16BGateBB1(
                Stage16BGateBB1Profile.ZH_BASE,
                "<zh>\n<en>\n<ja>\n<ko>\n<yue>",
            )

        assertTrue(quality.wholeProfileBlank)
    }

    @Test
    fun `English profile uses explicit token WER and ignores punctuation casing`() {
        val reference = Stage16BGateBB1Profile.EN_BASE.referenceText
        val hypothesis = reference.lowercase().replace(",", "").replace(".", "")

        val quality = scoreStage16BGateBB1(Stage16BGateBB1Profile.EN_BASE, hypothesis)

        assertEquals(0.0, quality.wordErrorRate ?: error("WER missing"), 0.0)
        assertEquals(0, quality.word?.errors)
    }

    @Test
    fun `English WER excludes SenseVoice language metadata`() {
        val reference = Stage16BGateBB1Profile.EN_BASE.referenceText
        val quality =
            scoreStage16BGateBB1(
                Stage16BGateBB1Profile.EN_BASE,
                "<en>$reference",
            )

        assertEquals(0.0, quality.wordErrorRate ?: error("WER missing"), 0.0)
        assertEquals(0, quality.word?.errors)
    }

    @Test
    fun `boundary profile exposes head and tail deletion evidence`() {
        val quality =
            scoreStage16BGateBB1(
                Stage16BGateBB1Profile.BOUNDARY,
                "中间这一句保持正常语速。",
            )

        assertTrue(quality.headDeletion == true)
        assertTrue(quality.tailDeletion == true)
    }

    @Test
    fun `boundary profile keeps complete head and tail when present`() {
        val quality =
            scoreStage16BGateBB1(
                Stage16BGateBB1Profile.BOUNDARY,
                Stage16BGateBB1Profile.BOUNDARY.referenceText,
            )

        assertFalse(quality.headDeletion ?: true)
        assertFalse(quality.tailDeletion ?: true)
    }

    @Test
    fun `duplicate nonblank frozen chunk output is surfaced for review`() {
        val quality =
            scoreStage16BGateBB1(
                Stage16BGateBB1Profile.PAUSE,
                "测试重复\n测试重复\n其他内容",
            )

        assertEquals(1, quality.duplicateNonBlankLineCount)
    }

    @Test
    fun `frozen inputs preserve FSMN boundaries and split only oversized ranges`() {
        val pcm = ShortArray(100) { it.toShort() }
        val result =
            buildStage16BGateBFrozenInputs(
                pcm = pcm,
                speechSegments =
                    listOf(
                        SpeechSegment(0, 25),
                        SpeechSegment(40, 100),
                    ),
                maxChunkSamples = 30,
            )

        assertEquals(
            listOf(0L to 25L, 40L to 70L, 70L to 100L),
            result.map { it.startSampleIndex to it.endSampleIndexExclusive },
        )
        assertArrayEquals(pcm.copyOfRange(0, 25), result[0].samples)
        assertArrayEquals(pcm.copyOfRange(40, 70), result[1].samples)
        assertArrayEquals(pcm.copyOfRange(70, 100), result[2].samples)
    }
}
