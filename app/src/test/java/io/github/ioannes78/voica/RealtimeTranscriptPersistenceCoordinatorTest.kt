package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimePcmWavSegmentFinalization
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.database.RealtimeAudioSourceTypeValue
import io.github.ioannes78.voica.database.RealtimeMediaSegmentStateValue
import io.github.ioannes78.voica.database.RealtimeTimelineMarkerTypeValue
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptionRunEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RealtimeAsrFinalizationReason
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioResult
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeTranscriptPersistenceCoordinatorTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: RealtimeTranscriptPersistenceRepository
    private var nextId = 0
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RealtimeTranscriptPersistenceRepository(database) { "rt-${nextId++}" }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `persists transcript media and lifecycle across reconnect sessions`() = runBlocking {
        repository.createRun(runEntity("run-a"))
        var generation = 10L
        val coordinator = coordinator("run-a") { generation }

        coordinator.accept(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)))
        coordinator.accept(
            lifecycle(
                RealtimeAsrPipelineEvent.ContinuityStarted(
                    sessionId = 1,
                    epochId = 1,
                    continuityIndex = 1,
                    startSampleIndex = null,
                    epochReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                ),
            ),
        )
        coordinator.onPauseCommitted(1, 1, 320)
        coordinator.accept(
            lifecycle(
                RealtimeAsrPipelineEvent.ContinuityStarted(
                    sessionId = 1,
                    epochId = 2,
                    continuityIndex = 2,
                    startSampleIndex = null,
                    epochReason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY,
                ),
            ),
        )
        coordinator.accept(finalHypothesis(sessionId = 1, epochId = 2, start = 0, end = 16_000))
        coordinator.accept(
            lifecycle(
                RealtimeAsrPipelineEvent.Discontinuity(
                    sessionId = 1,
                    epochId = 2,
                    resumeAtSampleIndex = 20_000,
                    reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
                    droppedFrames = 3,
                    droppedSamples = 960,
                ),
            ),
        )
        coordinator.accept(
            lifecycle(
                RealtimeAsrPipelineEvent.SessionEnded(
                    sessionId = 1,
                    reason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                    drainTimedOut = false,
                ),
            ),
        )
        coordinator.onLocalAudioResult(
            finalizedAudio(
                sessionId = 1,
                endReason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                sampleCount = 40_000,
                hadDiscontinuity = true,
            ),
            deviceFilename = "device.wav",
        )

        generation = 11L
        coordinator.markNextSessionAsReconnected()
        coordinator.accept(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(2)))
        coordinator.accept(
            lifecycle(
                RealtimeAsrPipelineEvent.ContinuityStarted(
                    sessionId = 2,
                    epochId = 1,
                    continuityIndex = 3,
                    startSampleIndex = null,
                    epochReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                ),
            ),
        )

        val segments = repository.getSegments("run-a")
        assertEquals(listOf(0, 1), segments.map { it.segmentIndex })
        assertEquals(listOf(10L, 11L), segments.map { it.transportGeneration })
        assertEquals(RealtimeMediaSegmentStateValue.INTERRUPTED, segments[0].state)
        assertEquals(40_000L, segments[0].canonicalSampleCount)
        assertEquals("session-1.wav", segments[0].localFilename)
        assertFalse(segments[0].isCompleteDeviceRecording)
        assertEquals(RealtimeMediaSegmentStateValue.ACTIVE, segments[1].state)

        val utterances = repository.getFinalUtterances(segments[0].id)
        assertEquals(1, utterances.size)
        assertEquals(0, utterances.single().utteranceIndex)
        assertEquals("原始文本", utterances.single().rawFinalText)
        assertEquals("原始文本。", utterances.single().finalText)
        assertEquals(0L, utterances.single().startSampleIndex)
        assertEquals(16_000L, utterances.single().endSampleIndexExclusive)

        val markers = repository.getTimelineMarkers("run-a")
        assertEquals(
            listOf(
                RealtimeTimelineMarkerTypeValue.PAUSE,
                RealtimeTimelineMarkerTypeValue.RESUME,
                RealtimeTimelineMarkerTypeValue.DISCONTINUITY,
                RealtimeTimelineMarkerTypeValue.TRANSPORT_CLOSED,
                RealtimeTimelineMarkerTypeValue.RECONNECTED,
            ),
            markers.map { it.type },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), markers.map { it.markerIndex })
        assertEquals(320L, markers[0].sampleIndex)
        assertEquals(0L, markers[1].sampleIndex)
        assertEquals(20_000L, markers[2].sampleIndex)
        assertEquals(2L, markers.last().canonicalSessionId)

        val snapshot = coordinator.snapshot()
        assertEquals(2, snapshot.knownCanonicalSessions)
        assertEquals(1L, snapshot.persistedFinalUtterances)
        assertEquals(5L, snapshot.persistedMarkers)
        assertEquals(1L, snapshot.finalizedMediaSegments)
    }

    @Test
    fun `local audio completion may arrive before session row and remains first terminal wins`() = runBlocking {
        repository.createRun(runEntity("run-b"))
        val coordinator = coordinator("run-b") { 5L }

        coordinator.onLocalAudioResult(
            finalizedAudio(
                sessionId = 4,
                endReason = RealtimeCanonicalSessionEndReason.STOP,
                sampleCount = 32_000,
                hadDiscontinuity = false,
            ),
            isCompleteDeviceRecording = true,
        )
        assertEquals(1, coordinator.snapshot().pendingLocalAudioResults)

        coordinator.accept(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(4)))
        val stored = repository.getSegments("run-b").single()
        assertEquals(RealtimeMediaSegmentStateValue.FINALIZED, stored.state)
        assertEquals(32_000L, stored.canonicalSampleCount)
        assertTrue(stored.isCompleteDeviceRecording)
        assertEquals(0, coordinator.snapshot().pendingLocalAudioResults)

        coordinator.onLocalAudioResult(
            finalizedAudio(
                sessionId = 4,
                endReason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                sampleCount = 64_000,
                hadDiscontinuity = false,
            ),
        )
        val unchanged = repository.getSegments("run-b").single()
        assertEquals(RealtimeMediaSegmentStateValue.FINALIZED, unchanged.state)
        assertEquals(32_000L, unchanged.canonicalSampleCount)
        assertEquals(1L, coordinator.snapshot().duplicateMediaFinalizations)
    }

    private fun coordinator(
        runId: String,
        generation: () -> Long?,
    ) = RealtimeTranscriptPersistenceCoordinator(
        repository = repository,
        runId = runId,
        transportGenerationProvider = generation,
        localPathResolver = { file -> "realtime/$runId/${file.name}" },
        clockMs = { now++ },
    )

    private fun lifecycle(event: RealtimeAsrPipelineEvent): RealtimeTranscriptionOutput.Lifecycle =
        RealtimeTranscriptionOutput.Lifecycle(event)

    private fun finalHypothesis(
        sessionId: Long,
        epochId: Long,
        start: Long,
        end: Long,
    ): RealtimeTranscriptionOutput.Hypothesis {
        val hypothesis =
            AsrHypothesis(
                text = "原始文本。",
                detectedLanguage = "zh",
                punctuationCapability = PunctuationCapability.RELIABLE,
                isFinal = true,
                stability = AsrHypothesisStability.FINAL,
            )
        val source =
            RealtimeAsrPipelineEvent.Hypothesis(
                sessionId = sessionId,
                epochId = epochId,
                continuityIndex = 2,
                recognizerBaseSampleIndex = start,
                acceptedEndSampleIndexExclusive = end,
                hypothesis = hypothesis.copy(text = "原始文本"),
                finalizationReason = RealtimeAsrFinalizationReason.VAD_SPEECH_END,
            )
        return RealtimeTranscriptionOutput.Hypothesis(
            source = source,
            rawText = "原始文本",
            displayHypothesis = hypothesis,
            canonicalTokens = emptyList(),
            punctuationAttempted = true,
            externalPunctuationApplied = true,
            externalPunctuationFailed = false,
        )
    }

    private fun finalizedAudio(
        sessionId: Long,
        endReason: RealtimeCanonicalSessionEndReason,
        sampleCount: Long,
        hadDiscontinuity: Boolean,
    ) = RealtimeLocalAudioResult.Finalized(
        canonicalSessionId = sessionId,
        finalEpochId = 2,
        finalSampleIndexExclusive = sampleCount,
        endReason = endReason,
        drainTimedOut = false,
        media =
            RealtimePcmWavSegmentFinalization(
                file = File("/tmp/session-$sessionId.wav"),
                sampleCount = sampleCount,
                sizeBytes = 44 + sampleCount * 2,
                sha256 = "a".repeat(64),
                hadDiscontinuity = hadDiscontinuity,
            ),
    )

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
}
