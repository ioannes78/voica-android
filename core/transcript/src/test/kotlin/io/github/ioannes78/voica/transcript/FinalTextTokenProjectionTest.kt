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
    fun emptyTokenListIsUnavailable() {
        val projection = projectTokensToFinalText("文本", emptyList())

        assertEquals(TextProjectionQuality.UNAVAILABLE, projection.quality)
        assertEquals(listOf(0), projection.boundaries.toList())
    }
}
