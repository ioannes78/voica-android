package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSourceResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

internal data class TimelineReferenceSegment(
    val segmentIndex: Int,
    val text: String,
    val absoluteTokens: List<TranscriptToken>,
)

internal class TimelineReferenceRunner(
    private val pcmSourceResolver: PcmSourceResolver,
    private val asrEngineFactory: StreamingAsrEngineFactory,
    private val readChunkSamples: Int,
) {
    init {
        require(readChunkSamples > 0)
    }

    suspend fun run(
        recordingId: String,
        expectedTotalSampleCount: Long,
        speechSegments: List<SpeechSegment>,
        progressListener: ProgressListener? = null,
    ): List<TimelineReferenceSegment> {
        if (speechSegments.isEmpty()) return emptyList()

        val totalUnits = speechSegments.size.toLong()
        progressListener?.onProgress(
            TranscriptionProgress(
                phase = TranscriptionPhase.SECOND_PASS,
                processedUnits = 0L,
                totalUnits = totalUnits,
                activity = TranscriptionProgressActivity.TIMELINE_ALIGNMENT,
            ),
        )

        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before timeline alignment")
        require(source.totalSampleCount == expectedTotalSampleCount) {
            "canonical PCM sample count changed before timeline alignment"
        }

        val engine =
            try {
                asrEngineFactory.open()
            } catch (error: Throwable) {
                source.close()
                throw error
            }
        require(engine.capabilities.supportsStreaming) {
            "timeline alignment requires a streaming ASR engine"
        }
        require(engine.capabilities.supportsTokenTiming) {
            "timeline alignment model does not expose token timestamps"
        }

        try {
            val output = ArrayList<TimelineReferenceSegment>(speechSegments.size)
            val buffer = ShortArray(readChunkSamples)
            var sourceCursor = 0L
            var segmentIndex = 0
            var session: StreamingAsrSession? = null

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer) ?: break
                require(read.startSampleIndex == sourceCursor)
                val readEnd = Math.addExact(read.startSampleIndex, read.sampleCount.toLong())

                while (
                    segmentIndex < speechSegments.size &&
                    speechSegments[segmentIndex].startSampleIndex < readEnd
                ) {
                    currentCoroutineContext().ensureActive()
                    val segment = speechSegments[segmentIndex]
                    val overlapStart = max(segment.startSampleIndex, read.startSampleIndex)
                    val overlapEnd = min(segment.endSampleIndexExclusive, readEnd)

                    if (overlapEnd > overlapStart) {
                        if (session == null) session = engine.openSession()
                        val sourceOffset = Math.toIntExact(overlapStart - read.startSampleIndex)
                        val count = Math.toIntExact(overlapEnd - overlapStart)
                        session.acceptSamples(buffer, sourceOffset, count)
                        session.decode()
                    }

                    if (segment.endSampleIndexExclusive <= readEnd) {
                        val completed =
                            session ?: error("timeline segment completed without PCM")
                        val final =
                            try {
                                completed.finishInput()
                            } finally {
                                completed.close()
                            }
                        session = null
                        require(final.isFinal)
                        output +=
                            TimelineReferenceSegment(
                                segmentIndex = segmentIndex,
                                text = final.text,
                                absoluteTokens =
                                    safeMapRelativeTokens(
                                        tokens = final.tokens,
                                        segment = segment,
                                        source = TokenSource.FIRST_PASS,
                                    ),
                            )
                        segmentIndex += 1
                        progressListener?.onProgress(
                            TranscriptionProgress(
                                phase = TranscriptionPhase.SECOND_PASS,
                                processedUnits = segmentIndex.toLong(),
                                totalUnits = totalUnits,
                                activity = TranscriptionProgressActivity.TIMELINE_ALIGNMENT,
                            ),
                        )
                    } else {
                        break
                    }
                }
                sourceCursor = readEnd
            }

            session?.close()
            require(sourceCursor == expectedTotalSampleCount)
            require(segmentIndex == speechSegments.size)
            return output
        } finally {
            engine.close()
            source.close()
        }
    }
}

