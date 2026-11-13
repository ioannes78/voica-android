package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import java.security.MessageDigest

/**
 * R17C P2-C2 QA-only medium-length same-source CPU/GPU summary experiment.
 * Uses intact, contiguous original transcript paragraphs; no AI normalization.
 */
internal object Stage17R17CP2C2MediumQa {
    const val MIN_INPUT_TOKENS = 1_500
    const val MAX_INPUT_TOKENS = 2_000
    const val CONTEXT_TOKENS = 4_000
    const val OUTPUT_TOKENS = 1_024
    private const val TEMPLATE_RESERVE = 192
    private const val SAFETY_RESERVE = 256

    enum class Case(val label: String, val backend: LocalLlmBackendPolicy) {
        CPU("P2-C2-A · CPU 4K/1024 中等逐字稿", LocalLlmBackendPolicy.CPU),
        GPU("P2-C2-B · GPU 4K/1024 中等逐字稿", LocalLlmBackendPolicy.GPU),
    }

    data class FrozenInput(
        val source: Stage17R17CDirectQa.Source,
        val selectedText: String,
        val selectedSha256: String,
        val selectedParagraphs: Int,
        val prompt: String,
        val promptSha256: String,
        val estimate: Int,
        val evidence: List<String>,
    )

    data class Request(
        val testCase: Case,
        val frozen: FrozenInput,
        val config: LocalLlmRuntimeConfig,
    )

    data class Audit(
        val flagged: List<String>,
        val languageSignals: List<String>,
        val referenceMentions: List<String>,
        val missingReferenceMentions: List<String>,
    ) {
        fun concise(): String =
            "knownCorruptions=" + flagged.size +
                " · languageSignals=" + languageSignals.size +
                " · recognizedSourceFacts=" + referenceMentions.size +
                " · missingSourceFacts=" + missingReferenceMentions.size +
                " · MANUAL_REVIEW_REQUIRED"
    }

    data class Run(
        val testCase: Case,
        val rawOutput: String,
        val runtimeDiagnostic: String,
        val audit: Audit,
        val promptSha256: String,
    )

    /**
     * Choose the largest complete prefix whose *entire prompt* is 1500..2000
     * conservative tokens. This is an explicit truncated-source QA fixture,
     * never a claim of whole-recording summary.
     */
    fun freeze(source: Stage17R17CDirectQa.Source): FrozenInput {
        require(source.transcriptText.isNotBlank()) { "请选择有效的原始逐字稿" }
        val parts = source.transcriptText.split("\n\n").filter { it.isNotBlank() }
        require(parts.size > 1) { "逐字稿没有足够完整段落用于 C2" }
        var count = 0
        var chosen = ""
        var chosenPrompt = ""
        var tokens = 0
        for (index in 1..parts.size) {
            val candidateText = parts.take(index).joinToString("\n\n")
            val candidatePrompt = renderPrompt(candidateText)
            val candidateTokens = ConservativeOfflineSummaryTokenEstimator.estimateTokens(candidatePrompt)
            if (candidateTokens > MAX_INPUT_TOKENS) break
            chosen = candidateText
            chosenPrompt = candidatePrompt
            tokens = candidateTokens
            count = index
        }
        require(tokens in MIN_INPUT_TOKENS..MAX_INPUT_TOKENS) {
            "当前录音找不到1500–2000 token的完整前缀；未截断段落、未进入原生推理"
        }
        require(tokens.toLong() + OUTPUT_TOKENS + TEMPLATE_RESERVE + SAFETY_RESERVE <= CONTEXT_TOKENS) {
            "4K/1024 推理安全预算不足，拒绝"
        }
        return FrozenInput(
            source = source,
            selectedText = chosen,
            selectedSha256 = sha256(chosen),
            selectedParagraphs = count,
            prompt = chosenPrompt,
            promptSha256 = sha256(chosenPrompt),
            estimate = tokens,
            evidence = source.evidenceRows.take(count),
        )
    }

