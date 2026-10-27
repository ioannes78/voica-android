package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.ai.OfflineSummaryDirectBudgetGate
import io.github.ioannes78.voica.ai.OfflineSummaryPromptRenderer
import io.github.ioannes78.voica.ai.RenderedOfflineSummaryPrompt
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import kotlinx.coroutines.flow.first

/** QA-only Stage 17.2 R5-B foreground isolation matrix. */
internal enum class Stage17R5IsolationCase(
    val code: String,
    val title: String,
    val usesFormalPrompt: Boolean,
    val usesFormalSchema: Boolean,
    val requiresStructuredValidation: Boolean,
) {
    B1_FORMAL_PROMPT_GPU_NO_SCHEMA(
        code = "B1",
        title = "正式 prompt + GPU + 无 schema",
        usesFormalPrompt = true,
        usesFormalSchema = false,
        requiresStructuredValidation = false,
    ),
    B2_SMALL_PROMPT_GPU_FORMAL_SCHEMA(
        code = "B2",
        title = "小 prompt + GPU + 正式 schema",
        usesFormalPrompt = false,
        usesFormalSchema = true,
        requiresStructuredValidation = true,
    ),
    B3_FORMAL_PROMPT_GPU_FORMAL_SCHEMA(
        code = "B3",
        title = "正式 prompt + GPU + 正式 schema",
        usesFormalPrompt = true,
        usesFormalSchema = true,
        requiresStructuredValidation = true,
    ),
}

internal data class Stage17R5IsolationSource(
    val recordingId: String,
    val displayName: String,
    val durationMs: Long?,
    val formalPrompt: RenderedOfflineSummaryPrompt,
)

internal data class Stage17R5IsolationRequest(
    val testCase: Stage17R5IsolationCase,
    val prompt: String,
    val config: LocalLlmRuntimeConfig,
    val allowedEvidenceRefs: Set<String>,
    val estimatedPromptTokens: Int,
)

/**
 * Loads only real completed transcript snapshots that already fit the frozen Stage 17.2 direct
 * summary budget. No transcript content is persisted by this QA helper.
 */
internal suspend fun AppContainer.loadStage17R5IsolationSources(
    limit: Int = 12,
): List<Stage17R5IsolationSource> {
    require(limit > 0)
    val promptRenderer = OfflineSummaryPromptRenderer()
    val budgetGate = OfflineSummaryDirectBudgetGate()
    val recordings =
        recordingLibraryRepository.recordings
            .first()
            .sortedByDescending { it.qaRecencyKey() }

    val sources = mutableListOf<Stage17R5IsolationSource>()
    for (recording in recordings) {
        val source =
            runCatching {
                val effective =
                    stage12CContentRepository.resolveEffectiveTranscription(recording.id)
                        ?: return@runCatching null
                val input =
                    aiSummaryRepository.buildStructuredTranscriptSnapshot(
                        transcriptionId = effective.transcriptionId,
                        revisionId = effective.revisionId,
                    )
                val formalPrompt = promptRenderer.render(input)
                budgetGate.requireFits(formalPrompt)
                Stage17R5IsolationSource(
                    recordingId = recording.id,
                    displayName = recording.displayName,
                    durationMs = recording.mediaDurationMs ?: recording.deviceReportedDurationMs,
                    formalPrompt = formalPrompt,
                )
            }.getOrNull()
        if (source != null) {
            sources += source
            if (sources.size >= limit) break
        }
    }
    return sources
}

internal fun buildStage17R5IsolationRequest(
    testCase: Stage17R5IsolationCase,
    source: Stage17R5IsolationSource,
): Stage17R5IsolationRequest {
    val formalConfig =
        OfflineSummaryLocalLlmPreset
            .configFor(source.formalPrompt.allowedEvidenceRefs)
            .copy(backendPolicy = LocalLlmBackendPolicy.GPU)
    val prompt =
        if (testCase.usesFormalPrompt) {
            source.formalPrompt.text
        } else {
            R5_B2_SMALL_STRUCTURED_PROMPT
        }
    val config =
        if (testCase.usesFormalSchema) {
            formalConfig
        } else {
            formalConfig.copy(responseJsonSchema = null)
        }
    return Stage17R5IsolationRequest(
        testCase = testCase,
        prompt = prompt,
        config = config,
        allowedEvidenceRefs = source.formalPrompt.allowedEvidenceRefs,
        estimatedPromptTokens = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt),
    )
}

private fun RecordingLibraryItem.qaRecencyKey(): Long =
    maxOf(
        updatedAtMs,
        createdAtMs,
        downloadedAtMs ?: 0L,
    )

/**
 * Intentionally content-free and independent from the selected transcript. The formal schema still
 * comes from the selected request so B2 exercises the exact Stage 17.2 constrained-decoding shape
 * and request-specific Evidence enum while keeping prompt workload tiny.
 */
private const val R5_B2_SMALL_STRUCTURED_PROMPT =
    "你正在执行本地结构化输出诊断。不要引用任何转写事实。" +
        "只返回一个最小合法 JSON 对象：contentType 使用 OTHER，classificationConfidence 使用 null，" +
        "title 使用“诊断”，overview 使用“结构化输出诊断”，sections 只保留一个 SUMMARY section，" +
        "section.id 使用 s1，label 使用“摘要”，items 只保留一个 item，item.id 使用 i1，" +
        "text 使用“结构化输出诊断”，evidenceRefs 必须为空数组，epistemicStatus 使用 AI_SYNTHESIS，" +
        "attributes 必须是空对象。不要输出 Markdown、代码围栏或解释。"
