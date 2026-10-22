package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class FinalTextTokenProjectionTest {
    @Test
    fun exactProjectionAttachesInsertedPunctuationToPreviousToken() {
        val projection =
            projectTokensToFinalText(
                finalText = "你好，世界。",
                tokens = listOf("你好", "世界"),
            )

        assertEquals(TextProjectionQuality.EXACT, projection.quality)
        assertEquals(listOf(0, 3, 6), projection.boundaries.toList())
    }

    @Test
    fun senseVoiceBoundaryMarkersSpacesAndAsciiCaseRemainExact() {
        val projection =
            projectTokensToFinalText(
                finalText = "欢迎收听，今天聊 AI 趋势。",
                tokens = listOf("欢", "迎", "收", "听", "▁今", "天", "聊", " ai", "趋", "势"),
            )

        assertEquals(TextProjectionQuality.EXACT, projection.quality)
        assertEquals(finalTextTokenStarts("欢迎收听，今天聊 AI 趋势。", listOf("欢", "迎", "收", "听", "今", "天", "聊", "AI", "趋", "势")), projection.boundaries.toList())
    }

    @Test
    fun nonMatchingItnFallsBackWithoutClaimingExactness() {
        val projection =
            projectTokensToFinalText(
                finalText = "100元。",
                tokens = listOf("一百", "元"),
            )

        assertEquals(TextProjectionQuality.HEURISTIC, projection.quality)
        assertEquals(listOf(0, 3, 5), projection.boundaries.toList())
    }

    @Test
    fun lexicalMismatchStillFallsBack() {
        val projection =
            projectTokensToFinalText(
                finalText = "人工智能。",
                tokens = listOf("人工", "智障"),
            )

        assertEquals(TextProjectionQuality.HEURISTIC, projection.quality)
    }

    @Test
    fun emptyTokenListIsUnavailable() {
        val projection = projectTokensToFinalText("文本", emptyList())

        assertEquals(TextProjectionQuality.UNAVAILABLE, projection.quality)
        assertEquals(listOf(0), projection.boundaries.toList())
    }

    private fun finalTextTokenStarts(
        finalText: String,
        tokens: List<String>,
    ): List<Int> {
        val boundaries = mutableListOf(0)
        var cursor = 0
        tokens.drop(1).forEach { token ->
            val found = finalText.indexOf(token, startIndex = cursor, ignoreCase = true)
            check(found >= 0)
            boundaries += found
            cursor = found + token.length
        }
        boundaries += finalText.length
        return boundaries
    }
}
