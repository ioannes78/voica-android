package io.github.ioannes78.voica.localllm

/** Product-facing backend policy. AUTO is the default and is resolved at runtime. */
enum class LocalLlmBackendPolicy {
    AUTO,
    GPU,
    CPU,
}

/** Concrete LiteRT-LM backend selected for one inference attempt. */
enum class LocalLlmBackend {
    GPU,
    CPU,
}

enum class LocalLlmRuntimeState {
    IDLE,
    ACQUIRING,
    PREFLIGHT,
    INITIALIZING,
    READY,
    GENERATING,
    CANCELLING,
    RELEASING,
    FAILED,
    POISONED,
}

enum class LocalLlmFailureCode {
    MODEL_NOT_INSTALLED,
    MODEL_CORRUPT,
    MODEL_INCOMPATIBLE,
    BUSY,
    INSUFFICIENT_MEMORY,
    THERMAL_BLOCKED,
    BACKEND_UNSUPPORTED,
    ENGINE_INIT_FAILED,
    CONVERSATION_CREATE_FAILED,
    GENERATION_FAILED,
    CANCELLED,
    NATIVE_PROCESS_FAILURE_EVIDENCE,
    UNKNOWN,
    ;

    companion object {
        /**
         * R4-A compatibility alias. POISONED is a distinct runtime state, while product/persistence
         * failure contracts continue to classify the condition under the existing native-process
         * failure bucket instead of widening Stage 17.2's stored failure-code vocabulary.
         */
        val RUNTIME_POISONED: LocalLlmFailureCode = NATIVE_PROCESS_FAILURE_EVIDENCE
    }
}

enum class LocalLlmFailurePhase {
    MODEL_RESOLUTION,
    ACQUIRING,
    PREFLIGHT,
    ENGINE_CONSTRUCTING,
    ENGINE_INITIALIZING,
    CONVERSATION_CREATING,
    GENERATING,
    RELEASING,
    UNKNOWN,
}

data class LocalLlmModelIdentity(
    val modelId: String,
    val modelFamily: String,
    val artifact: String,
    val revision: String,
    val sha256: String,
    val runtimeId: String,
    val runtimeVersion: String,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(modelFamily.isNotBlank()) { "modelFamily must not be blank" }
        require(artifact.isNotBlank()) { "artifact must not be blank" }
        require(revision.isNotBlank()) { "revision must not be blank" }
        require(SHA256_REGEX.matches(sha256)) { "sha256 must be 64 hexadecimal characters" }
        require(runtimeId.isNotBlank()) { "runtimeId must not be blank" }
        require(runtimeVersion.isNotBlank()) { "runtimeVersion must not be blank" }
    }

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class LocalLlmRuntimeConfig(
    val backendPolicy: LocalLlmBackendPolicy = LocalLlmBackendPolicy.AUTO,
    val maxNumTokens: Int = 4_000,
    val maxOutputTokens: Int? = 1_024,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val speculativeDecoding: Boolean = false,
    /** Optional LiteRT-LM JSON Schema response format. Null preserves unconstrained generation. */
    val responseJsonSchema: String? = null,
) {
    init {
        require(maxNumTokens > 0) { "maxNumTokens must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be positive or null"
        }
        require(maxOutputTokens == null || maxOutputTokens <= maxNumTokens) {
            "maxOutputTokens must not exceed maxNumTokens"
        }
        require(topK > 0) { "topK must be positive" }
        require(topP.isFinite() && topP > 0.0 && topP <= 1.0) {
            "topP must be finite and in (0, 1]"
        }
        require(temperature.isFinite() && temperature >= 0.0) {
            "temperature must be finite and non-negative"
        }
        require(responseJsonSchema == null || responseJsonSchema.isNotBlank()) {
            "responseJsonSchema must be non-blank or null"
        }
    }
}

data class LocalLlmBackendResolution(
    val requestedPolicy: LocalLlmBackendPolicy,
    val resolvedBackend: LocalLlmBackend,
    val reason: String,
    val fallbackAttempted: Boolean = false,
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        if (requestedPolicy == LocalLlmBackendPolicy.CPU) {
            require(resolvedBackend == LocalLlmBackend.CPU) {
                "explicit CPU policy must resolve to CPU"
            }
        }
        if (requestedPolicy == LocalLlmBackendPolicy.GPU) {
            require(resolvedBackend == LocalLlmBackend.GPU) {
                "explicit GPU policy must resolve to GPU"
            }
        }
    }
}

data class LocalLlmGenerationRequest(
    val prompt: String,
    val modelIdentity: LocalLlmModelIdentity,
    val config: LocalLlmRuntimeConfig = LocalLlmRuntimeConfig(),
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
    }
}

data class LocalLlmDiagnostics(
    val runtimeVersion: String,
    val modelIdentity: LocalLlmModelIdentity,
    val backendResolution: LocalLlmBackendResolution,
    val initializeMs: Long? = null,
    val conversationCreateMs: Long? = null,
    val timeToFirstEmissionMs: Long? = null,
    val generationMs: Long? = null,
    val pssBeforeKb: Long? = null,
    val pssAfterInitializeKb: Long? = null,
    val pssAfterGenerationKb: Long? = null,
    val pssAfterCloseKb: Long? = null,
    val peakPssKb: Long? = null,
    val thermalBefore: Int? = null,
    val thermalAfter: Int? = null,
    val failurePhase: LocalLlmFailurePhase? = null,
    val failureCode: LocalLlmFailureCode? = null,
    val cancelled: Boolean = false,
) {
    init {
        require(runtimeVersion.isNotBlank()) { "runtimeVersion must not be blank" }
        listOf(
            initializeMs,
            conversationCreateMs,
            timeToFirstEmissionMs,
            generationMs,
            pssBeforeKb,
            pssAfterInitializeKb,
            pssAfterGenerationKb,
            pssAfterCloseKb,
            peakPssKb,
        ).filterNotNull().forEach { value ->
            require(value >= 0L) { "diagnostic durations and memory values must be non-negative" }
        }
    }
}

data class LocalLlmGenerationResult(
    val text: String,
    val modelIdentity: LocalLlmModelIdentity,
    val backendResolution: LocalLlmBackendResolution,
    val diagnostics: LocalLlmDiagnostics,
)

data class LocalLlmFailure(
    val code: LocalLlmFailureCode,
    val phase: LocalLlmFailurePhase,
    val message: String,
    val requestedPolicy: LocalLlmBackendPolicy,
    val resolvedBackend: LocalLlmBackend? = null,
    val fallbackAttempted: Boolean = false,
    val causeClassName: String? = null,
) {
    init {
        require(message.isNotBlank()) { "failure message must not be blank" }
    }
}

/** P0's proven sampler/context values retained as a named regression preset, not a product lock. */
object LocalLlmValidatedPresets {
    val P0_BASELINE = LocalLlmRuntimeConfig(
        backendPolicy = LocalLlmBackendPolicy.AUTO,
        maxNumTokens = 4_000,
        maxOutputTokens = null,
        topK = 64,
        topP = 0.95,
        temperature = 1.0,
        speculativeDecoding = false,
        responseJsonSchema = null,
    )
}
