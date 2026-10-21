package io.github.ioannes78.voica.sherpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaFunAsrCtcReflectionBridgeTest {
    @Test
    fun stockLikeRecognizerWithoutCtcAbiIsUnavailable() {
        val bridge = SherpaFunAsrCtcReflectionBridge(StockLikeRecognizer())

        assertFalse(bridge.isAvailable)
        assertThrows(IllegalStateException::class.java) { bridge.requireAvailable() }
    }

    @Test
    fun requiresScoreAndDiscardAsOneAtomicAbi() {
        val bridge = SherpaFunAsrCtcReflectionBridge(ScoreOnlyRecognizer())

        assertFalse(bridge.isAvailable)
    }

    @Test
    fun mapsRowMajorBlankPlusTargetScoresIntoAlignmentContract() {
        val recognizer = PatchedRecognizer()
        val bridge = SherpaFunAsrCtcReflectionBridge(recognizer)
        val stream = FakeStream()
        val targets = intArrayOf(7, 9)

        val scores = bridge.score(stream, targets)

        assertTrue(bridge.isAvailable)
        assertEquals(2, scores.frameCount)
        assertArrayEquals(targets, scores.targetTokenIds)
        assertArrayEquals(floatArrayOf(1f, 4f), scores.blankLogProbabilities, 0f)
        assertArrayEquals(floatArrayOf(2f, 3f, 5f, 6f), scores.targetLogProbabilities, 0f)
        assertArrayEquals(targets, recognizer.lastTargets)
        assertTrue(recognizer.lastStream === stream)
    }

    @Test
    fun preservesDuplicateTargetPositions() {
        val recognizer =
            PatchedRecognizer(
                response = floatArrayOf(
                    0f, 10f, 10f,
                    0f, 20f, 20f,
                ),
            )
        val bridge = SherpaFunAsrCtcReflectionBridge(recognizer)
        val targets = intArrayOf(5, 5)

        val scores = bridge.score(FakeStream(), targets)

        assertArrayEquals(intArrayOf(5, 5), scores.targetTokenIds)
        assertArrayEquals(floatArrayOf(10f, 10f, 20f, 20f), scores.targetLogProbabilities, 0f)
    }

    @Test
    fun rejectsMalformedNativeMatrix() {
        val bridge =
            SherpaFunAsrCtcReflectionBridge(
                PatchedRecognizer(response = floatArrayOf(1f, 2f, 3f, 4f)),
            )

        assertThrows(IllegalArgumentException::class.java) {
            bridge.score(FakeStream(), intArrayOf(1, 2))
        }
    }

    @Test
    fun discardUsesSameStreamAndCanRunAfterScoreFailurePath() {
        val recognizer = PatchedRecognizer()
        val bridge = SherpaFunAsrCtcReflectionBridge(recognizer)
        val stream = FakeStream()

        bridge.discard(stream)

        assertEquals(1, recognizer.discardCount)
        assertTrue(recognizer.lastDiscardedStream === stream)
    }

    private class FakeStream

    private class StockLikeRecognizer {
        fun decode(stream: FakeStream) = Unit
    }

    private class ScoreOnlyRecognizer {
        @Suppress("UNUSED_PARAMETER")
        fun getFunAsrNanoCtcScores(
            stream: FakeStream,
            targetIds: IntArray,
        ): FloatArray = floatArrayOf(0f, 0f)
    }

    private class PatchedRecognizer(
        private val response: FloatArray = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
    ) {
        var lastStream: FakeStream? = null
        var lastTargets = intArrayOf()
        var discardCount = 0
        var lastDiscardedStream: FakeStream? = null

        fun getFunAsrNanoCtcScores(
            stream: FakeStream,
            targetIds: IntArray,
        ): FloatArray {
            lastStream = stream
            lastTargets = targetIds.copyOf()
            return response.copyOf()
        }

        fun discardFunAsrNanoCtcState(stream: FakeStream) {
            discardCount += 1
            lastDiscardedStream = stream
        }
    }
}
