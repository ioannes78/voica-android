package io.github.ioannes78.voica.sherpa

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrCtcPerformanceDiagnosticsTest {
    @After
    fun tearDown() {
        FunAsrCtcPerformanceDiagnostics.clear()
    }

    @Test
    fun formatsMeasuredDecodeAndAlignmentWithoutInventingEncoderCount() {
        FunAsrCtcPerformanceDiagnostics.record(
            FunAsrCtcPerformanceDiagnostic(
                audioSampleCount = 32_000,
                audioDurationMs = 2_000,
                textDecodeMs = 900,
                alignmentMs = 200,
                totalMs = 1_100,
                targetTokenCount = 12,
                alignedTokenCount = 12,
                alignmentSucceeded = true,
                sameStreamAlignment = true,
                failureStage = null,
            ),
        )

        val text = FunAsrCtcPerformanceDiagnostics.formatForQa()

        assertTrue(text.contains("textDecodeMs=900"))
        assertTrue(text.contains("ctcAlignmentMs=200"))
        assertTrue(text.contains("measuredRtf=0.5500"))
        assertTrue(text.contains("sameStream=true"))
        assertTrue(text.contains("encoderInvocationCount=not_exposed"))
        assertEquals(1, FunAsrCtcPerformanceDiagnostics.snapshot().size)
    }

    @Test
    fun failedAlignmentKeepsMeasuredStageVisible() {
        FunAsrCtcPerformanceDiagnostics.record(
            FunAsrCtcPerformanceDiagnostic(
                audioSampleCount = 16_000,
                audioDurationMs = 1_000,
                textDecodeMs = 450,
                alignmentMs = 50,
                totalMs = 500,
                targetTokenCount = 6,
                alignedTokenCount = null,
                alignmentSucceeded = false,
                sameStreamAlignment = true,
                failureStage = "PARSE",
            ),
        )

        val text = FunAsrCtcPerformanceDiagnostics.formatForQa()

        assertTrue(text.contains("alignmentSucceeded=false"))
        assertTrue(text.contains("failureStage=PARSE"))
    }

    @Test
    fun emptyStateIsExplicitForQa() {
        assertEquals(
            "No FunASR CTC performance diagnostics yet.",
            FunAsrCtcPerformanceDiagnostics.formatForQa(),
        )
    }

    @Test
    fun clearRemovesPriorQaRecords() {
        FunAsrCtcPerformanceDiagnostics.record(
            FunAsrCtcPerformanceDiagnostic(
                audioSampleCount = 16_000,
                audioDurationMs = 1_000,
                textDecodeMs = 500,
                alignmentMs = null,
                totalMs = 500,
                targetTokenCount = 0,
                alignedTokenCount = null,
                alignmentSucceeded = false,
                sameStreamAlignment = true,
                failureStage = "TOKENIZE",
            ),
        )

        FunAsrCtcPerformanceDiagnostics.clear()

        assertTrue(FunAsrCtcPerformanceDiagnostics.snapshot().isEmpty())
    }
}
