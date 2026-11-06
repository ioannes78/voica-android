package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryRepairPromptTest {
    @Test
    fun repairPromptKeepsInvalidOutputAndGroundsReplacementRefsInEvidenceCatalogue() {
        val input = input(withEvidence = true)
        val invalid = """{"schemaVersion":1,"sections":[],"bad":"S99999"}"""
        val error =
            SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF,
                "unknown evidence refs: S99999",
            )

        val rendered = OfflineSummaryRepairPromptRenderer().render(input, invalid, error)

        assertEquals(170204, rendered.promptVersion)
        assertEquals(setOf("S00001"), rendered.allowedEvidenceRefs)
        assertTrue(rendered.text.contains("error=INVALID_EVIDENCE_REF"))
        assertTrue(rendered.text.contains("Evidence Catalogue"))
        assertTrue(rendered.text.contains("S00001|测试内容"))
        assertTrue(rendered.text.contains("未列出的 ref 不得作为新的替换值"))
        assertTrue(rendered.text.contains("BEGIN_INVALID_JSON_DATA\n$invalid\nEND_INVALID_JSON_DATA"))
        assertTrue(rendered.text.contains("不是重新总结"))
        assertFalse(rendered.text.contains("recordingId="))
        assertFalse(rendered.text.contains("inputContentDigest="))
        assertFalse(rendered.text.contains("startSampleIndex"))
        assertFalse(rendered.text.contains("endSampleIndexExclusive"))
        assertTrue(OfflineSummaryDirectBudgetGate().requireFits(rendered).allowed)
    }

    @Test
    fun repairPromptWithNoEvidenceForcesEmptyNewEvidenceRefs() {
        val rendered =
            OfflineSummaryRepairPromptRenderer().render(
                input = input(withEvidence = false),
                invalidOutput = "{bad json",
                validationError =
                    SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.INVALID_JSON,
                        "invalid",
                    ),
            )

        assertTrue(rendered.allowedEvidenceRefs.isEmpty())
        assertTrue(rendered.text.contains("无可用音频 Evidence"))
        assertTrue(rendered.text.contains("所有新 evidenceRefs 必须为 []"))
    }

    @Test
    fun truncatedJsonIsNotSentThroughSecondLlmRepair() {
        val failure =
            SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.TRUNCATED_JSON,
                "truncated",
            )

        val thrown =
            runCatching {
                OfflineSummaryRepairPromptRenderer().render(
                    input = input(withEvidence = true),
                    invalidOutput = "{\"schemaVersion\":1",
                    validationError = failure,
                )
            }.exceptionOrNull()

        assertTrue(thrown === failure)
    }

    @Test
    fun oversizedEvidenceCatalogueIsDeterministicallyBoundedWithoutTruncatingInvalidOutput() {
        val units =
            (1..80).map { index ->
                val relevant = index == 80
                StructuredTranscriptUnit(
                    evidence =
                        EvidenceSourceRef(
                            ref = "S" + index.toString().padStart(5, '0'),
                            sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                            sourceId = "segment-$index",
                            speakerId = null,
                            speakerDisplayName = null,
                            assignmentQuality = null,
                            overlap = false,
                            ambiguous = false,
                            startSampleIndex = (index - 1) * 8_000L,
                            endSampleIndexExclusive = index * 8_000L,
                        ),
                    text =
                        if (relevant) {
                            "发布窗口确定为周五，测试需要在周四结束。"
                        } else {
                            "背景材料第${index}段，讨论一般事项。" + "内容".repeat(35)
                        },
                    detectedLanguage = "zh",
                )
            }
        val largeInput =
            input(withEvidence = true).copy(
                totalSampleCount = 80L * 8_000L,
                units = units,
            )
        val invalid =
            """{"schemaVersion":1,"contentType":"GENERAL","classificationConfidence":null,"title":"发布","overview":"发布窗口确定为周五，测试需要在周四结束。","sections":[],"bad":"S99999"}"""

        val rendered =
            OfflineSummaryRepairPromptRenderer().render(
                input = largeInput,
                invalidOutput = invalid,
                validationError =
                    SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF,
                        "unknown evidence refs: S99999",
                    ),
            )

        val assessment = OfflineSummaryDirectBudgetGate().requireFits(rendered)
        assertTrue(assessment.allowed)
        assertTrue(rendered.text.contains("BEGIN_INVALID_JSON_DATA\n$invalid\nEND_INVALID_JSON_DATA"))
        assertTrue(rendered.text.contains("S00080|发布窗口确定为周五，测试需要在周四结束。"))
        assertEquals(80, rendered.allowedEvidenceRefs.size)
    }

    private fun input(withEvidence: Boolean): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording-1",
            transcriptionId = "transcription-1",
            transcriptionRevisionId = null,
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-1",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 16_000L,
            languageConfig = "zh",
            alignmentId = null,
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            if (withEvidence) {
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
                                )
                            } else {
                                null
                            },
                        text = "测试内容",
                        detectedLanguage = "zh",
                    ),
                ),
        )
}
