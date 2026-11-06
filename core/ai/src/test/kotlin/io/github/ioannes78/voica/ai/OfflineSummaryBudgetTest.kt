package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryBudgetTest {
    @Test
    fun conservativeEstimatorIsDeterministicAcrossChineseAsciiWhitespaceAndSupplementaryUnicode() {
        val estimator = ConservativeOfflineSummaryTokenEstimator

        assertEquals(3, estimator.estimateTokens("你好"))
        assertEquals(3, estimator.estimateTokens("abcdef"))
        assertEquals(4, estimator.estimateTokens("a   b"))
        assertEquals(3, estimator.estimateTokens("😀"))
        assertEquals(
            estimator.estimateTokens("会议 abc 123"),
            estimator.estimateTokens("会议 abc 123"),
        )
        assertEquals("CONSERVATIVE_TEXT_V1", estimator.id)
    }

    @Test
    fun defaultBudgetUsesValidatedFourThousandTokenEnvelopeWithExplicitReserves() {
        val spec = OfflineSummaryDirectBudgetSpec()

        assertEquals(4_000, spec.runtimeMaxTokens)
        assertEquals(1_024, spec.outputReserveTokens)
        assertEquals(192, spec.runtimeTemplateReserveTokens)
        assertEquals(256, spec.safetyReserveTokens)
        assertEquals(1_472, spec.reservedTokens)
        assertEquals(2_528, spec.maxEstimatedPromptTokens)
    }

    @Test
    fun budgetAllowsBelowAndExactlyAtBoundaryAndRejectsAbove() {
        val spec =
            OfflineSummaryDirectBudgetSpec(
                runtimeMaxTokens = 100,
                outputReserveTokens = 20,
                runtimeTemplateReserveTokens = 10,
                safetyReserveTokens = 10,
            )

        val below = gateWithEstimate(59, spec).assess(prompt("below"))
        val boundary = gateWithEstimate(60, spec).assess(prompt("boundary"))
        val over = gateWithEstimate(61, spec).assess(prompt("over"))

        assertEquals(60, spec.maxEstimatedPromptTokens)
        assertTrue(below.allowed)
        assertTrue(boundary.allowed)
        assertFalse(over.allowed)
        assertEquals(100, boundary.estimatedTotalWithReserves)
        assertEquals(101, over.estimatedTotalWithReserves)
    }

    @Test
    fun rejectionNeverReturnsATruncatedPrompt() {
        val rendered = prompt("完整输入不得被截断：" + "内容".repeat(100))
        val originalText = rendered.text
        val gate =
            gateWithEstimate(
                estimate = 61,
                spec =
                    OfflineSummaryDirectBudgetSpec(
                        runtimeMaxTokens = 100,
                        outputReserveTokens = 20,
                        runtimeTemplateReserveTokens = 10,
                        safetyReserveTokens = 10,
                    ),
            )

        val failure = runCatching { gate.requireFits(rendered) }.exceptionOrNull()

        assertTrue(failure is OfflineSummaryBudgetExceededException)
        assertEquals(originalText, rendered.text)
        val assessment = (failure as OfflineSummaryBudgetExceededException).assessment
        assertFalse(assessment.allowed)
        assertEquals(61, assessment.estimatedPromptTokens)
        assertEquals(60, assessment.maxEstimatedPromptTokens)
    }

    @Test
    fun acceptedPreflightReturnsDiagnosticsWithoutReplacingRenderedPrompt() {
        val rendered = prompt("原始完整 prompt")
        val gate = gateWithEstimate(42, OfflineSummaryDirectBudgetSpec())

        val assessment = gate.requireFits(rendered)

        assertTrue(assessment.allowed)
        assertEquals(42, assessment.estimatedPromptTokens)
        assertEquals("原始完整 prompt", rendered.text)
        assertEquals("FIXED_42", assessment.estimatorId)
    }

    @Test
    fun realRendererShortTranscriptFitsInitialDirectSummaryBudget() {
        val input =
            StructuredTranscriptInput(
                recordingId = "recording",
                transcriptionId = "transcription",
                inputContentDigest = "a".repeat(64),
                transcriptionMode = "HIGH_QUALITY",
                canonicalAssetId = "canonical",
                canonicalSha256 = "b".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = 16_000L,
                languageConfig = "zh",
                alignmentId = null,
                units =
                    listOf(
                        StructuredTranscriptUnit(
                            evidence =
                                EvidenceSourceRef(
                                    ref = "S00001",
                                    sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                    sourceId = "segment-1",
                                    speakerId = null,
                                    speakerDisplayName = null,
                                    assignmentQuality = null,
                                    overlap = false,
                                    ambiguous = false,
                                    startSampleIndex = 0L,
                                    endSampleIndexExclusive = 16_000L,
                                ),
                            text = "今天确认第一版周五发布，测试需要在周四结束。",
                            detectedLanguage = "zh",
                        ),
                    ),
            )
        val rendered = OfflineSummaryPromptRenderer().render(input)

        val assessment = OfflineSummaryDirectBudgetGate().requireFits(rendered)

        assertTrue(assessment.allowed)
        assertTrue(assessment.estimatedPromptTokens > 0)
        assertTrue(assessment.estimatedTotalWithReserves <= assessment.runtimeMaxTokens)
    }

    @Test
    fun largeDirectTranscriptIsRejectedInsteadOfSilentlyTruncated() {
        val rendered =
            RenderedOfflineSummaryPrompt(
                promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
                resultSchemaVersion = OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION,
                text = "会议内容".repeat(4_000),
                allowedEvidenceRefs = emptySet(),
                sourceUnitCount = 1,
            )
        val gate = OfflineSummaryDirectBudgetGate()

        val assessment = gate.assess(rendered)

        assertFalse(assessment.allowed)
        assertEquals("会议内容".repeat(4_000), rendered.text)
        assertTrue(assessment.estimatedPromptTokens > assessment.maxEstimatedPromptTokens)
    }

    private fun prompt(text: String) =
        RenderedOfflineSummaryPrompt(
            promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
            resultSchemaVersion = OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION,
            text = text,
            allowedEvidenceRefs = emptySet(),
            sourceUnitCount = 1,
        )

    private fun gateWithEstimate(
        estimate: Int,
        spec: OfflineSummaryDirectBudgetSpec,
    ): OfflineSummaryDirectBudgetGate =
        OfflineSummaryDirectBudgetGate(
            estimator =
                object : OfflineSummaryTokenEstimator {
                    override val id = "FIXED_$estimate"

                    override fun estimateTokens(text: String): Int = estimate
                },
            spec = spec,
        )
}
