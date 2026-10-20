package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateBBenchmarkContractTest {
    @Test
    fun formalGateBUsesFsmnAndCandidateFirstAsrOrder() {
        assertEquals(STAGE16B_FSMN_VAD_CANDIDATE_ID, Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
        assertEquals(
            listOf(
                STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                Stage8ModelIds.SECOND_PASS_ASR,
                Stage13AOfflineModelIds.QWEN3_ASR,
            ),
            Stage16BGateBBenchmarkContract.MODEL_IDS,
        )
        assertFalse(Stage8ModelIds.VAD in Stage16BGateBBenchmarkContract.MODEL_IDS)
    }

    @Test
    fun productionOfflineChoicesRemainSenseVoiceOrQwenOnly() {
        val productModelIds =
            OfflineAsrQualityChoice.values()
                .flatMap(OfflineAsrQualityChoice::preferredModelIds)
                .toSet()

        assertEquals(
            setOf(Stage13AOfflineModelIds.SENSEVOICE, Stage13AOfflineModelIds.QWEN3_ASR),
            productModelIds,
        )
        assertFalse(STAGE16B_FUNASR_NANO_CANDIDATE_ID in productModelIds)
    }

    @Test
    fun frozenInputsPreserveFsmnBoundariesAndPcmAcrossDeterministicSplits() {
        val pcm = ShortArray(20) { it.toShort() }
        val speech =
            listOf(
                SpeechSegment(2, 6),
                SpeechSegment(8, 15),
            )

        val inputs = buildStage16BGateBFrozenInputs(pcm, speech, maxChunkSamples = 3)

        assertEquals(
            listOf(2L to 5L, 5L to 6L, 8L to 11L, 11L to 14L, 14L to 15L),
            inputs.map { it.startSampleIndex to it.endSampleIndexExclusive },
        )
        assertArrayEquals(shortArrayOf(2, 3, 4), inputs[0].samples)
        assertArrayEquals(shortArrayOf(5), inputs[1].samples)
        assertArrayEquals(shortArrayOf(8, 9, 10), inputs[2].samples)
        assertArrayEquals(shortArrayOf(11, 12, 13), inputs[3].samples)
        assertArrayEquals(shortArrayOf(14), inputs[4].samples)
        assertEquals(
            speech.sumOf(SpeechSegment::sampleCount),
            inputs.sumOf { it.samples.size.toLong() },
        )
    }

    @Test
    fun frozenInputRejectsOverlappingVadSegments() {
        val failure =
            runCatching {
                buildStage16BGateBFrozenInputs(
                    pcm = ShortArray(20),
                    speechSegments = listOf(SpeechSegment(2, 8), SpeechSegment(7, 10)),
                    maxChunkSamples = 10,
                )
            }

        assertTrue(failure.isFailure)
    }
}
