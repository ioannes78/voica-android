package io.github.ioannes78.voica.ai

import kotlin.math.ceil

interface TokenEstimator {
    fun estimate(text: String): Int
}

class ConservativeTokenEstimator : TokenEstimator {
    override fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var tokens = 0
        var asciiRun = 0

        fun flushAscii() {
            if (asciiRun > 0) {
                tokens += ceil(asciiRun / 4.0).toInt().coerceAtLeast(1)
                asciiRun = 0
            }
        }

        text.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> flushAscii()
                codePoint in 0x21..0x7E -> asciiRun++
                else -> {
                    flushAscii()
                    tokens++
                }
            }
        }
        flushAscii()
        return tokens
    }
}

data class TokenBudgetRequest(
    val contextWindowTokens: Int?,
    val inputEstimatedTokens: Int,
    val systemTokens: Int,
    val templateTokens: Int,
    val schemaTokens: Int,
    val outputReserveTokens: Int,
    val safetyMarginTokens: Int,
)

sealed interface TokenBudgetPlan {
    val contextWindowTokens: Int
    val availableInputTokens: Int

    data class Direct(
        override val contextWindowTokens: Int,
        override val availableInputTokens: Int,
    ) : TokenBudgetPlan

    data class Chunked(
        override val contextWindowTokens: Int,
        override val availableInputTokens: Int,
        val targetChunkTokens: Int,
    ) : TokenBudgetPlan
}

class TokenBudgetPlanner(
    private val fallbackContextWindowTokens: Int = 16_384,
    private val chunkUtilization: Double = 0.85,
) {
    init {
        require(fallbackContextWindowTokens > 0)
        require(chunkUtilization in 0.5..0.95)
    }

    fun plan(request: TokenBudgetRequest): TokenBudgetPlan {
        require(request.inputEstimatedTokens >= 0)
        require(request.systemTokens >= 0)
        require(request.templateTokens >= 0)
        require(request.schemaTokens >= 0)
        require(request.outputReserveTokens >= 0)
        require(request.safetyMarginTokens >= 0)

        val context = request.contextWindowTokens ?: fallbackContextWindowTokens
        require(context > 0)
        val fixed =
            request.systemTokens +
                request.templateTokens +
                request.schemaTokens +
                request.outputReserveTokens +
                request.safetyMarginTokens
        val available = context - fixed
        require(available > 0) { "no context capacity left for transcript" }

        return if (request.inputEstimatedTokens <= available) {
            TokenBudgetPlan.Direct(context, available)
        } else {
            TokenBudgetPlan.Chunked(
                contextWindowTokens = context,
                availableInputTokens = available,
                targetChunkTokens = (available * chunkUtilization).toInt().coerceAtLeast(1),
            )
        }
    }
}
