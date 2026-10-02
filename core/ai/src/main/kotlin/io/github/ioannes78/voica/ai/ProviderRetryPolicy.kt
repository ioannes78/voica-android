package io.github.ioannes78.voica.ai

import kotlin.math.min
import kotlin.math.pow

class ProviderRetryPolicy(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 500L,
    private val maxDelayMs: Long = 8_000L,
) {
    init {
        require(maxAttempts >= 1)
        require(baseDelayMs >= 0)
        require(maxDelayMs >= baseDelayMs)
    }

    fun shouldRetry(
        failure: ProviderFailure,
        attempt: Int,
    ): Boolean {
        if (attempt >= maxAttempts) return false
        if (!failure.retryable) return false
        return failure.code in RETRYABLE_CODES
    }

    fun delayMs(
        failure: ProviderFailure,
        attempt: Int,
    ): Long {
        failure.retryAfterMs?.takeIf { it >= 0L }?.let {
            return min(it, maxDelayMs)
        }
        val exponent = (attempt - 1).coerceAtLeast(0)
        val backoff =
            (baseDelayMs * 2.0.pow(exponent.toDouble()))
                .toLong()
                .coerceAtMost(maxDelayMs)
        val deterministicJitter =
            if (backoff == 0L) 0L else ((attempt * 97L) % (backoff / 4L + 1L))
        return (backoff + deterministicJitter).coerceAtMost(maxDelayMs)
    }

    private companion object {
        val RETRYABLE_CODES =
            setOf(
                ProviderErrorCode.NETWORK_UNAVAILABLE,
                ProviderErrorCode.DNS_FAILED,
                ProviderErrorCode.TIMEOUT,
                ProviderErrorCode.RATE_LIMITED,
                ProviderErrorCode.PROVIDER_5XX,
            )
    }
}
