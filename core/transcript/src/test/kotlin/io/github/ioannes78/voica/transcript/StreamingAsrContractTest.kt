package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAsrContractTest {
    @Test
    fun defaultNonFinalHypothesisIsPartial() {
        val hypothesis =
            AsrHypothesis(
                text = "你好",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        assertFalse(hypothesis.isFinal)
        assertEquals(AsrHypothesisStability.PARTIAL, hypothesis.stability)
    }

    @Test
    fun stableHypothesisRemainsNonFinal() {
        val hypothesis =
            AsrHypothesis(
                text = "你好",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
                stability = AsrHypothesisStability.STABLE,
            )

        assertFalse(hypothesis.isFinal)
        assertEquals(AsrHypothesisStability.STABLE, hypothesis.stability)
    }

    @Test
    fun defaultFinalHypothesisIsFinalStability() {
        val hypothesis =
            AsrHypothesis(
                text = "你好。",
                punctuationCapability = PunctuationCapability.RELIABLE,
                isFinal = true,
            )

        assertTrue(hypothesis.isFinal)
        assertEquals(AsrHypothesisStability.FINAL, hypothesis.stability)
    }

    @Test
    fun rejectsFinalStabilityWhenIsFinalIsFalse() {
        assertThrows(IllegalArgumentException::class.java) {
            AsrHypothesis(
                text = "bad",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
                stability = AsrHypothesisStability.FINAL,
            )
        }
    }

    @Test
    fun rejectsPartialStabilityWhenIsFinalIsTrue() {
        assertThrows(IllegalArgumentException::class.java) {
            AsrHypothesis(
                text = "bad",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
                stability = AsrHypothesisStability.PARTIAL,
            )
        }
    }
}
