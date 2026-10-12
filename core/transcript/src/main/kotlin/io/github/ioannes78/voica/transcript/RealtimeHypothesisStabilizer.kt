package io.github.ioannes78.voica.transcript

import java.util.ArrayDeque

/**
 * Stage 16A P4 stability layer.
 *
 * Native streaming engines remain the source of PARTIAL/FINAL hypotheses. This layer promotes only
 * a prefix that has survived [confirmationWindow] consecutive native partials to STABLE, while the
 * newest full native hypothesis remains PARTIAL. FINAL is authoritative and may correct a previously
 * promoted stable prefix; duplicate FINAL delivery is suppressed per recognizer continuity.
 */
class RealtimeHypothesisStabilizer(
    private val confirmationWindow: Int = DEFAULT_CONFIRMATION_WINDOW,
) {
    init {
        require(confirmationWindow >= 2) { "confirmationWindow must be at least 2" }
    }

    private val recentPartials = ArrayDeque<AsrHypothesis>()
    private var activeContinuityIndex: Long? = null
    private var stablePrefix = ""
    private var lastPartial: AsrHypothesis? = null
    private var finalEmitted = false

    fun accept(event: RealtimeAsrPipelineEvent): List<RealtimeAsrPipelineEvent> =
        when (event) {
            is RealtimeAsrPipelineEvent.SessionStarted -> {
                resetAll()
                listOf(event)
            }

            is RealtimeAsrPipelineEvent.ContinuityStarted -> {
                resetForContinuity(event.continuityIndex)
                listOf(event)
            }

            is RealtimeAsrPipelineEvent.Hypothesis -> stabilize(event)

            is RealtimeAsrPipelineEvent.SessionEnded -> {
                resetAll()
                listOf(event)
            }

            else -> listOf(event)
        }

    fun reset() {
        resetAll()
    }

    private fun stabilize(
        event: RealtimeAsrPipelineEvent.Hypothesis,
    ): List<RealtimeAsrPipelineEvent> {
        if (activeContinuityIndex != event.continuityIndex) {
            resetForContinuity(event.continuityIndex)
        }

        val hypothesis = event.hypothesis
        if (hypothesis.isFinal) {
            if (finalEmitted) return emptyList()
            finalEmitted = true
            recentPartials.clear()
            lastPartial = null
            return listOf(
                event.copy(
                    hypothesis = hypothesis.copy(
                        isFinal = true,
                        stability = AsrHypothesisStability.FINAL,
                    ),
                ),
            )
        }

        check(!finalEmitted) { "partial hypothesis arrived after FINAL in the same continuity" }

        val normalized = hypothesis.copy(
            isFinal = false,
            stability = AsrHypothesisStability.PARTIAL,
        )
        val duplicate = sameMutableHypothesis(lastPartial, normalized)
        lastPartial = normalized
        recentPartials.addLast(normalized)
        while (recentPartials.size > confirmationWindow) {
            recentPartials.removeFirst()
        }

        return buildList {
            if (recentPartials.size == confirmationWindow) {
                val candidate = commonPrefix(recentPartials.map { it.text })
                if (
                    candidate.length > stablePrefix.length &&
                    candidate.startsWith(stablePrefix)
                ) {
                    stablePrefix = candidate
                    add(
                        event.copy(
                            hypothesis = normalized.copy(
                                text = candidate,
                                tokens = prefixTokensForText(normalized, candidate),
                                stability = AsrHypothesisStability.STABLE,
                            ),
                        ),
                    )
                }
            }

            if (!duplicate) {
                add(event.copy(hypothesis = normalized))
            }
        }
    }

    private fun resetForContinuity(continuityIndex: Long) {
        activeContinuityIndex = continuityIndex
        stablePrefix = ""
        recentPartials.clear()
        lastPartial = null
        finalEmitted = false
    }

    private fun resetAll() {
        activeContinuityIndex = null
        stablePrefix = ""
        recentPartials.clear()
        lastPartial = null
        finalEmitted = false
    }

    private fun sameMutableHypothesis(
        left: AsrHypothesis?,
        right: AsrHypothesis,
    ): Boolean =
        left != null &&
            left.text == right.text &&
            left.tokens == right.tokens &&
            left.detectedLanguage == right.detectedLanguage &&
            left.confidence == right.confidence

    private fun commonPrefix(values: List<String>): String {
        if (values.isEmpty()) return ""
        var prefix = values.first()
        for (index in 1 until values.size) {
            val next = values[index]
            var commonLength = 0
            val maxLength = minOf(prefix.length, next.length)
            while (commonLength < maxLength && prefix[commonLength] == next[commonLength]) {
                commonLength += 1
            }
            prefix = prefix.substring(0, commonLength)
            if (prefix.isEmpty()) break
        }
        return prefix
    }

    private fun prefixTokensForText(
        hypothesis: AsrHypothesis,
        prefix: String,
    ): List<RelativeTimedToken> {
        if (prefix.isEmpty() || hypothesis.tokens.isEmpty()) return emptyList()
        val result = mutableListOf<RelativeTimedToken>()
        val text = StringBuilder()
        for (token in hypothesis.tokens) {
            val candidate = text.toString() + token.text
            if (candidate.length > prefix.length || !prefix.startsWith(candidate)) break
            result += token
            text.append(token.text)
            if (text.length == prefix.length) break
        }
        return if (text.toString() == prefix) result else emptyList()
    }

    private companion object {
        const val DEFAULT_CONFIRMATION_WINDOW = 3
    }
}

/**
 * Maps recognizer-relative token timing onto the canonical epoch sample timeline without clamping or
 * inventing timestamps. Invalid/mismatched timing is discarded as a whole.
 */
fun RealtimeAsrPipelineEvent.Hypothesis.canonicalTokens(
    source: TokenSource = TokenSource.FIRST_PASS,
): List<TranscriptToken> {
    val relativeTokens = hypothesis.tokens
    if (relativeTokens.isEmpty()) return emptyList()
    val acceptedSamples = acceptedEndSampleIndexExclusive - recognizerBaseSampleIndex
    if (acceptedSamples < 0L) return emptyList()

    val mapped = ArrayList<TranscriptToken>(relativeTokens.size)
    var previousStart: Long? = null
    for (token in relativeTokens) {
        val startOffset = token.startSampleOffset ?: return emptyList()
        if (startOffset < 0L || startOffset > acceptedSamples) return emptyList()
        val endOffset = token.endSampleOffsetExclusive
        if (endOffset != null && (endOffset < startOffset || endOffset > acceptedSamples)) {
            return emptyList()
        }

        val start = Math.addExact(recognizerBaseSampleIndex, startOffset)
        val end = endOffset?.let { Math.addExact(recognizerBaseSampleIndex, it) }
        if (previousStart != null && start < previousStart!!) return emptyList()
        previousStart = start

        mapped += TranscriptToken(
            text = token.text,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            source = source,
        )
    }
    return mapped
}
