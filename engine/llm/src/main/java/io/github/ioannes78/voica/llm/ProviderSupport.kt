package io.github.ioannes78.voica.llm

import io.github.ioannes78.voica.ai.ProviderCapabilities
import io.github.ioannes78.voica.ai.ProviderErrorCode
import io.github.ioannes78.voica.ai.ProviderFailure
import io.github.ioannes78.voica.ai.ProviderPresetCatalog
import io.github.ioannes78.voica.ai.ProviderProfile
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ProviderCallException(
    val failure: ProviderFailure,
) : Exception(failure.sanitizedMessage ?: failure.code.name)

internal val PROVIDER_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

internal fun ProviderProfile.effectiveCapabilities(): ProviderCapabilities =
    capabilityOverrides
        ?: ProviderPresetCatalog.find(presetId)?.defaultCapabilities
        ?: ProviderCapabilities()

internal fun ProviderProfile.validatedBaseUrl(): String {
    require(enabled) { "provider profile is disabled" }
    val normalized = baseUrl.trim().trimEnd('/')
    val uri = URI(normalized)
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "provider base URL requires HTTPS"
    }
    require(!uri.host.isNullOrBlank()) { "provider base URL host is missing" }
    require(uri.userInfo == null) { "credentials are not allowed in provider URL" }
    require(uri.fragment == null) { "provider URL fragment is not allowed" }
    return normalized
}

internal fun joinUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

internal fun mapTransportFailure(error: Throwable): ProviderFailure =
    when (error) {
        is LlmTransportException ->
            when (error.kind) {
                TransportFailureKind.INVALID_URL ->
                    ProviderFailure(ProviderErrorCode.INVALID_CONFIGURATION, "invalid provider URL")
                TransportFailureKind.DNS ->
                    ProviderFailure(ProviderErrorCode.DNS_FAILED, "provider DNS lookup failed", retryable = true)
                TransportFailureKind.TLS ->
                    ProviderFailure(ProviderErrorCode.TLS_FAILED, "provider TLS handshake failed")
                TransportFailureKind.TIMEOUT ->
                    ProviderFailure(ProviderErrorCode.TIMEOUT, "provider request timed out", retryable = true)
                TransportFailureKind.NETWORK ->
                    ProviderFailure(ProviderErrorCode.NETWORK_UNAVAILABLE, "provider network request failed", retryable = true)
                TransportFailureKind.CANCELLED ->
                    ProviderFailure(ProviderErrorCode.CANCELLED, "provider request cancelled")
                TransportFailureKind.RESPONSE_TOO_LARGE ->
                    ProviderFailure(ProviderErrorCode.MALFORMED_RESPONSE, "provider response exceeded size limit")
            }
        else ->
            ProviderFailure(ProviderErrorCode.NETWORK_UNAVAILABLE, "provider request failed", retryable = true)
    }

internal fun classifyHttpFailure(
    response: LlmHttpResponse,
    modelScoped: Boolean,
): ProviderFailure {
    val retryAfter =
        response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.times(1000L)
    val safeCode =
        runCatching {
            val error = PROVIDER_JSON.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject
            error?.safeErrorCode()
        }.getOrNull()
    val codeText = safeCode.orEmpty().lowercase()
    val code =
        when (response.statusCode) {
            400 ->
                when {
                    "context" in codeText || "token" in codeText ->
                        ProviderErrorCode.CONTEXT_LIMIT_EXCEEDED
                    else ->
                        ProviderErrorCode.INVALID_CONFIGURATION
                }
            401 -> ProviderErrorCode.AUTHENTICATION_FAILED
            403 -> ProviderErrorCode.PERMISSION_DENIED
            404 ->
                if (modelScoped) ProviderErrorCode.MODEL_NOT_FOUND else ProviderErrorCode.INVALID_CONFIGURATION
            408 -> ProviderErrorCode.TIMEOUT
            413 -> ProviderErrorCode.PAYLOAD_TOO_LARGE
            429 ->
                if ("quota" in codeText || "billing" in codeText) {
                    ProviderErrorCode.QUOTA_EXCEEDED
                } else {
                    ProviderErrorCode.RATE_LIMITED
                }
            in 500..599 -> ProviderErrorCode.PROVIDER_5XX
            else -> ProviderErrorCode.INVALID_CONFIGURATION
        }
    return ProviderFailure(
        code = code,
        sanitizedMessage =
            buildString {
                append("provider HTTP ")
                append(response.statusCode)
                safeCode?.take(80)?.let {
                    append(" (")
                    append(it)
                    append(")")
                }
            },
        retryAfterMs = retryAfter,
        retryable =
            response.statusCode == 408 ||
                response.statusCode == 429 ||
                response.statusCode in 500..599,
    )
}

private fun JsonObject.safeErrorCode(): String? =
    listOf("code", "type", "status")
        .asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.contentOrNull }
        .firstOrNull()

internal suspend fun requireCredential(
    resolver: ProviderCredentialResolver,
    profile: ProviderProfile,
): String =
    resolver.resolve(profile.credentialRef)
        ?.takeIf { it.isNotBlank() }
        ?: throw ProviderCallException(
            ProviderFailure(
                ProviderErrorCode.AUTHENTICATION_FAILED,
                "provider credential is missing",
            ),
        )
