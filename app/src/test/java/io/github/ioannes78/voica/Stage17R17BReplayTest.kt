package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.OfflineSummaryResponseFormat
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17BReplayTest {
    private val finalInput = """
        你是“闻知 WENZHI”的长转写离线总结 FINAL 引擎。
        生成最终简体中文结构化总结，只用输入已有信息，不用外部知识补全。
        <INTERMEDIATE_DATA>
        [NODE=1]I1|FACT|TRANSCRIPT_STATED|某公司研发投入增加|U00001|S00001
        [NODE=2]I2|FACT|TRANSCRIPT_STATED|医疗设备应用增长|U00002|S00002
        </INTERMEDIATE_DATA>
        仅返回完整 JSON 对象，不要 Markdown、代码围栏、前言或后记。
    """.trimIndent()

    private val finalOutput = """
        {"schemaVersion":1,"contentType":"OTHER","classificationConfidence":null,
        "title":"测试","overview":"核心事实",
        "sections":[{"id":"s1","type":"SUMMARY","label":"摘要",
        "items":[{"id":"i1","text":"研发投入增加","evidenceRefs":["S00001"],
        "epistemicStatus":"TRANSCRIPT_STATED","attributes":{}}]}]}
    """.trimIndent()

    private fun diagnostic(
        input: String = finalInput,
        output: String = finalOutput,
    ): String = buildString {
        appendLine("# WENZHI Stage 17.3 Long Summary Quality Diagnostic")
        appendLine("formatVersion=1")
        appendLine("inputDigest=${"a".repeat(64)}")
        appendLine("plannedChunks=2")
        appendLine()
        appendLine("===== MAP 1/2 INPUT =====")
        appendLine("source text from one of the real chunks")
        appendLine()
        appendLine("===== MAP 1/2 OUTPUT =====")
        appendLine("""{"items":[]}""")
        appendLine()
        appendLine("===== FINAL INPUT =====")
        appendLine(input)
        appendLine()
        appendLine("===== FINAL OUTPUT =====")
        appendLine(output)
    }
    @Test
    fun parsesExactQualityDiagnosticAndChecksEvidenceScope() {
        val source = Stage17R17BReplay.parseDiagnostic(diagnostic())
        assertEquals(finalInput, source.finalPrompt)
        assertEquals(finalOutput, source.baselineRaw)
        assertEquals(2, source.semanticNodeCount)
        assertEquals(setOf("S00001", "S00002"), source.allowedEvidenceRefs)
        assertEquals("a".repeat(64), source.inputDigest)
        assertEquals(64, source.promptSha256.length)
        val baseline = Stage17R17BReplay.evaluate(source.baselineRaw, source)
        assertTrue(baseline.concise(), baseline.valid)
        assertEquals(1, baseline.totalItems)
        assertEquals(1, baseline.distinctEvidenceRefs)
        assertEquals(1, baseline.transcriptStatedItems)
    }

    @Test
    fun allFinalCandidatesShareIdenticalFrozenInputAndOnlyChangeTheirBudgets() {
        val source = Stage17R17BReplay.parseDiagnostic(diagnostic())
        val a = Stage17R17BReplay.request(source, Stage17R17BReplay.Case.B1_EXPANDED_4K)
        val b = Stage17R17BReplay.request(source, Stage17R17BReplay.Case.B2_EXPANDED_8K)
        val c = Stage17R17BReplay.request(source, Stage17R17BReplay.Case.B3_EXTENDED_OUTPUT_8K)
        assertEquals(a.prompt, b.prompt)
        assertEquals(b.prompt, c.prompt)
        assertEquals(a.config.responseJsonSchema, b.config.responseJsonSchema)
        assertEquals(b.config.responseJsonSchema, c.config.responseJsonSchema)
        assertEquals(4_000, a.config.maxNumTokens)
        assertEquals(8_000, b.config.maxNumTokens)
        assertEquals(8_000, c.config.maxNumTokens)
        assertEquals(1_024, a.config.maxOutputTokens)
        assertEquals(1_024, b.config.maxOutputTokens)
        assertEquals(2_048, c.config.maxOutputTokens)
        for (candidate in listOf(a, b, c)) {
            assertEquals(LocalLlmBackendPolicy.CPU, candidate.config.backendPolicy)
            assertEquals(a.estimatedPromptTokens, candidate.estimatedPromptTokens)
            assertEquals(a.allowedEvidenceRefs, candidate.allowedEvidenceRefs)
            assertTrue(
                candidate.estimatedPromptTokens + candidate.config.maxOutputTokens!! + 192 + 256
                    <= candidate.config.maxNumTokens,
            )
            val json = Json.parseToJsonElement(candidate.config.responseJsonSchema!!).jsonObject
            val maxSections = json["properties"]!!.jsonObject["sections"]!!.jsonObject
            assertEquals("4", maxSections["maxItems"].toString())
            val maxItem = maxSections["items"]!!.jsonObject["properties"]!!.jsonObject["items"]!!.jsonObject
            assertEquals("4", maxItem["maxItems"].toString())
        }
        val product = OfflineSummaryResponseFormat.jsonSchema(source.allowedEvidenceRefs)
        assertFalse(product == a.config.responseJsonSchema)
        assertTrue(product.contains("\"maxItems\":2"))
        assertEquals(2, OfflineSummaryResponseFormat.MAX_ITEMS_PER_SECTION)
    }

    @Test
    fun rejectsUnknownEvidenceAndMalformedFilesWithoutMutatingOriginal() {
        val source = Stage17R17BReplay.parseDiagnostic(diagnostic())
        val unknown = finalOutput.replace("S00001", "S99999")
        val invalid = Stage17R17BReplay.evaluate(unknown, source)
        assertFalse(invalid.valid)
        assertTrue(invalid.concise().contains("FAIL"))
        val text = Stage17R17BReplay.report(source, emptyList())
        assertTrue(text.contains("FROZEN FINAL INPUT"))
        assertTrue(text.contains("S00001"))
        assertTrue(runCatching { Stage17R17BReplay.parseDiagnostic("malicious") }.isFailure)
        assertTrue(runCatching {
            Stage17R17BReplay.readBounded(
                ByteArrayInputStream(ByteArray(Stage17R17BReplay.MAX_IMPORT_BYTES + 1)),
            )
        }.isFailure)
    }

    @Test
    fun rejectsOverBudgetFinalWithoutTruncatingPrompt() {
        val hugeNode = "[NODE=1]I1|FACT|TRANSCRIPT_STATED|" +
            "测试上下文".repeat(900) + "|U00001|S00001"
        val sourceText = finalInput.replace(
            "[NODE=1]I1|FACT|TRANSCRIPT_STATED|某公司研发投入增加|U00001|S00001",
            hugeNode,
        )
        assertTrue(runCatching {
            Stage17R17BReplay.parseDiagnostic(diagnostic(input = sourceText))
        }.isFailure)
    }
}
