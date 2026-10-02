package io.github.ioannes78.voica.ai

enum class ProviderAdapterKind {
    OPENAI_COMPATIBLE,
    GOOGLE_GEMINI,
    GOOGLE_VERTEX,
    XAI_GROK,
}

object ProviderPresetIds {
    const val OPENAI = "openai"
    const val GOOGLE_GEMINI = "google-gemini"
    const val GOOGLE_VERTEX = "google-vertex"
    const val XAI_GROK = "xai-grok"
    const val DEEPSEEK = "deepseek"
    const val ALIBABA_BAILIAN = "alibaba-bailian"
    const val VOLCENGINE_DOUBAO = "volcengine-doubao"
    const val SILICONFLOW = "siliconflow"
    const val ZHIPU_GLM = "zhipu-glm"
    const val MOONSHOT_KIMI = "moonshot-kimi"
    const val OPENROUTER = "openrouter"
    const val CUSTOM_OPENAI_COMPATIBLE = "custom-openai-compatible"
}

data class ProviderCapabilities(
    val supportsModelDiscovery: Boolean = false,
    val supportsStreaming: Boolean = false,
    val supportsNonStreaming: Boolean = true,
    val supportsJsonObject: Boolean = false,
    val supportsJsonSchema: Boolean = false,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val reportsUsage: Boolean = false,
    val supportsCancellation: Boolean = true,
)

data class ProviderPresetDescriptor(
    val presetId: String,
    val displayName: String,
    val adapterKind: ProviderAdapterKind,
    val defaultBaseUrl: String?,
    val defaultCapabilities: ProviderCapabilities,
)

object ProviderPresetCatalog {
    val builtIn: List<ProviderPresetDescriptor> =
        listOf(
            ProviderPresetDescriptor(ProviderPresetIds.OPENAI, "OpenAI", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, supportsJsonSchema = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.GOOGLE_GEMINI, "Google Gemini API / AI Studio", ProviderAdapterKind.GOOGLE_GEMINI, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.GOOGLE_VERTEX, "Google Vertex AI / Gemini", ProviderAdapterKind.GOOGLE_VERTEX, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.XAI_GROK, "xAI / Grok", ProviderAdapterKind.XAI_GROK, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.DEEPSEEK, "DeepSeek", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.ALIBABA_BAILIAN, "阿里云百炼", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsStreaming = true, supportsJsonObject = true)),
            ProviderPresetDescriptor(ProviderPresetIds.VOLCENGINE_DOUBAO, "火山引擎 / 豆包", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsStreaming = true, supportsJsonObject = true)),
            ProviderPresetDescriptor(ProviderPresetIds.SILICONFLOW, "硅基流动", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true)),
            ProviderPresetDescriptor(ProviderPresetIds.ZHIPU_GLM, "智谱 GLM", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsStreaming = true, supportsJsonObject = true)),
            ProviderPresetDescriptor(ProviderPresetIds.MOONSHOT_KIMI, "月之暗面 Kimi", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsStreaming = true, supportsJsonObject = true)),
            ProviderPresetDescriptor(ProviderPresetIds.OPENROUTER, "OpenRouter", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities(supportsModelDiscovery = true, supportsStreaming = true, supportsJsonObject = true, reportsUsage = true)),
            ProviderPresetDescriptor(ProviderPresetIds.CUSTOM_OPENAI_COMPATIBLE, "Custom OpenAI-compatible", ProviderAdapterKind.OPENAI_COMPATIBLE, null, ProviderCapabilities()),
        )

    fun find(presetId: String): ProviderPresetDescriptor? =
        builtIn.firstOrNull { it.presetId == presetId }
}

data class ProviderProfile(
    val providerProfileId: String,
    val presetId: String,
    val displayName: String,
    val baseUrl: String,
    val credentialRef: String,
    val defaultModel: String,
    val timeoutMs: Long,
    val manualContextWindowTokens: Int?,
    val capabilityOverrides: ProviderCapabilities?,
    val enabled: Boolean,
)

data class ProviderModel(
    val id: String,
    val displayName: String = id,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
)

enum class ProviderErrorCode {
    INVALID_CONFIGURATION,
    AUTHENTICATION_FAILED,
    PERMISSION_DENIED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    PAYLOAD_TOO_LARGE,
    CONTEXT_LIMIT_EXCEEDED,
    NETWORK_UNAVAILABLE,
    DNS_FAILED,
    TLS_FAILED,
    TIMEOUT,
    PROVIDER_5XX,
    MALFORMED_RESPONSE,
    STRUCTURED_OUTPUT_INVALID,
    CANCELLED,
}

data class ProviderFailure(
    val code: ProviderErrorCode,
    val sanitizedMessage: String?,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = false,
)

data class ConnectionTestResult(
    val success: Boolean,
    val capabilities: ProviderCapabilities?,
    val models: List<ProviderModel> = emptyList(),
    val failure: ProviderFailure? = null,
)

data class LlmGenerationRequest(
    val requestId: String,
    val model: String,
    val systemInstruction: String,
    val taskInstruction: String,
    val transcriptPayload: String,
    val structuredOutputSchema: String?,
    val maxOutputTokens: Int?,
)

data class LlmUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

data class LlmGenerationResponse(
    val requestId: String,
    val content: String,
    val usage: LlmUsage?,
)

interface TextLlmProvider {
    suspend fun capabilities(profile: ProviderProfile): ProviderCapabilities

    suspend fun discoverModels(profile: ProviderProfile): Result<List<ProviderModel>>

    suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult

    suspend fun generate(
        profile: ProviderProfile,
        request: LlmGenerationRequest,
    ): Result<LlmGenerationResponse>

    fun cancel(requestId: String)
}

data class AudioUnderstandingCapabilities(
    val supportedFormats: Set<String>,
    val maxDurationMs: Long?,
    val maxBytes: Long?,
    val streamingUpload: Boolean,
    val timestampEvidence: Boolean,
    val speakerUnderstanding: Boolean,
    val structuredOutput: Boolean,
    val modelDiscovery: Boolean,
    val connectionTest: Boolean,
)

interface AudioUnderstandingProvider {
    suspend fun capabilities(profile: ProviderProfile): AudioUnderstandingCapabilities
}
