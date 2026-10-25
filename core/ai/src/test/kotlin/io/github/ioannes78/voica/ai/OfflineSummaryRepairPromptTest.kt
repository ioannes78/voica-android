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

        assertEquals(170202, rendered.promptVersion)
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
                        SummaryStructuredOutputErrorCode.TRUNCATED_JSON,
                        "truncated",
                    ),
            )

        assertTrue(rendered.allowedEvidenceRefs.isEmpty())
        assertTrue(rendered.text.contains("无可用音频 Evidence"))
        assertTrue(rendered.text.contains("所有新 evidenceRefs 必须为 []"))
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
        val invalid =
            """{"schemaVersion":1,"contentType":"MEETING","classificationConfidence":0.9,"title":"发布安排","overview":"确认发布与测试窗口","sections":[{"id":"s1","type":"ACTION_ITEM","label":"行动项","items":[{"id":"i1","text":"周四完成测试，周五发布。","evidenceRefs":["S99999"],"epistemicStatus":"TRANSCRIPT_STATED","attributes":{}}]}]}"""
        val rendered =
            OfflineSummaryRepairPromptRenderer().render(
                input = input(units),
                invalidOutput = invalid,
                validationError =
                    SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF,
                        "unknown evidence refs: S99999",
                    ),
            )

        val assessment = OfflineSummaryDirectBudgetGate().requireFits(rendered)
        val catalogueEntryCount = Regex("(?m)^S\\d{5}\\|").findAll(rendered.text).count()

        assertTrue(assessment.allowed)
        assertEquals(80, rendered.allowedEvidenceRefs.size)
        assertEquals(80, rendered.sourceUnitCount)
        assertTrue(catalogueEntryCount in 1 until 80)
        assertTrue(rendered.text.contains("S00080|发布窗口确定为周五，测试需要在周四结束。"))
        assertTrue(rendered.text.contains(invalid))
    }

    private fun input(withEvidence: Boolean): StructuredTranscriptInput =
        input(
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
                                    endSampleIndexExclusive = 8_000L,
                                )
                            } else {
                                null
                            },
                        text = "测试内容",
                        detectedLanguage = "zh",
                    ),
                ),
        )

    private fun input(units: List<StructuredTranscriptUnit>): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording-1",
            transcriptionId = "transcription-1",
            transcriptionRevisionId = "revision-1",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-1",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "pcm16k-mono",
            totalSampleCount = 640_000L,
            languageConfig = "zh",
            alignmentId = "alignment-1",
            units = units,
        )
}
