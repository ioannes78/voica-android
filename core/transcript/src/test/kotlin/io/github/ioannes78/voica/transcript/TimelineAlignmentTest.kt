package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAlignmentTest {
    @Test
    fun transfersReferenceTimingToQwenTextAndInterpolatesMissingCharacter() {
        val segment = SpeechSegment(0L, 16_000L)
        val reference =
            listOf(
                timed("这", 1_000L),
                timed("个", 2_000L),
                timed("项", 3_000L),
                timed("目", 4_000L),
                timed("实", 6_000L),
                timed("施", 7_000L),
                timed("方", 8_000L),
                timed("案", 9_000L),
            )

        val result =
            alignFinalTextToReferenceTiming(
                finalText = "这个项目的实施方案。",
                referenceText = "这个项目实施方案",
                referenceTokens = reference,
                segment = segment,
            )

        assertTrue(result.quality != TimelineAlignmentQuality.FAILED)
        assertTrue(result.matchedRatio >= 0.85F)
        assertEquals("这个项目的实施方案", result.tokens.joinToString("") { it.text })
        val inserted = result.tokens.first { it.text == "的" }
        assertTrue(checkNotNull(inserted.startSampleIndex) in 4_000L..6_000L)
    }

    @Test
    fun punctuationAndCaseDoNotReduceUsefulAlignment() {
        val segment = SpeechSegment(0L, 20_000L)
        val result =
            alignFinalTextToReferenceTiming(
                finalText = "今天测试 OpenAI API。",
                referenceText = "今天测试 openai api",
                referenceTokens =
                    listOf(
                        timed("今", 1_000L),
                        timed("天", 2_000L),
                        timed("测", 3_000L),
                        timed("试", 4_000L),
                        timed(" openai", 5_000L),
                        timed(" api", 10_000L),
                    ),
                segment = segment,
            )

        assertTrue(result.quality != TimelineAlignmentQuality.FAILED)
        assertTrue(result.matchedRatio >= 0.85F)
        assertEquals("今天测试OpenAIAPI", result.tokens.joinToString("") { it.text })
    }

    @Test
    fun lowSimilarityFallsBackInsteadOfInventingPreciseTimeline() {
        val segment = SpeechSegment(0L, 16_000L)
        val result =
            alignFinalTextToReferenceTiming(
                finalText = "完全不同的内容",
                referenceText = "abcdefg",
                referenceTokens =
                    listOf(
                        timed("a", 1_000L),
                        timed("b", 2_000L),
                        timed("c", 3_000L),
                        timed("d", 4_000L),
                        timed("e", 5_000L),
                        timed("f", 6_000L),
                        timed("g", 7_000L),
                    ),
                segment = segment,
            )

        assertEquals(TimelineAlignmentQuality.FAILED, result.quality)
        assertTrue(result.tokens.isEmpty())
        assertTrue(result.matchedRatio < 0.60F)
    }

    private fun timed(
        text: String,
        start: Long,
    ) =
        TranscriptToken(
            text = text,
            startSampleIndex = start,
            endSampleIndexExclusive = null,
            source = TokenSource.FIRST_PASS,
        )
}
