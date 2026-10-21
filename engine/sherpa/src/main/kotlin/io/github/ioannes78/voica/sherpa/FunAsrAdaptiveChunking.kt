package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.TimestampCapability
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.RelativeTimedToken
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 16B post-gate diagnostics for the pinned FunASR Nano revision.
 *
 * sherpa-onnx v1.13.8 does not expose tokenizer counts, valid_len, or the terminal decode reason
 * through its Kotlin API. Fields derived without that API surface are explicitly marked by
 * [budgetValuesEstimated] / [terminationInferred]. Synthetic sherpa timestamps are used only to
 * infer effective audio coverage; they are not promoted as product token timing here.
 */
data class FunAsrRuntimeDiagnostic(
    val maxTotalLen: Int,
    val promptTokens: Int,
    val hotwordPromptTokens: Int,
    val audioTokenLen: Int,
    val contextLen: Int,
    val availableGenerationTokens: Int,
    val requestedMaxNewTokens: Int,
    val generatedTokenCount: Int,
    val eosReached: Boolean?,
    val audioTruncated: Boolean,
    val generationHitCapacity: Boolean,
    val chunkStart: Int,
    val chunkEnd: Int,
    val chunkDurationMs: Long,
    val outputChars: Int,
    val retryDepth: Int,
    val accepted: Boolean,
    val retryReason: String?,
    val budgetValuesEstimated: Boolean = true,
    val terminationInferred: Boolean = true,
)

object FunAsrRuntimeDiagnostics {
    private const val MAX_RECORDS = 256
    private val lock = Any()
    private val records = ArrayDeque<FunAsrRuntimeDiagnostic>()

    fun record(diagnostic: FunAsrRuntimeDiagnostic) {
        synchronized(lock) {
            while (records.size >= MAX_RECORDS) records.removeFirst()
            records.addLast(diagnostic)
        }
    }

    fun snapshot(): List<FunAsrRuntimeDiagnostic> =
        synchronized(lock) { records.toList() }

    fun clear() {
        synchronized(lock) { records.clear() }
    }

    fun formatForQa(): String {
        val current = snapshot()
        if (current.isEmpty()) return "No FunASR post-gate diagnostics yet."
        return buildString {
            current.forEachIndexed { index, item ->
                append('#').append(index + 1)
                append(" range=").append(item.chunkStart).append("..").append(item.chunkEnd)
                append(" durationMs=").append(item.chunkDurationMs)
                append(" maxTotalLen=").append(item.maxTotalLen)
                append(" promptTokens~=").append(item.promptTokens)
                append(" hotwordPromptTokens~=").append(item.hotwordPromptTokens)
                append(" audioTokenLen~=").append(item.audioTokenLen)
                append(" contextLen~=").append(item.contextLen)
                append(" availableGenerationTokens~=").append(item.availableGenerationTokens)
                append(" requestedMaxNewTokens=").append(item.requestedMaxNewTokens)
                append(" generatedTokenCount~=").append(item.generatedTokenCount)
                append(" eosReached=").append(item.eosReached?.toString() ?: "unknown")
                append(" audioTruncated=").append(item.audioTruncated)
                append(" generationHitCapacity~=").append(item.generationHitCapacity)
                append(" outputChars=").append(item.outputChars)
                append(" retryDepth=").append(item.retryDepth)
                append(" accepted=").append(item.accepted)
                item.retryReason?.let { append(" retryReason=").append(it) }
                append('\n')
            }
        }.trimEnd()
    }
}

internal data class FunAsrBudgetEstimate(
    val maxTotalLen: Int,
    val promptTokens: Int,
    val hotwordPromptTokens: Int,
    val reservedGenerationTokens: Int,
    val safetyMarginTokens: Int,
    val safeAudioTokens: Int,
    val safeAudioSamples: Int,
    val targetSamples: Int,
)

