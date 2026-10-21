package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.audio.timeUsToSampleIndex
import kotlin.math.max

/**
 * Compact score surface for CTC forced alignment.
 *
 * Runtime code is expected to gather only the blank score and the score for each target-token
 * position from the full CTC vocabulary tensor before crossing the JNI/Kotlin boundary. This keeps
 * the alignment core independent of vocabulary size and avoids retaining a T x vocab float matrix
 * in managed memory.
 *
 * [targetLogProbabilities] is frame-major with shape [frameCount, targetTokenIds.size]. Repeated
 * target ids deliberately retain separate positional columns so the Viterbi path can enforce the
 * CTC blank transition required between identical adjacent targets.
 */
internal data class CtcAlignmentScores(
    val frameCount: Int,
    val targetTokenIds: IntArray,
    val blankLogProbabilities: FloatArray,
    val targetLogProbabilities: FloatArray,
) {
    init {
        require(frameCount > 0)
        require(blankLogProbabilities.size == frameCount)
        require(targetLogProbabilities.size == frameCount * targetTokenIds.size)
    }

    val targetCount: Int
        get() = targetTokenIds.size

    fun blank(frame: Int): Float {
        require(frame in 0 until frameCount)
        return blankLogProbabilities[frame]
    }

    fun target(
        frame: Int,
        targetIndex: Int,
    ): Float {
        require(frame in 0 until frameCount)
        require(targetIndex in 0 until targetCount)
        return targetLogProbabilities[frame * targetCount + targetIndex]
    }
}

internal data class CtcAlignedTokenFrame(
    val targetIndex: Int,
    val tokenId: Int,
    val startFrame: Int,
    val endFrameExclusive: Int,
    val peakLogProbability: Float,
) {
    init {
        require(targetIndex >= 0)
        require(startFrame >= 0)
        require(endFrameExclusive > startFrame)
    }
}

internal data class CtcAlignedTokenSample(
    val targetIndex: Int,
    val tokenId: Int,
    val startSampleOffset: Long,
    val endSampleOffsetExclusive: Long,
    val peakLogProbability: Float,
) {
    init {
        require(targetIndex >= 0)
        require(startSampleOffset >= 0L)
        require(endSampleOffsetExclusive > startSampleOffset)
    }
}

/**
 * Viterbi forced alignment for a fixed CTC target sequence.
 *
 * This is the Android-portable equivalent of the dynamic-programming core used by CTC forced
 * alignment: states are [blank, y0, blank, y1, ..., blank]. A target state can advance by two
 * states only when the current token differs from the previous target token; identical adjacent
 * targets therefore require an intervening blank frame.
 *
 * Returns null when the target is structurally impossible for the available frames or when no
 * finite path reaches the final target/final blank. Callers must fail closed to segment-level
 * timing in that case; interpolation is intentionally not provided.
 */
