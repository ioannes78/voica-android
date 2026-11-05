package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.ai.OfflineSummaryResponseFormat
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Stage 17.3 R17B-1: QA-only FINAL replay from an explicitly imported Stage 17.3
 * quality diagnostic text. It NEVER touches production Room/checkpoints or MAP/REDUCE.
 * The exact FINAL input is reused across all candidates; only schema/runtime caps vary.
 */
internal object Stage17R17BReplay {
    const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
    private const val TEMPLATE_RESERVE = 192
    private const val SAFETY_RESERVE = 256

    data class Source(
        val finalPrompt: String,
        val baselineRaw: String,
        val inputDigest: String,
        val estimatedPromptTokens: Int,
        val semanticNodeCount: Int,
        val allowedEvidenceRefs: Set<String>,
        val promptSha256: String,
    )

    enum class Case(
        val label: String,
        val context: Int,
        val maxItemsPerSection: Int,
        val outputCap: Int,
    ) {
        B1_EXPANDED_4K("B1 · 4K / 4×4 / 1024", 4_000, 4, 1_024),
        B2_EXPANDED_8K("B2 · 8K / 4×4 / 1024", 8_000, 4, 1_024),
        B3_EXTENDED_OUTPUT_8K("B3 · 8K / 4×4 / 2048", 8_000, 4, 2_048),
    }

    data class Request(
        val testCase: Case,
        val prompt: String,
        val config: LocalLlmRuntimeConfig,
        val estimatedPromptTokens: Int,
        val allowedEvidenceRefs: Set<String>,
    )

    data class Assessment(
        val valid: Boolean,
        val totalItems: Int,
        val sections: Int,
        val evidenceLinkedItems: Int,
        val distinctEvidenceRefs: Int,
        val transcriptStatedItems: Int,
        val syntheticItems: Int,
        val error: String?,
    ) {
        fun concise(): String =
            if (!valid) "JSON FAIL: ${error ?: "unknown"}"
            else "JSON PASS · sections=$sections · items=$totalItems · " +
                "evidenceLinked=$evidenceLinkedItems · distinctEvidence=$distinctEvidenceRefs · " +
                "transcriptStated=$transcriptStatedItems · synthesis=$syntheticItems"
    }

    data class Run(
        val testCase: Case,
        val rawOutput: String,
        val runtimeDiagnostic: String,
        val assessment: Assessment,
    )