    fun request(frozen: FrozenInput, testCase: Case): Request {
        require(sha256(frozen.selectedText) == frozen.selectedSha256) { "片段已改变" }
        require(sha256(frozen.prompt) == frozen.promptSha256) { "冻结 Prompt 已改变" }
        return Request(
            testCase = testCase,
            frozen = frozen,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = testCase.backend,
                maxNumTokens = CONTEXT_TOKENS,
                maxOutputTokens = OUTPUT_TOKENS,
                responseJsonSchema = null,
                speculativeDecoding = false,
            ),
        )
    }

    /** Fixed QA prompt, not the production structured summary or P1 direct-full prompt. */
    fun renderPrompt(text: String): String = buildString {
        appendLine("你是闻知 WENZHI 的本地会议总结助手。")
        appendLine("仅针对下面这段原始语音转写（而非整场录音），用简体中文提炼关键事实与案例。")
        appendLine("优先保留具体数字、年份、金额、倍数、百分比、单位及其对应的主题。")
        appendLine("原文可能有识别错误，请把不确定的数字或专名标为待核实，不得猜测修复。")
        appendLine("以6至12个精炼要点输出，不要 JSON，不要引入外部事实或无关外语。")
        appendLine("<PARTIAL_TRANSCRIPT>")
        appendLine(text)
        appendLine("</PARTIAL_TRANSCRIPT>")
        append("请输出该片段的要点及需要核对的数字。")
    }

    /** Pure heuristics; never treat pattern absence as a factual quality PASS. */
    fun audit(frozen: FrozenInput, output: String): Audit {
        val prior = Stage17R17CP2CNumericQa.assess(output)
        val references = listOf(
            "87%" to Pair(Regex("""八十七趴|(?<!\d)87\s*[%％]"""), Regex("""八十七\s*(?:趴|百分之)?|(?<!\d)87\s*[%％]""")),
            "标普500" to Pair(Regex("""标普五百|标普500"""), Regex("""标普\s*(?:500|五百)""")),
            "2015/2025" to Pair(Regex("""二零一五|2015"""), Regex("""(?<!\d)2015(?!\d)|二零一五""")),
            "FDA 223" to Pair(Regex("""两百二十三|223"""), Regex("""(?<!\d)223(?!\d)|两百二十三""")),
            "三到八成" to Pair(Regex("""三到八成"""), Regex("""三到八成|30\s*[%％].*80\s*[%％]""")),
            "122天" to Pair(Regex("""一百二十二|122"""), Regex("""(?<!\d)122(?!\d)|一百二十二""")),
        )
        val present = references.filter { (_, regexes) ->
            regexes.first.containsMatchIn(frozen.selectedText)
        }
        val missing = present.filter { (_, regexes) ->
            !regexes.second.containsMatchIn(output)
        }.map { it.first }
        return Audit(
            flagged = prior.knownErrors,
            languageSignals = prior.languageSignals,
            referenceMentions = present.map { it.first },
            missingReferenceMentions = missing,
        )
    }

    fun runtimePass(
        testCase: Case,
        actual: LocalLlmBackend,
        fallback: Boolean,
        idle: Boolean,
        pssClosedKb: Long?,
    ): Boolean = Stage17R17CP2CNumericQa.runtimePass(
        testCase = when (testCase) {
            Case.CPU -> Stage17R17CP2CNumericQa.Case.CPU
            Case.GPU -> Stage17R17CP2CNumericQa.Case.GPU
        },
        actual = actual,
        fallback = fallback,
        idle = idle,
        closedPssKb = pssClosedKb,
    )

    fun report(frozen: FrozenInput, runs: List<Run>, blocked: Boolean): String = buildString {
        appendLine("# WENZHI R17C P2-C2 Medium Transcript CPU/GPU QA")
        appendLine("QA ONLY / LiteRT-LM 0.18.0 / 4K context / 1024 output / no schema")
        appendLine("blocked=" + blocked)
        appendLine("recordingId=" + frozen.source.recordingId)
        appendLine("displayName=" + frozen.source.displayName)
        appendLine("transcriptionId=" + frozen.source.transcriptionId)
        appendLine("revisionId=" + (frozen.source.revisionId ?: "MODEL_ORIGINAL"))
        appendLine("sourceDigest=" + frozen.source.inputDigest)
        appendLine("wholeTranscriptSHA256=" + frozen.source.transcriptSha256)
        appendLine("selectedParagraphs=" + frozen.selectedParagraphs)
        appendLine("selectedChars=" + frozen.selectedText.length)
        appendLine("selectedSHA256=" + frozen.selectedSha256)
        appendLine("qaPromptSHA=" + frozen.promptSha256)
        appendLine("qaPromptEstimate=" + frozen.estimate)
        appendLine("SOURCE BOUNDARY: Only intact contiguous prefix paragraphs, NOT full transcript.")
        appendLine("FACT CHECK: literal QA patterns only; ALL claims require human evidence review.")
        appendLine("===== FROZEN PARTIAL PROMPT =====")
        appendLine(frozen.prompt)
        runs.forEach { run ->
            appendLine()
            appendLine("===== " + run.testCase.label + " =====")
            appendLine("promptSHA=" + run.promptSha256)
            appendLine(run.runtimeDiagnostic)
            appendLine("audit=" + run.audit.concise())
            appendLine("knownCorruptions=" + run.audit.flagged.joinToString(" | "))
            appendLine("languageSignals=" + run.audit.languageSignals.joinToString(" | "))
            appendLine("presentSourceFacts=" + run.audit.referenceMentions.joinToString(" | "))
            appendLine("missingSourceFacts=" + run.audit.missingReferenceMentions.joinToString(" | "))
            appendLine("===== RAW GENERATED TEXT =====")
            appendLine(run.rawOutput)
        }
        appendLine()
        appendLine("===== SELECTED ORIGINAL TRANSCRIPT =====")
        appendLine(frozen.selectedText)
        appendLine("===== SELECTED EVIDENCE (manual review only) =====")
        frozen.evidence.forEach(::appendLine)
        appendLine("END: no automatic quality pass; no production GPU promotion.")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
