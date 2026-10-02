package io.github.ioannes78.voica.llm

import io.github.ioannes78.voica.ai.ConnectionTestResult
import io.github.ioannes78.voica.ai.LlmGenerationRequest
import io.github.ioannes78.voica.ai.LlmGenerationResponse
import io.github.ioannes78.voica.ai.ProviderAdapterKind
import io.github.ioannes78.voica.ai.ProviderCapabilities
import io.github.ioannes78.voica.ai.ProviderErrorCode
import io.github.ioannes78.voica.ai.ProviderFailure
import io.github.ioannes78.voica.ai.ProviderModel
import io.github.ioannes78.voica.ai.ProviderPresetCatalog
import io.github.ioannes78.voica.ai.ProviderProfile
import io.github.ioannes78.voica.ai.TextLlmProvider

class ProviderAdapterRegistry(
    transport: LlmHttpTransport,
    credentials: ProviderCredentialResolver,
) {
    private val openAiCompatible =
        OpenAiCompatibleTextLlmProvider(transport, credentials)
    private val gemini =
        GeminiTextLlmProvider(transport, credentials)
    private val vertex =
        Stage11VertexContractProvider()

    fun forProfile(profile: ProviderProfile): TextLlmProvider {
        val preset =
            ProviderPresetCatalog.find(profile.presetId)
                ?: throw IllegalArgumentException("unknown provider preset")
        return when (preset.adapterKind) {
            ProviderAdapterKind.OPENAI_COMPATIBLE -> openAiCompatible
            ProviderAdapterKind.XAI_GROK -> openAiCompatible
            ProviderAdapterKind.GOOGLE_GEMINI -> gemini
            ProviderAdapterKind.GOOGLE_VERTEX -> vertex
        }
    }
}

/**
 * Stage 11 freezes Vertex as a separate provider contract, but deliberately does not
 * persist service-account private keys or implement a device-side OAuth flow.
 * Standard Vertex enterprise authentication is completed in a later provider-auth
 * enhancement instead of being incorrectly treated as an AI Studio API key.
 */
class Stage11VertexContractProvider : TextLlmProvider {
    override suspend fun capabilities(profile: ProviderProfile): ProviderCapabilities =
        profile.effectiveCapabilities()

    override suspend fun discoverModels(
        profile: ProviderProfile,
    ): Result<List<ProviderModel>> =
        Result.failure(unsupported())

    override suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult =
        ConnectionTestResult(
            success = false,
            capabilities = capabilities(profile),
            failure = unsupported().failure,
        )

    override suspend fun generate(
        profile: ProviderProfile,
        request: LlmGenerationRequest,
    ): Result<LlmGenerationResponse> =
        Result.failure(unsupported())

    override fun cancel(requestId: String) = Unit

    private fun unsupported() =
        ProviderCallException(
            ProviderFailure(
                ProviderErrorCode.INVALID_CONFIGURATION,
                "Vertex AI requires Google Cloud OAuth configuration; Stage 11 does not store service-account private keys on device",
            ),
        )
}