    fun readBounded(source: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = source.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            require(output.size() + count <= MAX_IMPORT_BYTES) { "诊断文件超过4 MiB上限" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun parseDiagnostic(text: String): Source {
        require(text.length <= MAX_IMPORT_BYTES) { "诊断文本超过4 MiB上限" }
        require(text.startsWith("# WENZHI Stage 17.3 Long Summary Quality Diagnostic")) {
            "仅支持闻知 Stage 17.3 导出的长文本诊断"
        }
        require(Regex("""(?m)^formatVersion=1\s*$""").containsMatchIn(text)) {
            "诊断文件版本不匹配"
        }
        val input = readSection(text, "FINAL INPUT")
        val output = readSection(text, "FINAL OUTPUT")
        require(input.contains("长转写离线总结 FINAL 引擎") &&
            input.contains("<INTERMEDIATE_DATA>") &&
            input.contains("</INTERMEDIATE_DATA>") &&
            input.contains("[NODE=")) {
            "FINAL prompt 不是受支持的长文本结构"
        }
        require(output.startsWith("{")) { "FINAL OUTPUT 不存在完整 JSON 对象" }
        val refs = Regex("""S\d{5,}""").findAll(input).map { it.value }.toCollection(LinkedHashSet())
        val digest =
            Regex("""(?m)^inputDigest=([a-f0-9]{64})\s*$""")
                .find(text)?.groupValues?.get(1) ?: "not-available"
        val count = Regex("""\[NODE=\d+\]""").findAll(input).count()
        val estimated = ConservativeOfflineSummaryTokenEstimator.estimateTokens(input)
        require(estimated + 1_024 + TEMPLATE_RESERVE + SAFETY_RESERVE <= 4_000) {
            "FINAL prompt 超过正式4K预算：$estimated 估算token；不能做4K对照"
        }
        return Source(
            finalPrompt = input,
            baselineRaw = output,
            inputDigest = digest,
            estimatedPromptTokens = estimated,
            semanticNodeCount = count,
            allowedEvidenceRefs = refs,
            promptSha256 = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    fun request(source: Source, testCase: Case): Request {
        val required =
            source.estimatedPromptTokens + testCase.outputCap +
                TEMPLATE_RESERVE + SAFETY_RESERVE
        require(required <= testCase.context) {
            "${testCase.label}预算不够：需要$required，设置${testCase.context}"
        }
        val productionSchema = OfflineSummaryResponseFormat.jsonSchema(source.allowedEvidenceRefs)
        val schema =
            if (testCase.maxItemsPerSection == OfflineSummaryResponseFormat.MAX_ITEMS_PER_SECTION) {
                productionSchema
            } else {
                val existing = "\"maxItems\":${OfflineSummaryResponseFormat.MAX_ITEMS_PER_SECTION}"
                require(productionSchema.split(existing).size == 2) {
                    "不能安全定位 FINAL item schema，拒绝本轮试验"
                }
                productionSchema.replace(existing, "\"maxItems\":${testCase.maxItemsPerSection}")
            }
        return Request(
            testCase = testCase,
            prompt = source.finalPrompt,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = LocalLlmBackendPolicy.CPU,
                maxNumTokens = testCase.context,
                maxOutputTokens = testCase.outputCap,
                responseJsonSchema = schema,
            ),
            estimatedPromptTokens = source.estimatedPromptTokens,
            allowedEvidenceRefs = source.allowedEvidenceRefs,
        )
    }

    fun evaluate(raw: String, source: Source): Assessment =
        try {
            val result = SummaryResultCodec.decode(raw, source.allowedEvidenceRefs)
            val items = result.sections.flatMap { it.items }
            Assessment(
                valid = true,
                totalItems = items.size,
                sections = result.sections.size,
                evidenceLinkedItems = items.count { it.evidenceRefs.isNotEmpty() },
                distinctEvidenceRefs = items.flatMap { it.evidenceRefs }.toSet().size,
                transcriptStatedItems = items.count { it.epistemicStatus == AiEpistemicStatus.TRANSCRIPT_STATED },
                syntheticItems = items.count { it.epistemicStatus != AiEpistemicStatus.TRANSCRIPT_STATED },
                error = null,
            )
        } catch (error: Exception) {
            Assessment(false, 0, 0, 0, 0, 0, 0,
                "${error::class.java.simpleName}: ${error.message?.take(140) ?: "n/a"}")
        }

    fun report(source: Source, runs: List<Run>): String = buildString {
        appendLine("# WENZHI Stage 17.3 R17B-1 · FINAL 同源对照")
        appendLine("QA-ONLY; no changes to production Room or summary history")
        appendLine("inputDigest=${source.inputDigest}")
        appendLine("finalPromptSHA256=${source.promptSha256}")
        appendLine("estimatedPromptTokens=${source.estimatedPromptTokens}")
        appendLine("semanticNodes=${source.semanticNodeCount}")
        appendLine("allowedEvidenceRefs=${source.allowedEvidenceRefs.size}")
        appendLine("IMPORTANT: Evidence membership is not semantic support; inspect original transcript.")
        appendLine()
        appendLine("===== 0 · Original R16.6 FINAL =====")
        appendLine(evaluate(source.baselineRaw, source).concise())
        appendLine(source.baselineRaw)
        runs.forEach { run ->
            appendLine()
            appendLine("===== ${run.testCase.label} =====")
            appendLine(run.assessment.concise())
            appendLine(run.runtimeDiagnostic)
            appendLine(run.rawOutput)
        }
        appendLine()
        appendLine("===== FROZEN FINAL INPUT (contains user transcript-derived information) =====")
        appendLine(source.finalPrompt)
        appendLine()
        appendLine("人工复核：逐项核对数字、专名、案例、主张、否定、风险及其原文语义；")
        appendLine("不得把引证ID有效、条目数量或JSON通过，等同于事实准确率。")
    }

    private fun readSection(text: String, name: String): String {
        val marker = "===== $name ====="
        val i = text.indexOf(marker)
        require(i >= 0) { "缺少 $name 段落" }
        val start = i + marker.length
        val next = Regex("""(?m)^===== [^\r\n]+ =====\s*$""")
            .find(text, start)?.range?.first ?: text.length
        val result = text.substring(start, next).trim()
        require(result.isNotBlank()) { "$name 段落为空" }
        return result
    }
}