internal enum class TimelineAlignmentQuality {
    EXACT_OR_HIGH,
    INTERPOLATED,
    FAILED,
}

internal data class TimelineAlignmentResult(
    val tokens: List<TranscriptToken>,
    val matchedRatio: Float,
    val quality: TimelineAlignmentQuality,
)

internal fun alignFinalTextToReferenceTiming(
    finalText: String,
    referenceText: String,
    referenceTokens: List<TranscriptToken>,
    segment: SpeechSegment,
): TimelineAlignmentResult {
    if (finalText.isBlank() || referenceTokens.isEmpty()) {
        return TimelineAlignmentResult(emptyList(), 0F, TimelineAlignmentQuality.FAILED)
    }

    val target = alignmentUnits(finalText)
    val reference = referenceTimingUnits(referenceText, referenceTokens, segment)
    if (target.isEmpty() || reference.isEmpty()) {
        return TimelineAlignmentResult(emptyList(), 0F, TimelineAlignmentQuality.FAILED)
    }

    val matches = exactSequenceMatches(target.map { it.normalized }, reference.map { it.normalized })
    val timedMatches =
        matches.mapNotNull { (targetIndex, referenceIndex) ->
            reference[referenceIndex].startSampleIndex?.let { start ->
                targetIndex to start
            }
        }.toMap()

    val ratio = timedMatches.size.toFloat() / target.size.toFloat()
    if (ratio < MIN_ALIGNMENT_RATIO || timedMatches.isEmpty()) {
        return TimelineAlignmentResult(emptyList(), ratio, TimelineAlignmentQuality.FAILED)
    }

    val starts = LongArray(target.size) { UNSET }
    timedMatches.forEach { (index, start) ->
        starts[index] =
            start.coerceIn(
                segment.startSampleIndex,
                segment.endSampleIndexExclusive - 1L,
            )
    }
    interpolateMissingStarts(starts, segment)

    val tokens =
        target.mapIndexed { index, unit ->
            TranscriptToken(
                text = unit.original.toString(),
                startSampleIndex = starts[index],
                endSampleIndexExclusive = null,
                source = TokenSource.SECOND_PASS,
            )
        }

    return TimelineAlignmentResult(
        tokens = tokens,
        matchedRatio = ratio,
        quality =
            if (ratio >= HIGH_ALIGNMENT_RATIO) {
                TimelineAlignmentQuality.EXACT_OR_HIGH
            } else {
                TimelineAlignmentQuality.INTERPOLATED
            },
    )
}

private data class AlignmentUnit(
    val original: Char,
    val normalized: Char,
)

private data class ReferenceTimingUnit(
    val normalized: Char,
    val startSampleIndex: Long?,
)

private fun alignmentUnits(text: String): List<AlignmentUnit> =
    buildList {
        text.forEach { char ->
            normalizeAlignmentChar(char)?.let { normalized ->
                add(AlignmentUnit(original = char, normalized = normalized))
            }
        }
    }

private fun referenceTimingUnits(
    referenceText: String,
    tokens: List<TranscriptToken>,
    segment: SpeechSegment,
): List<ReferenceTimingUnit> {
    if (tokens.isEmpty()) return emptyList()
    val output = ArrayList<ReferenceTimingUnit>()

    tokens.forEachIndexed { tokenIndex, token ->
        val chars =
            token.text.mapNotNull(::normalizeAlignmentChar)
        if (chars.isEmpty()) return@forEachIndexed

        val start = token.startSampleIndex
        val nextStart =
            tokens.drop(tokenIndex + 1)
                .firstNotNullOfOrNull { it.startSampleIndex }
                ?: segment.endSampleIndexExclusive
        chars.forEachIndexed { charIndex, char ->
            val interpolated =
                if (start == null) {
                    null
                } else if (chars.size == 1 || nextStart <= start) {
                    start
                } else {
                    start +
                        ((nextStart - start) * charIndex.toLong()) /
                        chars.size.toLong()
                }
            output +=
                ReferenceTimingUnit(
                    normalized = char,
                    startSampleIndex = interpolated,
                )
        }
    }

    if (output.isNotEmpty()) return output

    return referenceText.mapNotNull(::normalizeAlignmentChar)
        .map { ReferenceTimingUnit(it, null) }
}

