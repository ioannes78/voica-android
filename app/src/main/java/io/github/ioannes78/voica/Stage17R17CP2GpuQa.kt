package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import java.security.MessageDigest

/**
 * R17C-P2 GPU compatibility gate: QA ONLY, fixed LiteRT-LM 0.18.0 and model revision.
 * Explicit GPU + no native response JSON schema; never changes production AUTO containment.
 */
internal object Stage17R17CP2GpuQa {
    private const val SAFETY_RESERVE = 256
    private const val TEMPLATE_RESERVE = 192
    private const val SHORT_OUTPUT_TOKENS = 256
    const val SHORT_MARKER = "R17C_GPU_OK"
    // QA-only conservative headroom floor; not a product-device requirement.
    const val LONG_MIN_FREE_BYTES: Long = 3L * 1024L * 1024L * 1024L

    fun longMemoryReady(availableBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && availableBytes >= LONG_MIN_FREE_BYTES


    enum class Case(val label: String) {
        SHORT_4K("P2-A · GPU 4K 短探针"),
        REAL_8K_2048("P2-B · GPU 8K/2048 完整转写"),
    }

    data class Request(
        val testCase: Case,
        val prompt: String,
        val config: LocalLlmRuntimeConfig,
        val promptSha256: String,
        val estimatedPromptTokens: Int,
        val sourceDigest: String? = null,
        val transcriptSha256: String? = null,
    )

    data class Run(
        val testCase: Case,
        val rawOutput: String,
        val runtimeDiagnostic: String,
        val promptSha256: String,
    )

    fun shortProbe(): Request {
        // Deliberately synthetic, tiny, and content-free. No user transcript enters this request.
        val prompt = "闻知 WENZHI 本地 GPU 推理短探针。请仅输出 R17C_GPU_OK，" +
            "不要解释、不要 JSON、不要其他文字。"
        val estimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt)
        require(estimate + SHORT_OUTPUT_TOKENS + TEMPLATE_RESERVE + SAFETY_RESERVE <= 4_000)
        return Request(
            testCase = Case.SHORT_4K,
            prompt = prompt,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = LocalLlmBackendPolicy.GPU,
                maxNumTokens = 4_000,
                maxOutputTokens = SHORT_OUTPUT_TOKENS,
                speculativeDecoding = false,
                responseJsonSchema = null,
            ),
            promptSha256 = sha256(prompt),
            estimatedPromptTokens = estimate,
        )
    }

    fun fullTranscriptProbe(source: Stage17R17CDirectQa.Source): Request {
        // Exact 8K/2048 CPU prompt and sampling; ONLY explicit backend changes to GPU.
        val cpu = Stage17R17CDirectQa.request(
            source = source,
            tier = Stage17R17CDirectQa.Tier.C8K_2048,
        )
        return Request(
            testCase = Case.REAL_8K_2048,
            prompt = cpu.prompt,
            config = cpu.config.copy(backendPolicy = LocalLlmBackendPolicy.GPU),
            promptSha256 = cpu.promptSha256,
            estimatedPromptTokens = cpu.estimatedPromptTokens,
            sourceDigest = source.inputDigest,
            transcriptSha256 = source.transcriptSha256,
        )
    }

    /** Pure preflight; used before every manual native GPU attempt. */
    fun preflightReason(
        testCase: Case,
        acknowledgedRisk: Boolean,
        shortProbePassed: Boolean,
        previousFailureInSession: Boolean,
        foreground: Boolean,
        activityResumed: Boolean,
        interactive: Boolean,
        keyguardShowing: Boolean,
        deviceLocked: Boolean,
        runtimeIdle: Boolean,
    ): String? = when {
        previousFailureInSession -> "本次 QA 已出现失败或取消；禁止在同一页面继续 GPU 尝试"
        !acknowledgedRisk -> "请先勾选已知 GPU/native 风险确认"
        !foreground || !activityResumed || !interactive || keyguardShowing || deviceLocked ->
            "GPU QA 仅限前台、亮屏、未锁定状态"
        !runtimeIdle -> "原生推理资源未处于 IDLE；禁止再次获取 GPU"
        testCase == Case.REAL_8K_2048 && !shortProbePassed ->
            "请先完成并通过一次 GPU 4K 短探针"
        else -> null
    }

    /** Successful emission is insufficient without confirmed post-close memory observation. */
    fun shortProbePass(
        output: String,
        resolvedBackend: LocalLlmBackend,
        fallbackAttempted: Boolean,
        afterClosePssKb: Long?,
        nativeRuntimeIdle: Boolean,
    ): Boolean =
        output.contains(SHORT_MARKER) &&
            resolvedBackend == LocalLlmBackend.GPU &&
            !fallbackAttempted &&
            afterClosePssKb != null &&
            nativeRuntimeIdle

    fun report(
        source: Stage17R17CDirectQa.Source?,
        runs: List<Run>,
        shortProbePassed: Boolean,
        sessionBlocked: Boolean,
    ): String = buildString {
        appendLine("# WENZHI Stage 17.3 R17C-P2 GPU Free-Text Compatibility QA")
        appendLine("QA ONLY; Runtime=0.18.0; explicit GPU; responseJsonSchema=null")
        appendLine("No automatic fallback, no production backend change, no network upload")
        appendLine("shortProbePassed=" + shortProbePassed)
        appendLine("sessionBlocked=" + sessionBlocked)
        appendLine("SAFETY: prior structured-GPU ANR/device reboot; never lock screen or retry after native failure")
        if (source != null) {
            appendLine("recordingId=" + source.recordingId)
            appendLine("displayName=" + source.displayName)
            appendLine("transcriptionId=" + source.transcriptionId)
            appendLine("revisionId=" + (source.revisionId ?: "MODEL_ORIGINAL"))
            appendLine("inputDigest=" + source.inputDigest)
            appendLine("transcriptSHA256=" + source.transcriptSha256)
            appendLine("transcriptChars=" + source.transcriptText.length)
            appendLine("units=" + source.unitCount)
        }
        runs.forEach { run ->
            appendLine()
            appendLine("===== " + run.testCase.label + " =====")
            appendLine("promptSHA=" + run.promptSha256)
            appendLine(run.runtimeDiagnostic)
            if (run.testCase == Case.REAL_8K_2048 && source != null) {
                appendLine(
                    "knownNumericRegression=" +
                        Stage17R17CDirectQa.knownNumericRegression(
                            source.transcriptText, run.rawOutput,
                        ),
                )
            }
            appendLine("===== RAW GPU OUTPUT =====")
            appendLine(run.rawOutput)
        }
        if (source != null) {
            appendLine()
            appendLine("===== COMPLETE EFFECTIVE TRANSCRIPT =====")
            appendLine(source.transcriptText)
            appendLine()
            appendLine("===== EVIDENCE SIDECAR (manual semantic review only) =====")
            source.evidenceRows.forEach(::appendLine)
        }
        appendLine("Interpretation: GPU completion != long-term driver stability; no factual PASS implied.")
        appendLine("Compare only matching model revision, promptSHA, transcriptSHA and output cap.")
        appendLine("Manual review: numbers/units, risk, summaries, PSS, release, crash breadcrumbs.")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
