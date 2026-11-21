package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import java.security.MessageDigest

/** Stage 17.3 R17C P2-C1: short, QA-only numeric fidelity control on pinned LiteRT-LM 0.18.0. */
internal object Stage17R17CP2CNumericQa {
    const val CONTEXT = 4_000
    const val OUTPUT = 512
    enum class Case(val label: String, val policy: LocalLlmBackendPolicy) {
        CPU("P2-C1 · CPU 4K/512 数字对照", LocalLlmBackendPolicy.CPU),
        GPU("P2-C1 · GPU 4K/512 数字对照", LocalLlmBackendPolicy.GPU),
    }

    // Curated transcript-derived QA fixture, not an externally verified statistical source.
    val prompt: String = buildString {
        appendLine("你是闻知 WENZHI 的数字保真 QA 助手。以下是来自录音转写的五条待核实资料。")
        appendLine("只依据资料用简体中文逐项复述为[1]至[5]五个短句，不要JSON或额外开场。")
        appendLine("保留全部年份、数字、单位与数量级，不要编造，不要混入无关的外语。")
        appendLine("<FACTS>")
        appendLine("[1] 图灵测试中的 GPT-4.5 示例提到 87%。")
        appendLine("[2] 标普500企业财报提及AI的比例，从2015年接近零到2025年初接近50%。")
        appendLine("[3] FDA批准的AI医疗设备数量，从1995年的1个增加到2023年的223个。")
        appendLine("[4] 法律AI企业的年经常性收入，15个月内从1000万美元增长至7000万美元。")
        appendLine("[5] 资料称新药研发时间可缩短三到八成（30%到80%）。")
        appendLine("</FACTS>")
        append("请逐项输出五条短句。")
    }
    val promptSha256: String = sha256(prompt)

    data class Request(
        val testCase: Case,
        val prompt: String,
        val promptSha256: String,
        val estimatedPromptTokens: Int,
        val config: LocalLlmRuntimeConfig,
    )
    data class Assessment(
        val matched: Int,
        val total: Int,
        val missing: List<String>,
        val knownErrors: List<String>,
        val languageSignals: List<String>,
    ) {
        val requiresReview: Boolean
            get() = matched != total || knownErrors.isNotEmpty() || languageSignals.isNotEmpty()
        fun summary(): String =
            "referenceMatch=" + matched + "/" + total +
                " errors=" + knownErrors.size + " languageSignals=" + languageSignals.size +
                " " + if (requiresReview) "REVIEW_REQUIRED" else "NO_KNOWN_FLAG_MANUAL_REVIEW_STILL_REQUIRED"
    }
    data class Run(
        val testCase: Case,
        val output: String,
        val runtimeDiagnostic: String,
        val assessment: Assessment,
    )

    fun request(testCase: Case): Request {
        val estimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt)
        require(estimate.toLong() + OUTPUT + 192 + 256 <= CONTEXT) {
            "QA固定输入超过4K预算，不能截断"
        }
        return Request(
            testCase = testCase, prompt = prompt, promptSha256 = promptSha256,
            estimatedPromptTokens = estimate,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = testCase.policy, maxNumTokens = CONTEXT,
                maxOutputTokens = OUTPUT, speculativeDecoding = false, responseJsonSchema = null,
            ),
        )
    }

    /** Literal QA sentinels, not general semantic or external fact verification. */
    fun assess(output: String): Assessment {
        val expected = listOf(
            "87%" to listOf(Regex("""(?<!\d)87\s*[%％]""")),
            "标普500/2015/2025/50%" to listOf(
                Regex("""标普\s*500"""), Regex("""(?<!\d)2015(?!\d)"""),
                Regex("""(?<!\d)2025(?!\d)"""), Regex("""(?<!\d)50\s*[%％]""")),
            "FDA/1995/2023/223" to listOf(
                Regex("""FDA""", RegexOption.IGNORE_CASE), Regex("""(?<!\d)1995(?!\d)"""),
                Regex("""(?<!\d)2023(?!\d)"""), Regex("""(?<!\d)223(?!\d)""")),
            "15个月/1000万美元/7000万美元" to listOf(
                Regex("""(?<!\d)15\s*个?月"""),
                Regex("""(?<!\d)1000\s*万\s*美元"""),
                Regex("""(?<!\d)7000\s*万\s*美元""")),
            "三到八成或30%-80%" to listOf(
                Regex("""三到八成|(?<!\d)30\s*[%％]\s*(?:到|至|[-~～—–])\s*80\s*[%％]""")),
        )
        val missing = expected.filter { (_, patterns) ->
            patterns.any { !it.containsMatchIn(output) }
        }.map { it.first }
        val defects = listOf(
            "87->877%" to Regex("""(?<!\d)877\s*[%％]"""),
            "500->50500" to Regex("""(?<!\d)50500(?!\d)"""),
            "2015->20015" to Regex("""(?<!\d)20015(?!\d)"""),
            "2025->20205" to Regex("""(?<!\d)20205(?!\d)"""),
            "223->2223" to Regex("""(?<!\d)2223(?!\d)"""),
            "1000万->100万" to Regex("""(?<!\d)100\s*万\s*美元"""),
            "7000万->700万" to Regex("""(?<!\d)700\s*万\s*美元"""),
            "三到八成->3%-8%" to Regex("""(?<!\d)3\s*[%％]\s*(?:到|至|[-~～—–])\s*8\s*[%％]"""),
        ).filter { (_, pattern) -> pattern.containsMatchIn(output) }.map { it.first }
        val mixed = buildList {
            if (Regex("""[\u3040-\u30FF\uAC00-\uD7AF]""").containsMatchIn(output)) add("日文/韩文字符")
            if (Regex("""(?i)\bbillions\b""").containsMatchIn(output)) add("billions")
            if (output.contains("mọi")) add("mọi")
        }
        return Assessment(expected.size - missing.size, expected.size, missing, defects, mixed)
    }

    fun runtimePass(
        testCase: Case, actual: LocalLlmBackend, fallback: Boolean,
        idle: Boolean, closedPssKb: Long?,
    ): Boolean = actual == when (testCase) {
        Case.CPU -> LocalLlmBackend.CPU
        Case.GPU -> LocalLlmBackend.GPU
    } && !fallback && idle && closedPssKb != null

    fun report(runs: List<Run>, blocked: Boolean, shortProbePassed: Boolean): String = buildString {
        appendLine("# WENZHI R17C P2-C1 · CPU/GPU Numeric Fidelity Control")
        appendLine("QA ONLY; 0.18.0; 4K/512; no native schema; no product change")
        appendLine("blocked=" + blocked)
        appendLine("shortProbePassed=" + shortProbePassed)
        appendLine("promptSHA=" + promptSha256)
        appendLine("ATTENTION: source-derived fixture, NOT externally fact-checked.")
        appendLine("===== FROZEN INPUT =====")
        appendLine(prompt)
        runs.forEach { run ->
            appendLine()
            appendLine("===== " + run.testCase.label + " =====")
            appendLine(run.runtimeDiagnostic)
            appendLine("numericAssessment=" + run.assessment.summary())
            appendLine("missing=" + run.assessment.missing.joinToString(" | "))
            appendLine("knownErrors=" + run.assessment.knownErrors.joinToString(" | "))
            appendLine("languageSignals=" + run.assessment.languageSignals.joinToString(" | "))
            appendLine("===== RAW OUTPUT =====")
            appendLine(run.output)
        }
        appendLine("WARNING: no automatic fact pass; compare raw text and source/units manually.")
        appendLine("One successful GPU request does not certify 8K or structured GPU production.")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