private fun normalizeAlignmentChar(char: Char): Char? {
    if (char.isWhitespace()) return null
    if (char == '▁' || char == 'Ġ') return null
    val type = Character.getType(char)
    if (
        type == Character.CONNECTOR_PUNCTUATION.toInt() ||
        type == Character.DASH_PUNCTUATION.toInt() ||
        type == Character.START_PUNCTUATION.toInt() ||
        type == Character.END_PUNCTUATION.toInt() ||
        type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.OTHER_PUNCTUATION.toInt()
    ) {
        return null
    }
    return char.lowercaseChar()
}

private fun exactSequenceMatches(
    target: List<Char>,
    reference: List<Char>,
): List<Pair<Int, Int>> {
    val n = target.size
    val m = reference.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 0..n) dp[i][0] = i
    for (j in 0..m) dp[0][j] = j

    for (i in 1..n) {
        for (j in 1..m) {
            val substitution = dp[i - 1][j - 1] + if (target[i - 1] == reference[j - 1]) 0 else 1
            dp[i][j] =
                minOf(
                    substitution,
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                )
        }
    }

    var i = n
    var j = m
    val reversed = ArrayList<Pair<Int, Int>>()
    while (i > 0 || j > 0) {
        if (
            i > 0 && j > 0 &&
            target[i - 1] == reference[j - 1] &&
            dp[i][j] == dp[i - 1][j - 1]
        ) {
            reversed += (i - 1) to (j - 1)
            i -= 1
            j -= 1
        } else if (
            i > 0 && j > 0 &&
            dp[i][j] == dp[i - 1][j - 1] + 1
        ) {
            i -= 1
            j -= 1
        } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
            i -= 1
        } else {
            j -= 1
        }
    }
    reversed.reverse()
    return reversed
}

private fun interpolateMissingStarts(
    starts: LongArray,
    segment: SpeechSegment,
) {
    val anchored = starts.indices.filter { starts[it] != UNSET }
    require(anchored.isNotEmpty())

    val first = anchored.first()
    val firstTime = starts[first]
    for (index in 0 until first) {
        starts[index] =
            segment.startSampleIndex +
                ((firstTime - segment.startSampleIndex) * (index + 1L)) /
                (first + 1L)
    }

    for (anchorIndex in 0 until anchored.lastIndex) {
        val left = anchored[anchorIndex]
        val right = anchored[anchorIndex + 1]
        val leftTime = starts[left]
        val rightTime = starts[right].coerceAtLeast(leftTime)
        val gap = right - left
        for (index in left + 1 until right) {
            starts[index] =
                leftTime +
                    ((rightTime - leftTime) * (index - left).toLong()) /
                    gap.toLong()
        }
    }

    val last = anchored.last()
    val lastTime = starts[last]
    val end = segment.endSampleIndexExclusive - 1L
    val tail = starts.lastIndex - last
    for (offset in 1..tail) {
        starts[last + offset] =
            lastTime +
                ((end - lastTime).coerceAtLeast(0L) * offset.toLong()) /
                (tail + 1L)
    }

    var previous = segment.startSampleIndex
    for (index in starts.indices) {
        starts[index] =
            starts[index]
                .coerceIn(segment.startSampleIndex, end)
                .coerceAtLeast(previous)
        previous = starts[index]
    }
}

private const val MIN_ALIGNMENT_RATIO = 0.60F
private const val HIGH_ALIGNMENT_RATIO = 0.85F
private const val UNSET = Long.MIN_VALUE
