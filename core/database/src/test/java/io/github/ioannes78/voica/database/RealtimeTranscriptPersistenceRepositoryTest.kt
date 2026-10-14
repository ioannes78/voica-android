package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeTranscriptPersistenceRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `segment and final utterance indexes allocate atomically per parent`() = runBlocking {
        val ids =
            ArrayDeque(
                listOf(
                    "segment-a0",
                    "segment-a1",
                    "utterance-a0",
                    "utterance-a1",
                    "segment-b0",
                    "utterance-b0",
                ),
            )
        val repository = RealtimeTranscriptPersistenceRepository(database) { ids.removeFirst() }
        repository.createRun(runEntity("run-a"))
        repository.createRun(runEntity("run-b"))

        val segmentA0 = repository.createNextSegment(NewRealtimeMediaSegment("run-a", 10, 100, 1))
        val segmentA1 = repository.createNextSegment(NewRealtimeMediaSegment("run-a", 11, 101, 2))
        assertEquals(0, segmentA0.segmentIndex)
        assertEquals(1, segmentA1.segmentIndex)

        val utteranceA0 =
            repository.persistFinalUtterance(
                NewRealtimeFinalUtterance(
                    segmentId = segmentA0.id,
                    canonicalEpochId = 1,
                    startSampleIndex = 0,
                    endSampleIndexExclusive = 16_000,
                    rawFinalText = "第一句",
                    finalText = "第一句。",
                    detectedLanguage = "zh",
                    createdAtMs = 10,
                ),
            )
        val utteranceA1 =
            repository.persistFinalUtterance(
                NewRealtimeFinalUtterance(
                    segmentId = segmentA0.id,
                    canonicalEpochId = 1,
                    startSampleIndex = 16_000,
                    endSampleIndexExclusive = 32_000,
                    rawFinalText = "第二句",
                    finalText = "第二句。",
                    detectedLanguage = "zh",
                    createdAtMs = 11,
                ),
            )
        assertEquals(0, utteranceA0.utteranceIndex)
        assertEquals(1, utteranceA1.utteranceIndex)

        val segmentB0 = repository.createNextSegment(NewRealtimeMediaSegment("run-b", 20, 200, 3))
        val utteranceB0 =
            repository.persistFinalUtterance(
                NewRealtimeFinalUtterance(
                    segmentId = segmentB0.id,
                    canonicalEpochId = 1,
                    startSampleIndex = 0,
                    endSampleIndexExclusive = 8_000,
                    rawFinalText = "另一段",
                    finalText = "另一段。",
                    detectedLanguage = "zh",
                    createdAtMs = 12,
                ),
            )
        assertEquals(0, segmentB0.segmentIndex)
        assertEquals(0, utteranceB0.utteranceIndex)
    }

    @Test
    fun `timeline markers allocate deterministic per-run order`() = runBlocking {
        val ids = ArrayDeque(listOf("marker-0", "marker-1", "marker-2", "marker-3", "marker-4", "marker-b"))
        val repository = RealtimeTranscriptPersistenceRepository(database) { ids.removeFirst() }
        repository.createRun(runEntity("run-a"))
        repository.createRun(runEntity("run-b"))
        repository.createSegment(segmentEntity("segment-a", "run-a"))

        val types =
            listOf(
                RealtimeTimelineMarkerTypeValue.PAUSE,
                RealtimeTimelineMarkerTypeValue.RESUME,
                RealtimeTimelineMarkerTypeValue.DISCONTINUITY,
                RealtimeTimelineMarkerTypeValue.TRANSPORT_CLOSED,
                RealtimeTimelineMarkerTypeValue.RECONNECTED,
            )
        types.forEachIndexed { index, type ->
            val marker =
                repository.appendTimelineMarker(
                    NewRealtimeTimelineMarker(
                        runId = "run-a",
                        segmentId = "segment-a",
                        type = type,
                        canonicalSessionId = 10,
                        canonicalEpochId = 20,
                        sampleIndex = index.toLong() * 160L,
                        createdAtMs = 100L + index,
                    ),
                )
            assertEquals(index, marker.markerIndex)
        }

        val stored = repository.getTimelineMarkers("run-a")
        assertEquals(listOf(0, 1, 2, 3, 4), stored.map { it.markerIndex })
        assertEquals(types, stored.map { it.type })

        val firstInSecondRun =
            repository.appendTimelineMarker(
                NewRealtimeTimelineMarker(
                    runId = "run-b",
                    segmentId = null,
                    type = RealtimeTimelineMarkerTypeValue.TRANSPORT_CLOSED,
                    canonicalSessionId = null,
                    canonicalEpochId = null,
                    sampleIndex = null,
                    createdAtMs = 200L,
                ),
            )
        assertEquals(0, firstInSecondRun.markerIndex)
    }

    @Test
    fun `timeline marker rejects segment owned by another run`() = runBlocking {
        val repository = RealtimeTranscriptPersistenceRepository(database) { "marker" }
        repository.createRun(runEntity("run-a"))
        repository.createRun(runEntity("run-b"))
        repository.createSegment(segmentEntity("segment-b", "run-b"))

        var rejected = false
        try {
            repository.appendTimelineMarker(
                NewRealtimeTimelineMarker(
                    runId = "run-a",
                    segmentId = "segment-b",
                    type = RealtimeTimelineMarkerTypeValue.PAUSE,
                    canonicalSessionId = 1,
                    canonicalEpochId = 1,
                    sampleIndex = 0,
                    createdAtMs = 1,
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(repository.getTimelineMarkers("run-a").isEmpty())
    }

    @Test
    fun `segment finalization is first-terminal-wins`() = runBlocking {
        val repository = RealtimeTranscriptPersistenceRepository(database)
        repository.createRun(runEntity("run-a"))
        repository.createSegment(segmentEntity("segment-a", "run-a"))

        val first =
            repository.finalizeActiveSegment(
                RealtimeMediaSegmentFinalization(
                    segmentId = "segment-a",
                    terminalState = RealtimeMediaSegmentStateValue.FINALIZED,
                    endedAtMs = 200,
                    endReason = "STOP",
                    canonicalSampleCount = 32_000,
                    localRelativePath = "realtime/run-a/part01.wav",
                    localFilename = "part01.wav",
                    deviceFilename = "device.wav",
                    sizeBytes = 64_044,
                    sha256 = "first",
                    isCompleteDeviceRecording = true,
                ),
            )
        val duplicate =
            repository.finalizeActiveSegment(
                RealtimeMediaSegmentFinalization(
                    segmentId = "segment-a",
                    terminalState = RealtimeMediaSegmentStateValue.INTERRUPTED,
                    endedAtMs = 300,
                    endReason = "TRANSPORT_CLOSED",
                    canonicalSampleCount = 48_000,
                    localRelativePath = "realtime/run-a/overwritten.wav",
                    localFilename = "overwritten.wav",
                    deviceFilename = null,
                    sizeBytes = 96_044,
                    sha256 = "second",
                    isCompleteDeviceRecording = false,
                ),
            )

        assertTrue(first)
        assertFalse(duplicate)
        val stored = repository.getSegments("run-a").single()
        assertEquals(RealtimeMediaSegmentStateValue.FINALIZED, stored.state)
        assertEquals(200L, stored.endedAtMs)
        assertEquals("STOP", stored.endReason)
        assertEquals(32_000L, stored.canonicalSampleCount)
        assertEquals("part01.wav", stored.localFilename)
        assertEquals("first", stored.sha256)
        assertTrue(stored.isCompleteDeviceRecording)
    }

    private fun runEntity(id: String) =
        RealtimeTranscriptionRunEntity(
            id = id,
            recordingId = null,
            deviceRecordingIdentity = "device-recording-$id",
            audioSourceType = RealtimeAudioSourceTypeValue.RECORDER_CARD,
            state = RealtimeTranscriptionStateValue.ACTIVE,
            modelId = "zipformer-small-bilingual",
            modelVersion = "test",
            modelRevision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersion = "1.13.8",
            vadModelId = "silero-vad-int8",
            vadModelVersion = "test",
            vadModelRevision = 1,
            punctuationModelId = "ct-transformer-zh-en-int8",
            punctuationModelVersion = "test",
            punctuationModelRevision = 1,
            languageConfig = "zh-en",
            configSnapshotSchemaVersion = 1,
            requestedConfigSnapshot = "{}",
            effectiveConfigSnapshot = "{}",
            modelManifestDigest = "digest",
            createdAtMs = 1,
            startedAtMs = 1,
            updatedAtMs = 1,
            endedAtMs = null,
            endReason = null,
            errorCode = null,
            errorMessage = null,
        )

    private fun segmentEntity(id: String, runId: String) =
        RealtimeMediaSegmentEntity(
            id = id,
            runId = runId,
            segmentIndex = 0,
            transportGeneration = 1,
            canonicalSessionId = 1,
            state = RealtimeMediaSegmentStateValue.ACTIVE,
            startedAtMs = 1,
            endedAtMs = null,
            endReason = null,
            canonicalSampleCount = 0,
            localRelativePath = null,
            localFilename = null,
            deviceFilename = null,
            sizeBytes = null,
            sha256 = null,
            isCompleteDeviceRecording = false,
        )
}
