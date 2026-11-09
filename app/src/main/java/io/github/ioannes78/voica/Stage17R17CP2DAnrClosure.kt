package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy

/**
 * Stage 17.3 R17C P2-D QA-only fail-closed containment.
 *
 * Real-device R17C P2-C2-B: explicit GPU / no native schema, 1988 estimated input
 * tokens, last phase GENERATING with 0 emissions; Android exited with REASON_ANR.
 * Suspending only C2-B would leave several legacy GPU/AUTO QA entrances reachable.
 *
 * Do not infer this fixes the LiteRT-LM / driver native ANR. Requalification
 * requires an explicit future code review and device stability gate.
 */
internal object Stage17R17CP2DAnrClosure {
    const val GPU_QA_SUSPENDED: Boolean = true

    const val NOTICE: String =
        "R17C-P2-D 安全隔离：C2-B GPU 无 Schema 生成发生 Android ANR。" +
            "本 QA 版本禁止 GPU/AUTO 原生推理，保留 CPU 测试及 R5 证据。" +
            "严禁在当前设备重试中长文本 GPU；未变更正式总结后端。"

    /**
     * Fail closed: AUTO can resolve to GPU for a no-schema request. Do not rely
     * on individual disabled buttons, UI state, or remembered previous-process state.
     */
    fun allowQaInference(policy: LocalLlmBackendPolicy): Boolean =
        !GPU_QA_SUSPENDED || policy == LocalLlmBackendPolicy.CPU

    fun blockedReason(policy: LocalLlmBackendPolicy): String? =
        if (allowQaInference(policy)) null
        else "P2-D 已拒绝 " + policy.name + "：已确认 GPU GENERATING/0-emission ANR；" +
            "此 QA 版本仅允许显式 CPU。不要继续 GPU 真机压力测试。"

    /** Classify a persisted breadcrumb without inferring causal root cause. */
    fun knownExitEvidence(
        previousProcess: Boolean,
        requestedBackend: String,
        resolvedBackend: String,
        runtimePhase: String,
        exitReasonLabel: String?,
        emissionCount: Long,
    ): String = when {
        !previousProcess -> "CURRENT_PROCESS_NOT_EXIT_EVIDENCE"
        exitReasonLabel == "ANR" && resolvedBackend == "GPU" && runtimePhase == "GENERATING" ->
            "CONFIRMED_ANDROID_ANR_DURING_GPU_GENERATING; emissions=" + emissionCount +
                "; causal_driver_root_unknown"
        exitReasonLabel == "ANR" ->
            "ANDROID_ANR_OTHER_PHASE_OR_BACKEND; requested=" + requestedBackend +
                "; causal_driver_root_unknown"
        exitReasonLabel.isNullOrBlank() -> "HISTORICAL_EXIT_REASON_NOT_AVAILABLE"
        else -> "HISTORICAL_EXIT=" + exitReasonLabel + "; no GPU root cause inferred"
    }
}
