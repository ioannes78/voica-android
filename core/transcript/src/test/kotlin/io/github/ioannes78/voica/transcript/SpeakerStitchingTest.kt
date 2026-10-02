package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStitchingTest {
    @Test
    fun matchesPermutedChunkLocalLabelsByEmbedding() {
        val result =
            stitchDiarizationChunks(
                chunks =
                    listOf(
                        chunk(
                            index = 0,
                            turns =
                                listOf(
                                    turn(0, 0L, 640_000L),
                                    turn(1, 640_000L, 960_000L),
                                ),
                            anchors =
                                listOf(
                                    anchor(0, floatArrayOf(1F, 0F)),
                                    anchor(1, floatArrayOf(0F, 1F)),
                                ),
                        ),
                        chunk(
                            index = 1,
                            turns =
                                listOf(
                                    turn(0, 800_000L, 1_120_000L),
                                    turn(1, 1_120_000L, 1_600_000L),
                                ),
                            anchors =
                                listOf(
                                    anchor(0, floatArrayOf(0F, 1F)),
                                    anchor(1, floatArrayOf(1F, 0F)),
                                ),
                        ),
                    ),
                config = DiarizationConfig(stitchingCosineThreshold = 0.8F),
            )

        assertEquals(2, result.speakerCount)
        assertEquals(1, result.mapping(1, 0))
        assertEquals(0, result.mapping(1, 1))
    }

    @Test
    fun overlapCanRecoverIdentityWhenShortTurnHasNoUsableAnchor() {
        val result =
            stitchDiarizationChunks(
                chunks =
                    listOf(
                        chunk(
                            index = 0,
                            turns = listOf(turn(0, 0L, 960_000L)),
                            anchors = listOf(anchor(0, floatArrayOf(1F, 0F))),
                        ),
                        chunk(
                            index = 1,
                            turns = listOf(turn(7, 800_000L, 1_000_000L)),
                            anchors = emptyList(),
                        ),
                    ),
                config = DiarizationConfig(),
            )

        assertEquals(1, result.speakerCount)
        assertEquals(0, result.mapping(1, 7))
    }

    @Test
    fun lowCosineCreatesNewSpeakerEvenWhenSpeechOverlapsInTime() {
        val result =
            stitchDiarizationChunks(
                chunks =
                    listOf(
                        chunk(
                            index = 0,
                            turns = listOf(turn(0, 0L, 960_000L)),
                            anchors = listOf(anchor(0, floatArrayOf(1F, 0F))),
                        ),
                        chunk(
                            index = 1,
                            turns = listOf(turn(0, 800_000L, 1_100_000L, overlap = true)),
                            anchors = listOf(anchor(0, floatArrayOf(0F, 1F))),
                        ),
                    ),
                config = DiarizationConfig(stitchingCosineThreshold = 0.8F),
            )

        assertEquals(2, result.speakerCount)
        assertEquals(1, result.mapping(1, 0))
    }

    @Test
    fun twoLocalSpeakersCannotCollapseIntoSameGlobalSpeakerInOneChunk() {
        val result =
            stitchDiarizationChunks(
                chunks =
                    listOf(
                        chunk(
                            index = 0,
                            turns = listOf(turn(0, 0L, 320_000L)),
                            anchors = listOf(anchor(0, floatArrayOf(1F, 0F))),
                        ),
                        chunk(
                            index = 1,
                            turns =
                                listOf(
                                    turn(0, 320_000L, 480_000L),
                                    turn(1, 480_000L, 640_000L),
                                ),
                            anchors =
                                listOf(
                                    anchor(0, floatArrayOf(0.99F, 0.01F)),
                                    anchor(1, floatArrayOf(0.98F, 0.02F)),
                                ),
                        ),
                    ),
                config = DiarizationConfig(stitchingCosineThreshold = 0.8F),
            )

        val mapped = result.mappings.filter { it.chunkIndex == 1 }.map { it.globalSpeakerIndex }
        assertEquals(2, mapped.distinct().size)
    }

    @Test
    fun duplicateSameSpeakerTurnsFromChunkOverlapAreMerged() {
        val result =
            stitchDiarizationChunks(
                chunks =
                    listOf(
                        chunk(
                            index = 0,
                            turns = listOf(turn(0, 0L, 960_000L)),
                            anchors = listOf(anchor(0, floatArrayOf(1F, 0F))),
                        ),
                        chunk(
                            index = 1,
                            turns = listOf(turn(4, 800_000L, 1_200_000L)),
                            anchors = listOf(anchor(4, floatArrayOf(1F, 0F))),
                        ),
                    ),
                config = DiarizationConfig(),
            )

        assertEquals(1, result.turns.size)
        assertEquals(0L, result.turns.single().startSampleIndex)
        assertEquals(1_200_000L, result.turns.single().endSampleIndexExclusive)
    }

    private fun SpeakerStitchingResult.mapping(
        chunkIndex: Int,
        localSpeakerIndex: Int,
    ): Int =
        mappings.single {
            it.chunkIndex == chunkIndex && it.localSpeakerIndex == localSpeakerIndex
        }.globalSpeakerIndex

    private companion object {
        fun chunk(
            index: Int,
            turns: List<DiarizationSpeakerTurn>,
            anchors: List<SpeakerAnchorEmbedding>,
        ) =
            DiarizationChunkStitchInput(
                chunkIndex = index,
                turns = turns,
                anchors = anchors,
            )

        fun turn(
            speaker: Int,
            start: Long,
            end: Long,
            overlap: Boolean = false,
        ) =
            DiarizationSpeakerTurn(
                speakerIndex = speaker,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                confidence = 0.8F,
                overlap = overlap,
            )

        fun anchor(
            speaker: Int,
            embedding: FloatArray,
        ) =
            SpeakerAnchorEmbedding(
                localSpeakerIndex = speaker,
                embedding = embedding,
                anchorSampleCount = 32_000L,
            )
    }
}
