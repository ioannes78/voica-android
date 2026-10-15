package io.github.ioannes78.voica.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeCanonicalEpochProjectionTest {
    @Test
    fun `single epoch remains aligned at canonical zero without markers`() {
        val segment = segment(sampleCount = 16_000L)
        val utterance = utterance(epochId = 1L, start = 2_000L, end = 8_000L)

        val offsets =
            resolveRealtimeCanonicalEpochOffsets(
                segment = segment,
                utterances = listOf(utterance),
                markers = emptyList(),
            )

        assertEquals(mapOf(1L to 0L), offsets)
    }

    @Test
    fun `pause resume offsets second epoch by first epoch media length`() {
        val segment = segment(sampleCount = 24_000L)
        val first = utterance(id = "u1", epochId = 1L, start = 4_000L, end = 12_000L)
        val second = utterance(id = "u2", epochId = 2L, start = 1_000L, end = 6_000L)
        val markers =
            listOf(
                marker(
                    index = 0,
                    type = RealtimeTimelineMarkerTypeValue.PAUSE,
                    epochId = 1L,
                    sampleIndex = 16_000L,
                ),
                marker(
                    index = 1,
                    type = RealtimeTimelineMarkerTypeValue.RESUME,
                    epochId = 2L,
                    sampleIndex = 0L,
                ),
            )

        val offsets =
            resolveRealtimeCanonicalEpochOffsets(
                segment = segment,
                utterances = listOf(first, second),
                markers = markers,
            )

        assertEquals(mapOf(1L to 0L, 2L to 16_000L), offsets)
        assertEquals(17_000L, checkNotNull(offsets)[2L]!! + second.startSampleIndex)
        assertEquals(22_000L, offsets[2L]!! + second.endSampleIndexExclusive)
    }

    @Test
    fun `resume without proven pause refuses canonical alignment`() {
        val segment = segment(sampleCount = 24_000L)
        val utterance = utterance(epochId = 2L, start = 0L, end = 4_000L)
        val markers =
            listOf(
                marker(
                    index = 0,
                    type = RealtimeTimelineMarkerTypeValue.RESUME,
                    epochId = 2L,
                    sampleIndex = 0L,
                ),
            )

        assertNull(
            resolveRealtimeCanonicalEpochOffsets(
                segment = segment,
                utterances = listOf(utterance),
                markers = markers,
            ),
        )
    }

    @Test
    fun `paused terminal segment requires durable lengths to equal finalized wav`() {
        val segment = segment(sampleCount = 12_000L)
        val utterance = utterance(epochId = 1L, start = 0L, end = 8_000L)
        val markers =
            listOf(
                marker(
                    index = 0,
                    type = RealtimeTimelineMarkerTypeValue.PAUSE,
                    epochId = 1L,
                    sampleIndex = 10_000L,
                ),
            )

        assertNull(
            resolveRealtimeCanonicalEpochOffsets(
                segment = segment,
                utterances = listOf(utterance),
                markers = markers,
            ),
        )
    }

    private fun segment(sampleCount: Long) =
        RealtimeMediaSegmentEntity(
            id = "segment-1",
            runId = "run-1",
            segmentIndex = 0,
            transportGeneration = null,
            canonicalSessionId = 7L,
            state = RealtimeMediaSegmentStateValue.FINALIZED,
            startedAtMs = 1_000L,
            endedAtMs = 2_000L,
            endReason = "STOP",
            canonicalSampleCount = sampleCount,
            localRelativePath = "phone/recording.wav",
            localFilename = "recording.wav",
            deviceFilename = null,
            sizeBytes = 44L + sampleCount * 2L,
            sha256 = "a".repeat(64),
            isCompleteDeviceRecording = false,
        )

    private fun utterance(
        id: String = "u1",
        epochId: Long,
        start: Long,
        end: Long,
    ) = RealtimeUtteranceEntity(
        id = id,
        segmentId = "segment-1",
        utteranceIndex = if (id == "u1") 0 else 1,
        canonicalEpochId = epochId,
        startSampleIndex = start,
        endSampleIndexExclusive = end,
        rawFinalText = id,
        finalText = id,
        detectedLanguage = "zh",
        createdAtMs = 1_500L,
    )

    private fun marker(
        index: Int,
        type: String,
        epochId: Long,
        sampleIndex: Long,
    ) = RealtimeTimelineMarkerEntity(
        id = "marker-$index",
        runId = "run-1",
        segmentId = "segment-1",
        markerIndex = index,
        type = type,
        canonicalSessionId = 7L,
        canonicalEpochId = epochId,
        sampleIndex = sampleIndex,
        createdAtMs = 1_200L + index,
        detail = null,
    )
}
