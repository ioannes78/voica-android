package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.transcript.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB2CompletenessTest {
    @Test
    fun `known SenseVoice metadata only chunk is blank but raw evidence is retained`() {
        val chunk =
            Stage16BGateB2ChunkObservation(
                index = 0,
                startSampleIndex = 1600,
                endSampleIndexExclusive = 3200,
                rawText = "<zh>\n<yue>",
                detectedLanguage = "zh",
            )

        assertTrue(chunk.blank)
        assertEquals("", chunk.contentText)
        assertEquals("<zh>\n<yue>", chunk.rawText)
    }

    @Test
    fun `spoken content remains nonblank when SenseVoice metadata is present`() {
        val chunk =
            Stage16BGateB2ChunkObservation(
                index = 1,
                startSampleIndex = 3200,
                endSampleIndexExclusive = 6400,
                rawText = "<zh>今天继续测试",
                detectedLanguage = "zh",
            )

        assertFalse(chunk.blank)
        assertEquals("今天继续测试", chunk.contentText)
    }

    @Test
    fun `blank chunk indexes preserve frozen input positions for manual comparison`() {
        val chunks =
            listOf(
                Stage16BGateB2ChunkObservation(0, 0, 1600, "", null),
                Stage16BGateB2ChunkObservation(1, 1600, 3200, "第一段", "zh"),
                Stage16BGateB2ChunkObservation(2, 3200, 4800, "<zh>", "zh"),
                Stage16BGateB2ChunkObservation(3, 4800, 6400, "第二段", "zh"),
            )
        val observation =
            Stage16BGateB2ModelObservation(
                modelId = "test-model",
                benchmark = null,
                startingPssKb = null,
                engineOpenTimeMillis = null,
                chunks = chunks,
                errorMessage = null,
            )

        assertEquals(listOf(0, 2), observation.blankChunkIndexes)
        assertEquals(2, observation.nonBlankChunkCount)
        assertEquals("第一段\n第二段", observation.rawText)
    }

    @Test
    fun `all blank model observation is not ready`() {
        val chunks =
            listOf(
                Stage16BGateB2ChunkObservation(0, 0, 1600, "", null),
                Stage16BGateB2ChunkObservation(1, 1600, 3200, "<zh>", "zh"),
            )
        val observation =
            Stage16BGateB2ModelObservation(
                modelId = "test-model",
                benchmark = io.github.ioannes78.voica.transcript.LocalFileAsrBenchmarkObservation(
                    recordingId = "test",
                    canonicalSha256 = null,
                    sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    totalSampleCount = 3200,
                    speechSampleCount = 3200,
                    vad = io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity("vad", "1", "1"),
                    asr = io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity("asr", "1", "1"),
                    wallTimeMillis = 1,
                    vadTimeMillis = 1,
                    asrTimeMillis = 0,
                    timingAlignmentTimeMillis = null,
                    punctuationTimeMillis = null,
                    segmentCount = 2,
                    tokenCount = 0,
                ),
                startingPssKb = null,
                engineOpenTimeMillis = 0,
                chunks = chunks,
                errorMessage = null,
            )

        assertFalse(observation.ready)
    }

    @Test
    fun `B2 30 second speech range is split at the 25 second common cap`() {
        val totalSamples = CanonicalPcmProfile.SAMPLE_RATE_HZ * 30
        val pcm = ShortArray(totalSamples)
        val result =
            buildStage16BGateBFrozenInputs(
                pcm = pcm,
                speechSegments = listOf(SpeechSegment(0, totalSamples.toLong())),
                maxChunkSamples =
                    CanonicalPcmProfile.SAMPLE_RATE_HZ * STAGE16B_GATE_B_B2_MAX_ASR_INPUT_SECONDS,
            )

        assertEquals(25, STAGE16B_GATE_B_B2_MAX_ASR_INPUT_SECONDS)
        assertEquals(2, result.size)
        assertEquals(0L, result[0].startSampleIndex)
        assertEquals((CanonicalPcmProfile.SAMPLE_RATE_HZ * 25).toLong(), result[0].endSampleIndexExclusive)
        assertEquals((CanonicalPcmProfile.SAMPLE_RATE_HZ * 25).toLong(), result[1].startSampleIndex)
        assertEquals(totalSamples.toLong(), result[1].endSampleIndexExclusive)
    }
}
