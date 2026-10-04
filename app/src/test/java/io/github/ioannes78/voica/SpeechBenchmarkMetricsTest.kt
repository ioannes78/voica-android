package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechBenchmarkMetricsTest {
    @Test
    fun cerIgnoresWhitespaceCaseAndPunctuation() {
        assertEquals(
            0.0,
            speechBenchmarkCer(
                reference = "今天，Hello 世界！",
                hypothesis = "今天 hello世界",
            )!!,
            0.000001,
        )
    }

    @Test
    fun cerMeasuresUnicodeCodePointEdits() {
        assertEquals(
            0.25,
            speechBenchmarkCer(
                reference = "语音转写",
                hypothesis = "语音转录",
            )!!,
            0.000001,
        )
    }

    @Test
    fun werUsesNormalizedWhitespaceSeparatedWords() {
        assertEquals(
            1.0 / 3.0,
            speechBenchmarkWer(
                reference = "hello world again",
                hypothesis = "hello word again",
            )!!,
            0.000001,
        )
    }

    @Test
    fun emptyReferenceHasNoAccuracyMetric() {
        assertNull(speechBenchmarkCer(" ！？ ", "hello"))
        assertNull(speechBenchmarkWer(" ！？ ", "hello"))
    }
}
