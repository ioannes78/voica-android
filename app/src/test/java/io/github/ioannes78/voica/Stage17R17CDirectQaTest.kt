package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17CDirectQaTest {
    private val source = Stage17R17CDirectQa.Source(
        recordingId = "recording-r17c",
        displayName = "真实测试录音.m4a",
        durationMs = 507_000,
        transcriptionId = "transcription-v1",
        revisionId = null,
        inputDigest = "a".repeat(64),
        transcriptText = "第一段讨论人工智能技术增长与算力需求。\n\n第二段讨论企业应用与风险。",
        transcriptSha256 = "b".repeat(64),
        unitCount = 2,
        evidenceRows = listOf("U00001 | S00001 | 第一段", "U00002 | S00002 | 第二段"),
    )

    @Test fun frozenEffectiveSnapshotKeepsFullTextAndLineage() {
        val input = StructuredTranscriptInput(
            recordingId = "recording-r17c",
            transcriptionId = "transcription-v1",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "high_quality",
            canonicalAssetId = "asset",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "profile",
            totalSampleCount = 0,
            languageConfig = "zh",
            alignmentId = null,
            units = listOf(
                StructuredTranscriptUnit(evidence = null, text = "第一段讨论人工智能技术增长与算力需求。", detectedLanguage = "zh"),
                StructuredTranscriptUnit(evidence = null, text = "第二段讨论企业应用与风险。", detectedLanguage = "zh"),
            ),
        )
        val result = Stage17R17CDirectQa.sourceFromSnapshot(
            recordingId = "recording-r17c",
            displayName = "真实测试录音.m4a",
            durationMs = 507_000,
            snapshot = input,
        )
        assertEquals(source.transcriptText, result.transcriptText)
        assertEquals(source.inputDigest, result.inputDigest)
        assertEquals(2, result.unitCount)
        assertEquals(64, result.transcriptSha256.length)
        assertTrue(result.evidenceRows.all { it.contains("NO_EVIDENCE") })
    }

    @Test fun everyTierUsesIdenticalWholePromptAndCpuNoSchema() {
        val runs = Stage17R17CDirectQa.Tier.entries.map { tier ->
            Stage17R17CDirectQa.request(source, tier)
        }
        assertEquals(runs[0].prompt, runs[1].prompt)
        assertEquals(runs[1].prompt, runs[2].prompt)
        assertEquals(runs[0].promptSha256, runs[2].promptSha256)
        for (run in runs) {
            assertEquals(LocalLlmBackendPolicy.CPU, run.config.backendPolicy)
            assertEquals(null, run.config.responseJsonSchema)
            assertFalse(run.config.speculativeDecoding)
            assertTrue(run.prompt.contains(source.transcriptText))
            assertTrue(
                run.estimatedPromptTokens + run.tier.outputCap + 192 + 256 <= run.tier.context,
            )
        }
        assertEquals(4_000, runs[0].config.maxNumTokens)
        assertEquals(8_000, runs[1].config.maxNumTokens)
        assertEquals(16_000, runs[2].config.maxNumTokens)
        assertEquals(2_048, runs[2].config.maxOutputTokens)
    }

    @Test fun oversizedPromptFailsBeforeAnyRuntimeRequestWithoutTruncation() {
        val oversized = source.copy(transcriptText = "完整会议逐字稿".repeat(3_000))
        val error = runCatching {
            Stage17R17CDirectQa.request(oversized, Stage17R17CDirectQa.Tier.C4K)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("原文未截断"))
        assertTrue(Stage17R17CDirectQa.renderPrompt(oversized.transcriptText).contains(oversized.transcriptText))
    }

    @Test fun reportContainsOriginalTranscriptAndManualEvidenceAuditOnly() {
        val output = "AI算力需求提高，但仍存在不确定性。"
        val report = Stage17R17CDirectQa.report(
            source = source,
            runs = listOf(
                Stage17R17CDirectQa.Run(
                    tier = Stage17R17CDirectQa.Tier.C4K,
                    rawOutput = output,
                    runtimeDiagnostic = "resolved=CPU",
                ),
            ),
            finalDiagnosticDigest = source.inputDigest,
        )
        assertTrue(report.contains("sameDigest=true"))
        assertTrue(report.contains(source.transcriptText))
        assertTrue(report.contains("U00001 | S00001"))
        assertTrue(report.contains(output))
        assertTrue(report.contains("人工"))
    }
}
