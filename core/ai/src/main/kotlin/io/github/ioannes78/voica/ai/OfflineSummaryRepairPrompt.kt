package io.github.ioannes78.voica.ai

/**
 * Deterministic one-shot repair prompt for Stage 17.2.
 *
 * This is intentionally not a second summarization prompt. The invalid model output is preserved in
 * full. Evidence catalogue context is selected deterministically inside the same direct-summary
 * prompt budget so repair never invents coordinates merely to make the request fit.
 *
 * A truncated JSON object is not repairable here: it means generation exhausted its output budget.
 * Re-running the same local LLM would only repeat a long decode and can double user-visible latency.
 */
class OfflineSummaryRepairPromptRenderer(
    private val estimator: OfflineSummaryTokenEstimator = ConservativeOfflineSummaryTokenEstimator,
    private val budgetSpec: OfflineSummaryDirectBudgetSpec = OfflineSummaryDirectBudgetSpec(),
) {
    fun render(
        input: StructuredTranscriptInput,
        invalidOutput: String,
        validationError: SummaryStructuredOutputException,
    ): RenderedOfflineSummaryPrompt {
        require(invalidOutput.isNotBlank()) { "invalid output must not be blank for repair" }
        if (validationError.code == SummaryStructuredOutputErrorCode.TRUNCATED_JSON) {
            throw validationError
        }

        val allowedEvidenceRefs =
            input.units
                .asSequence()
                .mapNotNull { it.evidence?.ref }
                .toCollection(LinkedHashSet())
        val candidates =
            input.units.mapIndexedNotNull { index, unit ->
                val evidence = unit.evidence ?: return@mapIndexedNotNull null
                val speaker = unit.effectiveSpeakerDisplayName ?: unit.effectiveSpeakerId
                EvidenceCatalogueEntry(
                    sourceIndex = index,
                    ref = evidence.ref,
                    rendered =
                        buildString {
                            append(evidence.ref)
                            if (!speaker.isNullOrBlank()) {
                                append('|')
                                append(compact(speaker))
                            }
                            append('|')
                            append(compact(unit.text))
                        },
                    relevance =
                        relevanceScore(unit.text, invalidOutput) +
                            if (invalidOutput.contains(evidence.ref)) EXACT_REF_BONUS else 0,
                )
            }.sortedWith(
                compareByDescending<EvidenceCatalogueEntry> { it.relevance }
                    .thenBy { it.sourceIndex },
            )

        val selectedCatalogue = mutableListOf<String>()
        for (candidate in candidates) {
            val trial = selectedCatalogue + candidate.rendered
            val trialPrompt =
                buildPrompt(
                    validationError = validationError,
                    catalogue = trial,
                    hasEvidence = candidates.isNotEmpty(),
                    invalidOutput = invalidOutput,
                )
            if (estimator.estimateTokens(trialPrompt) <= budgetSpec.maxEstimatedPromptTokens) {
                selectedCatalogue += candidate.rendered
            }
        }

        val prompt =
            buildPrompt(
                validationError = validationError,
                catalogue = selectedCatalogue,
                hasEvidence = candidates.isNotEmpty(),
                invalidOutput = invalidOutput,
            )

        return RenderedOfflineSummaryPrompt(
            promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
            resultSchemaVersion = OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION,
            text = prompt,
            allowedEvidenceRefs = allowedEvidenceRefs,
            sourceUnitCount = input.units.size,
        )
    }

    private fun buildPrompt(
        validationError: SummaryStructuredOutputException,
        catalogue: List<String>,
        hasEvidence: Boolean,
        invalidOutput: String,
    ): String =
        buildString {
            appendLine("你是闻知 WENZHI 的一次性本地 JSON 修复器。不是重新总结：不得新增事实，不得扩写或改写原总结语义。")
            appendLine("只返回一个完整 JSON 对象，不要 Markdown、解释或额外文本。")
            appendLine("error=${validationError.code.name}:${compact(validationError.message.orEmpty())}")
            appendLine("保持原有 title、overview、section/item 的事实与文字；仅修复结构、枚举、Evidence 引用或 epistemicStatus 中导致校验失败的部分。")
            appendLine("Evidence 规则：新绑定的 ref 只能逐字复制下方 Evidence Catalogue 中的 Sxxxxx，禁止推算、递增、补全或创造编号。")
            appendLine("原 JSON 中已经合法且无需修正的 ref 可以原样保留；若没有可靠匹配，设 evidenceRefs=[]，并改为 AI_SYNTHESIS 或 UNCONFIRMED。")
            appendLine("TRANSCRIPT_STATED 必须至少有一个与 item.text 含义真实对应的合法 ref；不得生成 timestamp、毫秒、sample index 或 seek position。")
            appendLine("Evidence Catalogue（冻结转写的预算内真实候选；未列出的 ref 不得作为新的替换值）：")
            when {
                catalogue.isNotEmpty() -> catalogue.forEach(::appendLine)
                hasEvidence -> appendLine("（本次安全预算内无可加入的 Evidence 候选；不要猜 ref）")
                else -> appendLine("（无可用音频 Evidence；所有新 evidenceRefs 必须为 []）")
            }
            appendLine("Schema keys: root{schemaVersion,contentType,classificationConfidence,title,overview,sections}; section{id,type,label,items}; item{id,text,evidenceRefs,epistemicStatus,attributes}.")
            appendLine("contentType=${enumValues<AiContentType>().joinToString(",") { it.name }}")
            appendLine("sectionType=${enumValues<AiSummarySectionType>().joinToString(",") { it.name }}")
            appendLine("epistemicStatus=${enumValues<AiEpistemicStatus>().joinToString(",") { it.name }}")
            appendLine("BEGIN_INVALID_JSON_DATA")
            appendLine(invalidOutput)
            appendLine("END_INVALID_JSON_DATA")
            append("现在只返回修复后的唯一 JSON 对象。")
        }

    private fun relevanceScore(
        evidenceText: String,
        invalidOutput: String,
    ): Int {
        val evidenceGrams = bigrams(evidenceText)
        if (evidenceGrams.isEmpty()) return 0
        val outputGrams = bigrams(invalidOutput)
        return evidenceGrams.count(outputGrams::contains)
    }

    private fun bigrams(value: String): Set<String> {
        val normalized =
            value.lowercase()
                .filter { it.isLetterOrDigit() }
        if (normalized.isEmpty()) return emptySet()
        if (normalized.length == 1) return setOf(normalized)
        return normalized.windowed(size = 2, step = 1).toSet()
    }

    private fun compact(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class EvidenceCatalogueEntry(
        val sourceIndex: Int,
        val ref: String,
        val rendered: String,
        val relevance: Int,
    )

    private companion object {
        const val EXACT_REF_BONUS = 1_000_000
    }
}
