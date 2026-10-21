package io.github.ioannes78.voica.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrCtcForcedAlignerTest {
    @Test
    fun alignsTwoTargetsThroughPreferredBlankFrames() {
        val scores =
            scores(
                blank = floatArrayOf(-0.1f, -6f, -0.1f, -6f, -0.1f),
                targetIds = intArrayOf(10, 20),
                targets =
                    arrayOf(
                        floatArrayOf(-8f, -8f),
                        floatArrayOf(-0.1f, -8f),
                        floatArrayOf(-6f, -8f),
                        floatArrayOf(-8f, -0.1f),
                        floatArrayOf(-8f, -6f),
                    ),
            )

        val aligned = assertNotNullValue(forceAlignCtc(scores))

        assertEquals(2, aligned.size)
        assertEquals(CtcAlignedTokenFrame(0, 10, 1, 2, -0.1f), aligned[0])
        assertEquals(CtcAlignedTokenFrame(1, 20, 3, 4, -0.1f), aligned[1])
    }

    @Test
    fun repeatedTargetsRequireInterveningBlank() {
        val scores =
            scores(
                blank = floatArrayOf(-5f, -0.1f, -5f),
                targetIds = intArrayOf(7, 7),
                targets =
                    arrayOf(
                        floatArrayOf(-0.1f, -0.1f),
                        floatArrayOf(-8f, -8f),
                        floatArrayOf(-0.1f, -0.1f),
                    ),
            )

        val aligned = assertNotNullValue(forceAlignCtc(scores))

        assertEquals(0, aligned[0].startFrame)
        assertEquals(1, aligned[0].endFrameExclusive)
        assertEquals(2, aligned[1].startFrame)
        assertEquals(3, aligned[1].endFrameExclusive)
    }

    @Test
    fun refusesRepeatedTargetWhenThereAreTooFewFrames() {
        val scores =
            scores(
                blank = floatArrayOf(-1f, -1f),
                targetIds = intArrayOf(5, 5),
                targets =
                    arrayOf(
                        floatArrayOf(-0.1f, -0.1f),
                        floatArrayOf(-0.1f, -0.1f),
                    ),
            )

        assertNull(forceAlignCtc(scores))
    }

    @Test
    fun refusesWhenNoFinitePathCanReachTarget() {
        val scores =
            scores(
                blank = floatArrayOf(0f, Float.NEGATIVE_INFINITY),
                targetIds = intArrayOf(3),
                targets =
                    arrayOf(
                        floatArrayOf(Float.NEGATIVE_INFINITY),
                        floatArrayOf(Float.NEGATIVE_INFINITY),
                    ),
            )

        assertNull(forceAlignCtc(scores))
    }

    @Test
    fun mapsOfficialSixtyMillisecondFramesToCanonicalSamples() {
        val mapped =
            mapCtcFramesToSampleOffsets(
                alignment =
                    listOf(
                        CtcAlignedTokenFrame(
                            targetIndex = 0,
                            tokenId = 42,
                            startFrame = 1,
                            endFrameExclusive = 4,
                            peakLogProbability = -0.25f,
                        ),
                    ),
                sampleRateHz = 16_000,
            )

        assertEquals(1, mapped.size)
        assertEquals(960L, mapped.single().startSampleOffset)
        assertEquals(3_840L, mapped.single().endSampleOffsetExclusive)
        assertEquals(42, mapped.single().tokenId)
    }

    @Test
    fun emptyTargetNeedsNoAlignmentFrames() {
        val scores =
            CtcAlignmentScores(
                frameCount = 2,
                targetTokenIds = intArrayOf(),
                blankLogProbabilities = floatArrayOf(-0.1f, -0.1f),
                targetLogProbabilities = floatArrayOf(),
            )

        val aligned = forceAlignCtc(scores)

        assertNotNull(aligned)
        assertTrue(aligned!!.isEmpty())
    }

    private fun scores(
        blank: FloatArray,
        targetIds: IntArray,
        targets: Array<FloatArray>,
    ): CtcAlignmentScores {
        require(targets.size == blank.size)
        val flat = FloatArray(blank.size * targetIds.size)
        targets.forEachIndexed { frame, row ->
            require(row.size == targetIds.size)
            row.copyInto(flat, destinationOffset = frame * targetIds.size)
        }
        return CtcAlignmentScores(
            frameCount = blank.size,
            targetTokenIds = targetIds,
            blankLogProbabilities = blank,
            targetLogProbabilities = flat,
        )
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
