package io.github.ioannes78.voica.ai

enum class ProviderAdapterKind {
    OPENAI_COMPATIBLE,
    GOOGLE_GEMINI,
    GOOGLE_VERTEX,
    XAI_GROK,
}

enum class ProviderAuthKind {
    BEARER_API_KEY,
    GOOGLE_API_KEY_HEADER,
    GOOGLE_VERTEX_OAUTH,
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
    val authKind: ProviderAuthKind,
    val defaultBaseUrl: String?,
    val defaultCapabilities: ProviderCapabilities,
)

object ProviderPresetCatalog {
    val builtIn: List<ProviderPresetDescriptor> =
        listOf(
            preset(
                ProviderPresetIds.OPENAI,
                "OpenAI",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://api.openai.com/v1",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = true,
                usage = true,
            ),
            preset(
                ProviderPresetIds.GOOGLE_GEMINI,
                "Google Gemini API / AI Studio",
                ProviderAdapterKind.GOOGLE_GEMINI,
                ProviderAuthKind.GOOGLE_API_KEY_HEADER,
                "https://generativelanguage.googleapis.com/v1beta",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = true,
                usage = true,
            ),
            preset(
                ProviderPresetIds.GOOGLE_VERTEX,
                "Google Vertex AI / Gemini",
                ProviderAdapterKind.GOOGLE_VERTEX,
                ProviderAuthKind.GOOGLE_VERTEX_OAUTH,
                null,
                discovery = false,
                streaming = true,
                jsonObject = true,
                jsonSchema = true,
                usage = true,
            ),
            preset(
                ProviderPresetIds.XAI_GROK,
                "xAI / Grok",
                ProviderAdapterKind.XAI_GROK,
                ProviderAuthKind.BEARER_API_KEY,
                "https://api.x.ai/v1",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = true,
                usage = true,
            ),
            preset(
                ProviderPresetIds.DEEPSEEK,
                "DeepSeek",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://api.deepseek.com",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.ALIBABA_BAILIAN,
                "阿里云百炼",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                null,
                discovery = false,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.VOLCENGINE_DOUBAO,
                "火山引擎 / 豆包",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://ark.cn-beijing.volces.com/api/v3",
                discovery = false,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.SILICONFLOW,
                "硅基流动",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://api.siliconflow.cn/v1",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.ZHIPU_GLM,
                "智谱 GLM",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://open.bigmodel.cn/api/paas/v4",
                discovery = false,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.MOONSHOT_KIMI,
                "月之暗面 Kimi",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://api.moonshot.cn/v1",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.OPENROUTER,
                "OpenRouter",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                "https://openrouter.ai/api/v1",
                discovery = true,
                streaming = true,
                jsonObject = true,
                jsonSchema = false,
                usage = true,
            ),
            preset(
                ProviderPresetIds.CUSTOM_OPENAI_COMPATIBLE,
                "Custom OpenAI-compatible",
                ProviderAdapterKind.OPENAI_COMPATIBLE,
                ProviderAuthKind.BEARER_API_KEY,
                null,
                discovery = true,
                streaming = false,
                jsonObject = false,
                jsonSchema = false,
                usage = false,
            ),
        )

    fun find(presetId: String): ProviderPresetDescriptor? =
        builtIn.firstOrNull { it.presetId == presetId }

    private fun preset(
        id: String,
        name: String,
        adapter: ProviderAdapterKind,
        auth: ProviderAuthKind,
        baseUrl: String?,
        discovery: Boolean,
        streaming: Boolean,
        jsonObject: Boolean,
        jsonSchema: Boolean,
        usage: Boolean,
    ) = ProviderPresetDescriptor(
        presetId = id,
        displayName = name,
        adapterKind = adapter,
        authKind = auth,
        defaultBaseUrl = baseUrl,
        defaultCapabilities =
            ProviderCapabilities(
                supportsModelDiscovery = discovery,
                supportsStreaming = streaming,
                supportsJsonObject = jsonObject,
                supportsJsonSchema = jsonSchema,
                reportsUsage = usage,
            ),
    )
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
    val extraParameters: Map<String, String> = emptyMap(),
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