internal data class FunAsrChunkRange(
    val start: Int,
    val endExclusive: Int,
    val overlapFromPrevious: Int,
    val naturalBoundaryBefore: Boolean,
) {
    init {
        require(start >= 0)
        require(endExclusive > start)
        require(overlapFromPrevious >= 0)
    }

    val sampleCount: Int
        get() = endExclusive - start
}

internal fun estimateFunAsrBudget(
    settings: LargeOfflineAsrSettings,
    sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
): FunAsrBudgetEstimate {
    require(sampleRateHz > 0)
    val promptTokens =
        CHAT_TEMPLATE_ESTIMATE_TOKENS +
            estimateTextTokens(settings.funAsrSystemPrompt) +
            estimateTextTokens(settings.funAsrUserPrompt)
    val hotwordPromptTokens =
        settings.funAsrHotwords.trim().takeIf { it.isNotEmpty() }?.let { hotwords ->
            HOTWORD_SCAFFOLD_ESTIMATE_TOKENS + estimateTextTokens(hotwords)
        } ?: 0
    val safeAudioTokens =
        (FUNASR_PINNED_MAX_TOTAL_LEN -
            promptTokens -
            hotwordPromptTokens -
            RESERVED_GENERATION_TOKENS -
            SAFETY_MARGIN_TOKENS).coerceAtLeast(1)
    val safeSeconds = safeAudioTokens.toDouble() / ESTIMATED_AUDIO_TOKENS_PER_SECOND
    val safeAudioSamples = floor(safeSeconds * sampleRateHz).toInt().coerceAtLeast(1)
    val targetSamples =
        min(
            safeAudioSamples,
            (TARGET_CHUNK_SECONDS * sampleRateHz).toInt(),
        ).coerceAtLeast(1)

    return FunAsrBudgetEstimate(
        maxTotalLen = FUNASR_PINNED_MAX_TOTAL_LEN,
        promptTokens = promptTokens,
        hotwordPromptTokens = hotwordPromptTokens,
        reservedGenerationTokens = RESERVED_GENERATION_TOKENS,
        safetyMarginTokens = SAFETY_MARGIN_TOKENS,
        safeAudioTokens = safeAudioTokens,
        safeAudioSamples = safeAudioSamples,
        targetSamples = targetSamples,
    )
}

internal fun planFunAsrChunks(
    samples: ShortArray,
    settings: LargeOfflineAsrSettings,
    sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
): List<FunAsrChunkRange> {
    require(samples.isNotEmpty())
    val budget = estimateFunAsrBudget(settings, sampleRateHz)
    require(budget.safeAudioSamples >= (MIN_BUDGET_SECONDS * sampleRateHz).toInt()) {
        "FunASR hotword/prompt context leaves insufficient audio budget"
    }

    if (samples.size <= budget.targetSamples) {
        return listOf(FunAsrChunkRange(0, samples.size, 0, true))
    }

    val overlapSamples = (OVERLAP_SECONDS * sampleRateHz).toInt()
    val searchRadiusSamples = (BOUNDARY_SEARCH_SECONDS * sampleRateHz).toInt()
    val minChunkSamples = (MIN_CHUNK_SECONDS * sampleRateHz).toInt()
    val output = ArrayList<FunAsrChunkRange>()
    var start = 0
    var overlapFromPrevious = 0
    var naturalBoundaryBefore = true

    while (start < samples.size) {
        val remaining = samples.size - start
        if (remaining <= budget.targetSamples) {
            output +=
                FunAsrChunkRange(
                    start = start,
                    endExclusive = samples.size,
                    overlapFromPrevious = overlapFromPrevious,
                    naturalBoundaryBefore = naturalBoundaryBefore,
                )
            break
        }

        val desired = min(start + budget.targetSamples, samples.size)
        val earliest = max(start + minChunkSamples, desired - searchRadiusSamples)
        val latest = min(
            min(start + budget.safeAudioSamples, samples.size - minChunkSamples),
            desired + searchRadiusSamples,
        )
        val natural =
            if (latest > earliest) {
                findReliableLowEnergyBoundary(samples, earliest, latest, sampleRateHz)
            } else {
                null
            }
        val end = natural ?: desired
        require(end > start)
        require(end - start <= budget.safeAudioSamples) {
            "FunASR planner exceeded estimated audio budget"
        }

        output +=
            FunAsrChunkRange(
                start = start,
                endExclusive = end,
                overlapFromPrevious = overlapFromPrevious,
                naturalBoundaryBefore = naturalBoundaryBefore,
            )

        if (natural != null) {
            start = end
            overlapFromPrevious = 0
            naturalBoundaryBefore = true
        } else {
            val actualOverlap = min(overlapSamples, max(0, end - start - 1))
            val nextStart = end - actualOverlap
            require(nextStart > start) { "FunASR overlap planner made no forward progress" }
            start = nextStart
            overlapFromPrevious = actualOverlap
            naturalBoundaryBefore = false
        }
    }

    return output
}

