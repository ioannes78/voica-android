package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationContractsTest {
    @Test
    fun defaultConfigUsesBoundedStage9Window() {
        val config = DiarizationConfig()

        assertEquals(16_000, config.sampleRateHz)
        assertEquals(960_000L, config.chunkSizeSamples)
        assertEquals(160_000L, config.chunkOverlapSamples)
        assertEquals(8_000L, config.vadContextPaddingSamples)
        assertNull(config.expectedSpeakerCount)
    }

    @Test
    fun windowPreservesAbsoluteSampleTimeline() {
        val window =
            DiarizationWindow(
                startSampleIndex = 32_000L,
                samples = ShortArray(16_000),
            )

        assertEquals(48_000L, window.endSampleIndexExclusive)
    }

    @Test
    fun resultAllowsOverlappingTurnsWhileKeepingStartOrder() {
        val result =
            DiarizationChunkResult(
                speakerCount = 2,
                turns =
                    listOf(
                        DiarizationSpeakerTurn(
                            speakerIndex = 0,
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 32_000L,
                        ),
                        DiarizationSpeakerTurn(
                            speakerIndex = 1,
                            startSampleIndex = 24_000L,
                            endSampleIndexExclusive = 48_000L,
                            overlap = true,
                        ),
                    ),
            )

        assertEquals(2, result.speakerCount)
        assertEquals(2, result.turns.size)
        assertEquals(24_000L, result.turns[1].startSampleIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChunkOverlapThatConsumesWholeChunk() {
        DiarizationConfig(
            chunkSizeSamples = 160_000L,
            chunkOverlapSamples = 160_000L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSpeakerIndexOutsideDeclaredCount() {
        DiarizationChunkResult(
            speakerCount = 1,
            turns =
                listOf(
                    DiarizationSpeakerTurn(
                        speakerIndex = 1,
                        startSampleIndex = 0L,
                        endSampleIndexExclusive = 16_000L,
                    ),
                ),
        )
    }
}
