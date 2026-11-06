package io.github.ioannes78.voica.ai

/**
 * Estimates prompt token usage without creating a LiteRT-LM Engine or Conversation.
 *
 * LiteRT-LM Android 0.18.0 exposes Conversation.getTokenCount() only after native conversation
 * creation, so Stage 17.2 cannot use it for the required pre-initialization budget gate.
 */
interface OfflineSummaryTokenEstimator {
    val id: String

    fun estimateTokens(text: String): Int
}

/**
 * Deterministic conservative estimator used until the validated Gemma artifact is calibrated on
 * real devices. This deliberately reports an estimate, never an exact tokenizer count.
 *
 * Rules before the final 20% headroom:
 * - CJK/Hangul/Kana code points: 1 unit each;
 * - ASCII letter/digit/underscore runs: ceil(length / 3) units;
 * - whitespace runs: 1 unit;
 * - supplementary Unicode code points: 2 units;
 * - other punctuation/symbols: 1 unit each.
 */
object ConservativeOfflineSummaryTokenEstimator : OfflineSummaryTokenEstimator {
    override val id: String = "CONSERVATIVE_TEXT_V1"

    override fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0

        var index = 0
        var baseUnits = 0L
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                Character.isWhitespace(codePoint) -> {
                    baseUnits += 1L
                    index += Character.charCount(codePoint)
                    while (index < text.length) {
                        val next = Character.codePointAt(text, index)
                        if (!Character.isWhitespace(next)) break
                        index += Character.charCount(next)
                    }
                }

                isAsciiWordCodePoint(codePoint) -> {
                    var runLength = 0
                    while (index < text.length) {
                        val next = Character.codePointAt(text, index)
                        if (!isAsciiWordCodePoint(next)) break
                        runLength += 1
                        index += Character.charCount(next)
                    }
                    baseUnits += ceilDiv(runLength.toLong(), ASCII_CHARS_PER_BASE_TOKEN)
                }

                isEastAsianTextCodePoint(codePoint) -> {
                    baseUnits += 1L
                    index += Character.charCount(codePoint)
                }

                Character.charCount(codePoint) == 2 -> {
                    baseUnits += 2L
                    index += 2
                }

                else -> {
                    baseUnits += 1L
                    index += Character.charCount(codePoint)
                }
            }
        }

        val withHeadroom =
            ceilDiv(baseUnits * HEADROOM_NUMERATOR, HEADROOM_DENOMINATOR)
                .coerceAtMost(Int.MAX_VALUE.toLong())
        return withHeadroom.toInt()
    }

    private fun isAsciiWordCodePoint(codePoint: Int): Boolean =
        codePoint in 'a'.code..'z'.code ||
            codePoint in 'A'.code..'Z'.code ||
            codePoint in '0'.code..'9'.code ||
            codePoint == '_'.code

    private fun isEastAsianTextCodePoint(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x3040..0x309F ||
            codePoint in 0x30A0..0x30FF ||
            codePoint in 0xAC00..0xD7AF

    private fun ceilDiv(
        value: Long,
        divisor: Long,
    ): Long =
        if (value == 0L) 0L else 1L + (value - 1L) / divisor

    private const val ASCII_CHARS_PER_BASE_TOKEN = 3L
    private const val HEADROOM_NUMERATOR = 6L
    private const val HEADROOM_DENOMINATOR = 5L
}

/**
 * Direct-summary budget inside the P0/P1 validated LiteRT-LM 4,000-token KV-cache envelope.
 *
 * These are Stage 17.2 initial values, not a theoretical Gemma context-window claim. Real-device QA
 * may calibrate them later without weakening the pre-initialization gate.
 */
data class OfflineSummaryDirectBudgetSpec(
    val runtimeMaxTokens: Int = 4_000,
    val outputReserveTokens: Int = 1_024,
    val runtimeTemplateReserveTokens: Int = 192,
    val safetyReserveTokens: Int = 256,
) {
    init {
        require(runtimeMaxTokens > 0)
        require(outputReserveTokens > 0)
        require(runtimeTemplateReserveTokens >= 0)
        require(safetyReserveTokens >= 0)
        require(reservedTokens < runtimeMaxTokens) {
            "offline summary reserves must leave a positive prompt budget"
        }
    }

    val reservedTokens: Int
        get() = outputReserveTokens + runtimeTemplateReserveTokens + safetyReserveTokens

    val maxEstimatedPromptTokens: Int
        get() = runtimeMaxTokens - reservedTokens
}

data class OfflineSummaryBudgetAssessment(
    val estimatorId: String,
    val estimatedPromptTokens: Int,
    val runtimeMaxTokens: Int,
    val outputReserveTokens: Int,
    val runtimeTemplateReserveTokens: Int,
    val safetyReserveTokens: Int,
    val maxEstimatedPromptTokens: Int,
    val estimatedTotalWithReserves: Int,
    val allowed: Boolean,
) {
    init {
        require(estimatorId.isNotBlank())
        require(estimatedPromptTokens >= 0)
        require(runtimeMaxTokens > 0)
        require(outputReserveTokens >= 0)
        require(runtimeTemplateReserveTokens >= 0)
        require(safetyReserveTokens >= 0)
        require(maxEstimatedPromptTokens >= 0)
        require(estimatedTotalWithReserves >= estimatedPromptTokens)
        require(allowed == (estimatedPromptTokens <= maxEstimatedPromptTokens))
    }
}

class OfflineSummaryBudgetExceededException(
    val assessment: OfflineSummaryBudgetAssessment,
) : IllegalArgumentException(
        "direct offline summary input estimate ${assessment.estimatedPromptTokens} exceeds " +
            "prompt budget ${assessment.maxEstimatedPromptTokens} (${assessment.estimatorId})",
    )

/** Pure preflight gate. It never truncates prompt text and never touches the native LLM runtime. */
class OfflineSummaryDirectBudgetGate(
    private val estimator: OfflineSummaryTokenEstimator = ConservativeOfflineSummaryTokenEstimator,
    private val spec: OfflineSummaryDirectBudgetSpec = OfflineSummaryDirectBudgetSpec(),
) {
    fun assess(prompt: RenderedOfflineSummaryPrompt): OfflineSummaryBudgetAssessment {
        val estimatedPromptTokens = estimator.estimateTokens(prompt.text)
        require(estimatedPromptTokens >= 0) { "token estimator must return a non-negative value" }
        val estimatedTotal =
            estimatedPromptTokens.toLong() + spec.reservedTokens.toLong()
        return OfflineSummaryBudgetAssessment(
            estimatorId = estimator.id,
            estimatedPromptTokens = estimatedPromptTokens,
            runtimeMaxTokens = spec.runtimeMaxTokens,
            outputReserveTokens = spec.outputReserveTokens,
            runtimeTemplateReserveTokens = spec.runtimeTemplateReserveTokens,
            safetyReserveTokens = spec.safetyReserveTokens,
            maxEstimatedPromptTokens = spec.maxEstimatedPromptTokens,
            estimatedTotalWithReserves = estimatedTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            allowed = estimatedPromptTokens <= spec.maxEstimatedPromptTokens,
        )
    }

    /**
     * Returns diagnostics when the exact rendered prompt is allowed; otherwise throws before any
     * caller can start native inference. The prompt object itself is never rewritten or shortened.
     */
    fun requireFits(prompt: RenderedOfflineSummaryPrompt): OfflineSummaryBudgetAssessment {
        val assessment = assess(prompt)
        if (!assessment.allowed) {
            throw OfflineSummaryBudgetExceededException(assessment)
        }
        return assessment
    }
}
