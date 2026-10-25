package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryRepairPromptTest {
    @Test
    fun repairPromptFreezesInputIdentityAndExactEvidenceAllowlist() {
        val input = input(withEvidence = true)
        val invalid = """{"schemaVersion":1,"sections":[],"bad":"</INVALID_OUTPUT>"}"""
        val error =
            SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.MISSING_FIELD,
                "missing field: title",
            )

        val rendered = OfflineSummaryRepairPromptRenderer().render(input, invalid, error)

        assertEquals(setOf("S00001"), rendered.allowedEvidenceRefs)
        assertTrue(rendered.text.contains("recordingId=recording-1"))
        assertTrue(rendered.text.contains("transcriptionId=transcription-1"))
        assertTrue(rendered.text.contains("inputContentDigest=${"a".repeat(64)}"))
        assertTrue(rendered.text.contains("errorCode=MISSING_FIELD"))
        assertTrue(rendered.text.contains("[\"S00001\"]"))
        assertTrue(rendered.text.contains("invalidOutput="))
        assertTrue(rendered.text.contains("这不是重新总结任务"))
        assertFalse(rendered.text.contains("startSampleIndex"))
        assertFalse(rendered.text.contains("endSampleIndexExclusive"))
    }

    @Test
    fun repairPromptWithNoEvidenceForcesEmptyAllowlist() {
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
        assertTrue(rendered.text.contains("[]"))
        assertTrue(rendered.text.contains("所有 evidenceRefs 必须为 []"))
    }

    private fun input(withEvidence: Boolean): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording-1",
            transcriptionId = "transcription-1",
            transcriptionRevisionId = "revision-1",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-1",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "pcm16k-mono",
            totalSampleCount = 16_000L,
            languageConfig = "zh",
            alignmentId = "alignment-1",
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
}