internal fun forceAlignCtc(scores: CtcAlignmentScores): List<CtcAlignedTokenFrame>? {
    val targetCount = scores.targetCount
    if (targetCount == 0) return emptyList()

    var minimumFrames = targetCount
    for (index in 1 until targetCount) {
        if (scores.targetTokenIds[index] == scores.targetTokenIds[index - 1]) {
            minimumFrames += 1
        }
    }
    if (scores.frameCount < minimumFrames) return null

    val stateCount = targetCount * 2 + 1
    var previous = FloatArray(stateCount) { Float.NEGATIVE_INFINITY }
    var current = FloatArray(stateCount) { Float.NEGATIVE_INFINITY }
    val backPointers = ByteArray(scores.frameCount * stateCount) { BACK_UNREACHABLE }

    previous[0] = scores.blank(0)
    previous[1] = scores.target(0, 0)

    for (frame in 1 until scores.frameCount) {
        current.fill(Float.NEGATIVE_INFINITY)
        for (state in 0 until stateCount) {
            var bestScore = previous[state]
            var bestBack = BACK_STAY

            if (state > 0 && previous[state - 1] > bestScore) {
                bestScore = previous[state - 1]
                bestBack = BACK_ADVANCE_ONE
            }

            if (state > 1 && state % 2 == 1) {
                val targetIndex = (state - 1) / 2
                val maySkipBlank =
                    targetIndex == 0 ||
                        scores.targetTokenIds[targetIndex] != scores.targetTokenIds[targetIndex - 1]
                if (maySkipBlank && previous[state - 2] > bestScore) {
                    bestScore = previous[state - 2]
                    bestBack = BACK_ADVANCE_TWO
                }
            }

            if (bestScore == Float.NEGATIVE_INFINITY) continue

            val emission =
                if (state % 2 == 0) {
                    scores.blank(frame)
                } else {
                    scores.target(frame, (state - 1) / 2)
                }
            val score = bestScore + emission
            if (score.isNaN()) continue

            current[state] = score
            backPointers[frame * stateCount + state] = bestBack
        }

        val swap = previous
        previous = current
        current = swap
    }

    val lastTargetState = stateCount - 2
    val finalBlankState = stateCount - 1
    var state =
        if (previous[finalBlankState] > previous[lastTargetState]) {
            finalBlankState
        } else {
            lastTargetState
        }
    if (previous[state] == Float.NEGATIVE_INFINITY || previous[state].isNaN()) return null

    val path = IntArray(scores.frameCount)
    for (frame in scores.frameCount - 1 downTo 0) {
        path[frame] = state
        if (frame == 0) break
        state =
            when (backPointers[frame * stateCount + state]) {
                BACK_STAY -> state
                BACK_ADVANCE_ONE -> state - 1
                BACK_ADVANCE_TWO -> state - 2
                else -> return null
            }
        if (state < 0) return null
    }
    if (path[0] != 0 && path[0] != 1) return null

    val starts = IntArray(targetCount) { Int.MAX_VALUE }
    val ends = IntArray(targetCount) { -1 }
    val peaks = FloatArray(targetCount) { Float.NEGATIVE_INFINITY }
    for (frame in path.indices) {
        val pathState = path[frame]
        if (pathState % 2 == 0) continue
        val targetIndex = (pathState - 1) / 2
        starts[targetIndex] = minOf(starts[targetIndex], frame)
        ends[targetIndex] = max(ends[targetIndex], frame + 1)
        peaks[targetIndex] = max(peaks[targetIndex], scores.target(frame, targetIndex))
    }

    return List(targetCount) { targetIndex ->
        val start = starts[targetIndex]
        val end = ends[targetIndex]
        if (start == Int.MAX_VALUE || end <= start) return null
        CtcAlignedTokenFrame(
            targetIndex = targetIndex,
            tokenId = scores.targetTokenIds[targetIndex],
            startFrame = start,
            endFrameExclusive = end,
            peakLogProbability = peaks[targetIndex],
        )
    }
}

/** Maps official FunASR CTC frame spans to PCM sample offsets. */
internal fun mapCtcFramesToSampleOffsets(
    alignment: List<CtcAlignedTokenFrame>,
    sampleRateHz: Int,
    framePeriodUs: Long = FUNASR_CTC_FRAME_PERIOD_US,
): List<CtcAlignedTokenSample> {
    require(sampleRateHz > 0)
    require(framePeriodUs > 0L)
    return alignment.map { token ->
        val startUs = Math.multiplyExact(token.startFrame.toLong(), framePeriodUs)
        val endUs = Math.multiplyExact(token.endFrameExclusive.toLong(), framePeriodUs)
        CtcAlignedTokenSample(
            targetIndex = token.targetIndex,
            tokenId = token.tokenId,
            startSampleOffset = timeUsToSampleIndex(startUs, sampleRateHz),
            endSampleOffsetExclusive = timeUsToSampleIndex(endUs, sampleRateHz),
            peakLogProbability = token.peakLogProbability,
        )
    }
}

internal const val FUNASR_CTC_FRAME_PERIOD_US = 60_000L

private const val BACK_STAY: Byte = 0
private const val BACK_ADVANCE_ONE: Byte = 1
private const val BACK_ADVANCE_TWO: Byte = 2
private const val BACK_UNREACHABLE: Byte = 127