private fun findReliableLowEnergyBoundary(
    samples: ShortArray,
    earliest: Int,
    latest: Int,
    sampleRateHz: Int,
): Int? {
    val halfWindow = max(1, (ENERGY_WINDOW_SECONDS * sampleRateHz / 2.0).toInt())
    val step = max(1, (ENERGY_STEP_SECONDS * sampleRateHz).toInt())
    val candidates = ArrayList<Pair<Int, Double>>()
    var center = earliest
    while (center <= latest) {
        val from = max(0, center - halfWindow)
        val to = min(samples.size, center + halfWindow)
        if (to > from) {
            var total = 0.0
            for (index in from until to) {
                total += abs(samples[index].toInt()).toDouble()
            }
            candidates += center to (total / (to - from).toDouble())
        }
        center += step
    }
    if (candidates.isEmpty()) return null

    val sorted = candidates.map { it.second }.sorted()
    val median = sorted[sorted.size / 2]
    val best = candidates.minByOrNull { it.second } ?: return null
    val threshold = min(ABSOLUTE_SILENCE_MEAN, median * RELATIVE_SILENCE_RATIO)
    return best.first.takeIf { best.second <= threshold }
}

internal fun mergeFunAsrOverlapText(
    leftRaw: String,
    rightRaw: String,
): String {
    val left = leftRaw.trim()
    val right = rightRaw.trim()
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left

    val leftNormalized = normalizeForOverlap(left)
    val rightNormalized = normalizeForOverlap(right)
    val maxLength = min(min(leftNormalized.text.length, rightNormalized.text.length), MAX_OVERLAP_CHARS)

    var matched = 0
    for (length in maxLength downTo MIN_EXACT_OVERLAP_CHARS) {
        if (
            leftNormalized.text.regionMatches(
                leftNormalized.text.length - length,
                rightNormalized.text,
                0,
                length,
                ignoreCase = true,
            )
        ) {
            matched = length
            break
        }
    }

    if (matched == 0) {
        for (length in maxLength downTo MIN_FUZZY_OVERLAP_CHARS) {
            val a = leftNormalized.text.takeLast(length)
            val b = rightNormalized.text.take(length)
            if (levenshteinDistance(a, b) <= max(1, length / 5)) {
                matched = length
                break
            }
        }
    }

    if (matched == 0) return concatenateWithoutOverlap(left, right)

    var rawCut = rightNormalized.rawEndByNormalizedIndex[matched - 1]
    while (rawCut < right.length && !right[rawCut].isLetterOrDigit()) rawCut += 1
    val suffix = right.substring(rawCut).trimStart()
    return if (suffix.isEmpty()) left else concatenateWithoutOverlap(left, suffix)
}

private data class NormalizedText(
    val text: String,
    val rawEndByNormalizedIndex: List<Int>,
)

