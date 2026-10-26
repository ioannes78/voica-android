package io.github.ioannes78.voica.localllm

enum class ProbeBackend {
    CPU,
    GPU,
}

enum class Stage17ProbePhase {
    ENGINE_CONSTRUCTING,
    ENGINE_INITIALIZING,
    ENGINE_INITIALIZED,
    CONVERSATION_CREATING,
    CONVERSATION_READY,
    GENERATING,
    GENERATED,
    ENGINE_CLOSED,
}

data class Stage17ProbeRequest(
    val modelPath: String,
    val backend: ProbeBackend,
    val maxNumTokens: Int = Stage17LocalLlmProbe.PROBE_MAX_CONTEXT_TOKENS,
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(maxNumTokens > 0) { "maxNumTokens must be positive" }
    }
}

data class Stage17ProbeResult(
    val runtimeVersion: String,
    val backend: ProbeBackend,
    val maxNumTokens: Int,
    val initializeMs: Long,
    val timeToFirstEmissionMs: Long?,
    val totalGenerationMs: Long,
    val pssBeforeInitializeKb: Long,
    val pssAfterInitializeKb: Long,
    val pssAfterGenerationKb: Long,
    val pssAfterCloseKb: Long,
    val peakPssKb: Long,
    val responsePreview: String,
)

/**
 * Stage 17 / P0 regression wrapper.
 *
 * P1 moves the Engine/Conversation implementation into [LiteRtLocalLlmRuntime]. This wrapper keeps
 * the already validated P0 prompt, artifact metadata, explicit CPU/GPU controls and diagnostics
 * surface intact so R7 evidence remains directly regression-testable without making P0 probe code
 * the production runtime architecture.
 */
class Stage17LocalLlmProbe(
    private val runtime: LiteRtLocalLlmRuntime = LiteRtLocalLlmRuntime(),
) {
    suspend fun run(
        request: Stage17ProbeRequest,
        onPhase: (Stage17ProbePhase) -> Unit = {},
    ): Stage17ProbeResult {
        val backendPolicy =
            when (request.backend) {
                ProbeBackend.CPU -> LocalLlmBackendPolicy.CPU
                ProbeBackend.GPU -> LocalLlmBackendPolicy.GPU
            }
        val resolvedBackend =
            when (request.backend) {
                ProbeBackend.CPU -> LocalLlmBackend.CPU
                ProbeBackend.GPU -> LocalLlmBackend.GPU
            }
        val config =
            LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = backendPolicy,
                maxNumTokens = request.maxNumTokens,
            )
        val generationRequest =
            LocalLlmGenerationRequest(
                prompt = PROBE_PROMPT,
                modelIdentity = Stage17P0Candidate.IDENTITY,
                config = config,
            )
        val backendResolution =
            LocalLlmBackendResolution(
                requestedPolicy = backendPolicy,
                resolvedBackend = resolvedBackend,
                reason = "P0_EXPLICIT_${request.backend.name}",
            )
        val result =
            runtime.generate(
                modelPath = request.modelPath,
                request = generationRequest,
                backendResolution = backendResolution,
                onPhase = { phase -> onPhase(phase.toProbePhase()) },
            )
        val diagnostics = result.diagnostics
        return Stage17ProbeResult(
            runtimeVersion = diagnostics.runtimeVersion,
            backend = request.backend,
            maxNumTokens = request.maxNumTokens,
            initializeMs = checkNotNull(diagnostics.initializeMs),
            timeToFirstEmissionMs = diagnostics.timeToFirstEmissionMs,
            totalGenerationMs = checkNotNull(diagnostics.generationMs),
            pssBeforeInitializeKb = checkNotNull(diagnostics.pssBeforeKb),
            pssAfterInitializeKb = checkNotNull(diagnostics.pssAfterInitializeKb),
            pssAfterGenerationKb = checkNotNull(diagnostics.pssAfterGenerationKb),
            pssAfterCloseKb = checkNotNull(diagnostics.pssAfterCloseKb),
            peakPssKb = checkNotNull(diagnostics.peakPssKb),
            responsePreview = result.text.take(MAX_RESPONSE_PREVIEW_CHARS),
        )
    }

    companion object {
        const val RUNTIME_VERSION = "0.18.0"
        const val PROBE_PROMPT = "请用一句简短中文回答：你现在运行在本地设备上。"
        const val PROBE_MAX_CONTEXT_TOKENS = 4_000
        const val PROBE_TOP_K = 64
        const val PROBE_TOP_P = 0.95
        const val PROBE_TEMPERATURE = 1.0
        const val MAX_RESPONSE_PREVIEW_CHARS = 512
        const val PSS_SAMPLE_INTERVAL_MS = 250L
    }
}

private fun LocalLlmRuntimePhase.toProbePhase(): Stage17ProbePhase =
    when (this) {
        LocalLlmRuntimePhase.ENGINE_CONSTRUCTING -> Stage17ProbePhase.ENGINE_CONSTRUCTING
        LocalLlmRuntimePhase.ENGINE_INITIALIZING -> Stage17ProbePhase.ENGINE_INITIALIZING
        LocalLlmRuntimePhase.ENGINE_INITIALIZED -> Stage17ProbePhase.ENGINE_INITIALIZED
        LocalLlmRuntimePhase.CONVERSATION_CREATING -> Stage17ProbePhase.CONVERSATION_CREATING
        LocalLlmRuntimePhase.CONVERSATION_READY -> Stage17ProbePhase.CONVERSATION_READY
        LocalLlmRuntimePhase.GENERATING,
        LocalLlmRuntimePhase.GENERATION_PROGRESS,
        -> Stage17ProbePhase.GENERATING
        LocalLlmRuntimePhase.GENERATED -> Stage17ProbePhase.GENERATED
        LocalLlmRuntimePhase.ENGINE_CLOSED -> Stage17ProbePhase.ENGINE_CLOSED
    }

/**
 * Externally verified P0 candidate metadata. These values remain feasibility evidence, not a
 * production model-channel promotion. P1 continues to use this exact pre-MTP artifact until a
 * separate model-channel decision changes that contract.
 */
object Stage17P0Candidate {
    const val MODEL_ID = "litert-community/gemma-4-E2B-it-litert-lm"
    const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
    const val GALLERY_BASE_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    const val GALLERY_NEWER_MTP_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
    const val GALLERY_BASE_SIZE_BYTES = 2_583_085_056L
    const val GALLERY_BASE_SHA256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
    const val MIN_DEVICE_MEMORY_GB = 8
    const val MAX_CONTEXT_TOKENS = 32_000

    val IDENTITY =
        LocalLlmModelIdentity(
            modelId = "gemma-4-e2b-it-litertlm",
            modelFamily = "Gemma 4 E2B",
            artifact = MODEL_FILE,
            revision = GALLERY_BASE_REVISION,
            sha256 = GALLERY_BASE_SHA256,
            runtimeId = "litert-lm",
            runtimeVersion = Stage17LocalLlmProbe.RUNTIME_VERSION,
        )
}
