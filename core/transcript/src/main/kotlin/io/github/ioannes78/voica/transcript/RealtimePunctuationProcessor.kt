package io.github.ioannes78.voica.transcript

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

enum class RealtimeStablePunctuationMode {
    FINAL_ONLY,
    STABLE_AND_FINAL,
}

data class RealtimePunctuationSnapshot(
    val stableAttempts: Long = 0,
    val finalAttempts: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val nativeReliableSkips: Long = 0,
)

data class RealtimePunctuationResult(
    val source: RealtimeAsrPipelineEvent.Hypothesis,
    val rawText: String,
    val displayText: String,
    val attempted: Boolean,
    val externalPunctuationApplied: Boolean,
    val externalPunctuationFailed: Boolean,
) {
    val displayHypothesis: AsrHypothesis
        get() =
            source.hypothesis.copy(
                text = displayText,
                punctuationCapability =
                    if (externalPunctuationApplied) {
                        PunctuationCapability.RELIABLE
                    } else {
                        source.hypothesis.punctuationCapability
                    },
            )
}

/**
 * Stage 16A P5 external punctuation policy for realtime hypotheses.
 *
 * PARTIAL is never punctuated. FINAL is punctuated whenever an external punctuation engine is
 * available unless the native hypothesis already declares RELIABLE punctuation. STABLE punctuation
 * is opt-in after device benchmarking; the product default remains FINAL_ONLY until latency/churn
 * evidence says otherwise.
 *
 * Punctuation failure is deliberately recoverable: the raw ASR text remains the display/final text.
 * Cancellation is never swallowed. Engine ownership stays with the caller/model lease.
 */
class RealtimePunctuationProcessor(
    private val punctuationEngine: PunctuationEngine?,
    private val stableMode: RealtimeStablePunctuationMode = RealtimeStablePunctuationMode.FINAL_ONLY,
) {
    private val stableAttempts = AtomicLong(0)
    private val finalAttempts = AtomicLong(0)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val nativeReliableSkips = AtomicLong(0)

    suspend fun process(
        event: RealtimeAsrPipelineEvent.Hypothesis,
    ): RealtimePunctuationResult {
        val hypothesis = event.hypothesis
        val rawText = hypothesis.text

        if (hypothesis.punctuationCapability == PunctuationCapability.RELIABLE) {
            nativeReliableSkips.incrementAndGet()
            return passthrough(event)
        }

        val shouldAttempt =
            when (hypothesis.stability) {
                AsrHypothesisStability.PARTIAL -> false
                AsrHypothesisStability.STABLE ->
                    stableMode == RealtimeStablePunctuationMode.STABLE_AND_FINAL
                AsrHypothesisStability.FINAL -> true
            }

        val engine = punctuationEngine
        if (!shouldAttempt || engine == null || rawText.isBlank()) {
            return passthrough(event)
        }

        when (hypothesis.stability) {
            AsrHypothesisStability.STABLE -> stableAttempts.incrementAndGet()
            AsrHypothesisStability.FINAL -> finalAttempts.incrementAndGet()
            AsrHypothesisStability.PARTIAL -> error("PARTIAL must not reach punctuation")
        }

        return try {
            val punctuated = engine.addPunctuation(rawText)
            successes.incrementAndGet()
            RealtimePunctuationResult(
                source = event,
                rawText = rawText,
                displayText = punctuated,
                attempted = true,
                externalPunctuationApplied = true,
                externalPunctuationFailed = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failures.incrementAndGet()
            RealtimePunctuationResult(
                source = event,
                rawText = rawText,
                displayText = rawText,
                attempted = true,
                externalPunctuationApplied = false,
                externalPunctuationFailed = true,
            )
        }
    }

    fun snapshot(): RealtimePunctuationSnapshot =
        RealtimePunctuationSnapshot(
            stableAttempts = stableAttempts.get(),
            finalAttempts = finalAttempts.get(),
            successes = successes.get(),
            failures = failures.get(),
            nativeReliableSkips = nativeReliableSkips.get(),
        )

    private fun passthrough(
        event: RealtimeAsrPipelineEvent.Hypothesis,
    ): RealtimePunctuationResult =
        RealtimePunctuationResult(
            source = event,
            rawText = event.hypothesis.text,
            displayText = event.hypothesis.text,
            attempted = false,
            externalPunctuationApplied = false,
            externalPunctuationFailed = false,
        )
}