private fun normalizeForOverlap(raw: String): NormalizedText {
    val text = StringBuilder()
    val ends = ArrayList<Int>()
    raw.forEachIndexed { index, char ->
        if (char.isLetterOrDigit()) {
            text.append(char.lowercaseChar())
            ends += index + 1
        }
    }
    return NormalizedText(text.toString(), ends)
}

private fun concatenateWithoutOverlap(left: String, right: String): String {
    val needsSpace =
        left.lastOrNull()?.let { l ->
            right.firstOrNull()?.let { r ->
                l.isLetterOrDigit() && r.isLetterOrDigit() && l.code < 128 && r.code < 128
            }
        } == true
    return if (needsSpace) "$left $right" else left + right
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1)
        current[0] = i + 1
        for (j in b.indices) {
            current[j + 1] =
                minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
        }
        previous = current
    }
    return previous[b.length]
}

internal class FunAsrAdaptiveOfflineAsrEngine(
    private val delegate: SecondPassAsrEngine,
    private val settings: LargeOfflineAsrSettings,
) : SecondPassAsrEngine {
    override val model = delegate.model

    // sherpa-onnx v1.13.8 exposes evenly distributed FunASR timestamps. They remain available on
    // the private delegate solely for context-budget diagnostics, but are deliberately blocked from
    // the product contract until real CTC forced alignment is available.
    override val capabilities: AsrCapabilities =
        delegate.capabilities.copy(
            supportsTokenTiming = false,
            timestampCapability = TimestampCapability.NONE,
        )
    private var closed = false

    init {
        require(model.runtimeModelType == SherpaOfflineAsrModelType.FUNASR_NANO)
    }

    override suspend fun transcribe(
        samples: ShortArray,
        sampleRateHz: Int,
    ): AsrHypothesis {
        check(!closed) { "FunASR adaptive engine is closed" }
        require(sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
        require(samples.isNotEmpty())

        val plan = planFunAsrChunks(samples, settings, sampleRateHz)
        val accepted = ArrayList<AdaptiveResult>(plan.size)
        plan.forEach { range ->
            accepted +=
                transcribeRange(
                    parentSamples = samples,
                    start = range.start,
                    endExclusive = range.endExclusive,
                    sampleRateHz = sampleRateHz,
                    retryDepth = 0,
                )
        }

        var mergedText = ""
        accepted.forEachIndexed { index, result ->
            mergedText =
                if (index == 0) {
                    result.hypothesis.text.trim()
                } else if (plan[index].overlapFromPrevious > 0) {
                    mergeFunAsrOverlapText(mergedText, result.hypothesis.text)
                } else {
                    concatenateWithoutOverlap(mergedText, result.hypothesis.text.trim())
                }
        }

        val languages = accepted.mapNotNull { it.hypothesis.detectedLanguage }.distinct()

        return AsrHypothesis(
            text = mergedText,
            tokens = emptyList(),
            detectedLanguage = languages.singleOrNull(),
            confidence = null,
            punctuationCapability = capabilities.punctuationCapability,
            isFinal = true,
        )
    }

    private suspend fun transcribeRange(
        parentSamples: ShortArray,
        start: Int,
        endExclusive: Int,
        sampleRateHz: Int,
        retryDepth: Int,
    ): AdaptiveResult {
        val chunk = parentSamples.copyOfRange(start, endExclusive)
        val hypothesis = delegate.transcribe(chunk, sampleRateHz)
        val diagnostic = diagnose(
            hypothesis = hypothesis,
            sampleCount = chunk.size,
            sampleRateHz = sampleRateHz,
            chunkStart = start,
            chunkEnd = endExclusive,
            retryDepth = retryDepth,
        )
        val retryReason = diagnostic.retryReason
        if (retryReason == null) {
            FunAsrRuntimeDiagnostics.record(diagnostic.copy(accepted = true))
            return AdaptiveResult(hypothesis = hypothesis, hadRetry = false)
        }

        FunAsrRuntimeDiagnostics.record(diagnostic.copy(accepted = false))
        val minRetrySamples = (MIN_RETRY_SECONDS * sampleRateHz).toInt()
        if (retryDepth >= MAX_RETRY_DEPTH || chunk.size <= minRetrySamples * 2) {
            error(
                "FunASR chunk remained incomplete after adaptive retry: " +
                    "$retryReason range=$start..$endExclusive",
            )
        }

        val midpoint = start + chunk.size / 2
        val search = (RETRY_BOUNDARY_SEARCH_SECONDS * sampleRateHz).toInt()
        val earliest = max(start + minRetrySamples, midpoint - search)
        val latest = min(endExclusive - minRetrySamples, midpoint + search)
        val natural =
            if (latest > earliest) {
                findReliableLowEnergyBoundary(parentSamples, earliest, latest, sampleRateHz)
            } else {
                null
            }
        val split = natural ?: midpoint
        val overlap =
            if (natural != null) 0 else (RETRY_OVERLAP_SECONDS * sampleRateHz).toInt()
        val rightStart = max(start + 1, split - overlap)
        require(split > start && rightStart < endExclusive)

        val left =
            transcribeRange(
                parentSamples,
                start,
                split,
                sampleRateHz,
                retryDepth + 1,
            )
        val right =
            transcribeRange(
                parentSamples,
                rightStart,
                endExclusive,
                sampleRateHz,
                retryDepth + 1,
            )
        val text =
            if (overlap > 0) {
                mergeFunAsrOverlapText(left.hypothesis.text, right.hypothesis.text)
            } else {
                concatenateWithoutOverlap(left.hypothesis.text.trim(), right.hypothesis.text.trim())
            }
        val languages =
            listOfNotNull(left.hypothesis.detectedLanguage, right.hypothesis.detectedLanguage).distinct()
        return AdaptiveResult(
            hypothesis =
                AsrHypothesis(
                    text = text,
                    tokens = emptyList(),
                    detectedLanguage = languages.singleOrNull(),
                    confidence = null,
                    punctuationCapability = capabilities.punctuationCapability,
                    isFinal = true,
                ),
            hadRetry = true,
        )
    }

    private fun diagnose(
        hypothesis: AsrHypothesis,
        sampleCount: Int,
        sampleRateHz: Int,
        chunkStart: Int,
        chunkEnd: Int,
        retryDepth: Int,
    ): FunAsrRuntimeDiagnostic {
        val budget = estimateFunAsrBudget(settings, sampleRateHz)
        val audioTokens =
            ceil(sampleCount.toDouble() / sampleRateHz * ESTIMATED_AUDIO_TOKENS_PER_SECOND)
                .toInt()
        val contextLen = budget.promptTokens + budget.hotwordPromptTokens + audioTokens
        val availableGeneration = (budget.maxTotalLen - contextLen).coerceAtLeast(0)
        val generated = hypothesis.tokens.size
        val effectiveCoverage = inferSyntheticAudioCoverageSamples(hypothesis.tokens)
        val coverageTolerance = (AUDIO_TRUNCATION_TOLERANCE_SECONDS * sampleRateHz).toLong()
        val audioTruncated =
            effectiveCoverage != null &&
                effectiveCoverage + coverageTolerance < sampleCount.toLong()
        val generationHitCapacity =
            generated >= settings.funAsrMaxNewTokens - 1 ||
                (availableGeneration > 0 && generated + CAPACITY_GUARD_TOKENS >= availableGeneration)
        val shortOutput =
            sampleCount >= (SHORT_OUTPUT_MIN_SECONDS * sampleRateHz).toInt() &&
                hypothesis.text.trim().length <= SHORT_OUTPUT_MAX_CHARS
        val retryReason =
            when {
                hypothesis.text.isBlank() -> "blank_with_vad_speech"
                audioTruncated -> "audio_context_truncated"
                generationHitCapacity -> "generation_capacity"
                shortOutput -> "abnormally_short_output"
                else -> null
            }

        return FunAsrRuntimeDiagnostic(
            maxTotalLen = budget.maxTotalLen,
            promptTokens = budget.promptTokens,
            hotwordPromptTokens = budget.hotwordPromptTokens,
            audioTokenLen = audioTokens,
            contextLen = contextLen,
            availableGenerationTokens = availableGeneration,
            requestedMaxNewTokens = settings.funAsrMaxNewTokens,
            generatedTokenCount = generated,
            eosReached = if (generationHitCapacity) false else null,
            audioTruncated = audioTruncated,
            generationHitCapacity = generationHitCapacity,
            chunkStart = chunkStart,
            chunkEnd = chunkEnd,
            chunkDurationMs = sampleCount.toLong() * 1_000L / sampleRateHz.toLong(),
            outputChars = hypothesis.text.trim().length,
            retryDepth = retryDepth,
            accepted = retryReason == null,
            retryReason = retryReason,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }

    private data class AdaptiveResult(
        val hypothesis: AsrHypothesis,
        val hadRetry: Boolean,
    )
}

private fun inferSyntheticAudioCoverageSamples(tokens: List<RelativeTimedToken>): Long? {
    if (tokens.isEmpty()) return null
    val last = tokens.last().startSampleOffset ?: return null
    return if (tokens.size == 1) Math.multiplyExact(last, 2L) else last
}

private fun estimateTextTokens(text: String): Int {
    var tokens = 0
    var asciiRun = 0
    fun flushAscii() {
        if (asciiRun > 0) {
            tokens += ceil(asciiRun / 4.0).toInt()
            asciiRun = 0
        }
    }
    text.forEach { char ->
        when {
            char.isWhitespace() -> flushAscii()
            char.code < 128 && char.isLetterOrDigit() -> asciiRun += 1
            else -> {
                flushAscii()
                tokens += 1
            }
        }
    }
    flushAscii()
    return tokens
}

private const val FUNASR_PINNED_MAX_TOTAL_LEN = 512
private const val CHAT_TEMPLATE_ESTIMATE_TOKENS = 16
private const val HOTWORD_SCAFFOLD_ESTIMATE_TOKENS = 48
private const val RESERVED_GENERATION_TOKENS = 128
private const val SAFETY_MARGIN_TOKENS = 24
private const val CAPACITY_GUARD_TOKENS = 4
private const val ESTIMATED_AUDIO_TOKENS_PER_SECOND = 16.5
private const val TARGET_CHUNK_SECONDS = 18.0
private const val MIN_BUDGET_SECONDS = 2.0
private const val MIN_CHUNK_SECONDS = 3.0
private const val OVERLAP_SECONDS = 1.0
private const val BOUNDARY_SEARCH_SECONDS = 1.25
private const val ENERGY_WINDOW_SECONDS = 0.04
private const val ENERGY_STEP_SECONDS = 0.01
private const val RELATIVE_SILENCE_RATIO = 0.35
private const val ABSOLUTE_SILENCE_MEAN = 96.0
private const val MIN_EXACT_OVERLAP_CHARS = 2
private const val MIN_FUZZY_OVERLAP_CHARS = 4
private const val MAX_OVERLAP_CHARS = 48
private const val MAX_RETRY_DEPTH = 3
private const val MIN_RETRY_SECONDS = 2.5
private const val RETRY_BOUNDARY_SEARCH_SECONDS = 0.75
private const val RETRY_OVERLAP_SECONDS = 0.8
private const val AUDIO_TRUNCATION_TOLERANCE_SECONDS = 0.75
private const val SHORT_OUTPUT_MIN_SECONDS = 8.0
private const val SHORT_OUTPUT_MAX_CHARS = 2
