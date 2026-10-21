package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FunAsrCtcStreamProtocolTest {
    @Test
    fun validatesAlignmentArgumentsIncludingModelOwnedBlankAndVocabulary() {
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(),
                blankId = 99,
                vocabSize = 100,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(10, 99),
                blankId = 99,
                vocabSize = 100,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(10),
                blankId = -1,
                vocabSize = 100,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(10),
                blankId = 100,
                vocabSize = 100,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(100),
                blankId = 99,
                vocabSize = 100,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunAsrCtcStreamProtocol.validateAlignmentArguments(
                targetIds = intArrayOf(10),
                blankId = 0,
                vocabSize = 1,
            )
        }

        FunAsrCtcStreamProtocol.validateAlignmentArguments(
            targetIds = intArrayOf(10, 20),
            blankId = 99,
            vocabSize = 100,
        )
    }

    @Test
    fun parsesExistingResultFieldsAsTrustedCtcSpans() {
        val expected = intArrayOf(10, 20)
        val result =
            result(
                tokens = arrayOf("10", "20"),
                timestamps = floatArrayOf(0.06F, 0.18F),
                durations = floatArrayOf(0.12F, 0.06F),
            )

        val aligned =
            requireNotNull(
                FunAsrCtcStreamProtocol.parseAlignmentResult(
                    result = result,
                    expectedTargetIds = expected,
                    acceptedSampleCount = 8_000L,
                ),
            )

        assertEquals(
            listOf(
                CtcAlignedTokenSample(0, 10, 960L, 2_880L, Float.NaN),
                CtcAlignedTokenSample(1, 20, 2_880L, 3_840L, Float.NaN),
            ).map { it.copy(peakLogProbability = 0F) },
            aligned.map { it.copy(peakLogProbability = 0F) },
        )
    }

    @Test
    fun refusesTargetIdentityMismatch() {
        assertNull(
            FunAsrCtcStreamProtocol.parseAlignmentResult(
                result =
                    result(
                        tokens = arrayOf("10", "21"),
                        timestamps = floatArrayOf(0.06F, 0.18F),
                        durations = floatArrayOf(0.06F, 0.06F),
                    ),
                expectedTargetIds = intArrayOf(10, 20),
                acceptedSampleCount = 8_000L,
            ),
        )
    }

    @Test
    fun refusesMissingDurationOrZeroWidthSpan() {
        assertNull(
            FunAsrCtcStreamProtocol.parseAlignmentResult(
                result =
                    result(
                        tokens = arrayOf("10"),
                        timestamps = floatArrayOf(0.06F),
                        durations = floatArrayOf(),
                    ),
                expectedTargetIds = intArrayOf(10),
                acceptedSampleCount = 8_000L,
            ),
        )
        assertNull(
            FunAsrCtcStreamProtocol.parseAlignmentResult(
                result =
                    result(
                        tokens = arrayOf("10"),
                        timestamps = floatArrayOf(0.06F),
                        durations = floatArrayOf(0F),
                    ),
                expectedTargetIds = intArrayOf(10),
                acceptedSampleCount = 8_000L,
            ),
        )
    }

    @Test
    fun refusesSpanOutsideAcceptedChunk() {
        assertNull(
            FunAsrCtcStreamProtocol.parseAlignmentResult(
                result =
                    result(
                        tokens = arrayOf("10"),
                        timestamps = floatArrayOf(0.48F),
                        durations = floatArrayOf(0.12F),
                    ),
                expectedTargetIds = intArrayOf(10),
                acceptedSampleCount = 8_000L,
            ),
        )
    }

    private fun result(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
    ) =
        OfflineRecognizerResult(
            text = "",
            tokens = tokens,
            timestamps = timestamps,
            lang = "",
            emotion = "",
            event = "",
            durations = durations,
        )
}
