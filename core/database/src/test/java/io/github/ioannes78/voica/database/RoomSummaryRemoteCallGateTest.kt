package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ai.SummaryCheckpointRecord
import io.github.ioannes78.voica.ai.SummaryRemoteCallDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSummaryRemoteCallGateTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: AiSummaryRepository
    private var clock = 20_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = AiSummaryRepository(database, nowMs = { clock++ })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun beginPersistsInFlightBeforeReturningAndResolveClearsExactRequest() = runBlocking {
        val summaryId = createSummary()
        val gate = RoomSummaryRemoteCallGate(repository, summaryId, generation = 1L)

        gate.begin(
            SummaryRemoteCallDescriptor(
                requestId = "request-1",
                stepKind = "DIRECT",
                stepKey = "direct",
            ),
        )

        val inFlight = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT, inFlight.remoteDispatchState)
        assertEquals("request-1", inFlight.remoteRequestId)
        assertEquals(1, inFlight.remoteCallOrdinal)

        gate.resolve("request-1")

        val cleared = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, cleared.remoteDispatchState)
        assertNull(cleared.remoteRequestId)
    }

    @Test
    fun preparedRequestResetRequiresExactRequestId() = runBlocking {
        val summaryId = createSummary()
        assertTrue(
            repository.prepareRemoteCall(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-ready",
                stepKind = "DIRECT",
                stepKey = "direct",
            ),
        )
        assertFalse(repository.resetPreparedRemoteCall(summaryId, 1L, "request-wrong"))
        assertTrue(repository.resetPreparedRemoteCall(summaryId, 1L, "request-ready"))

        val reset = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, reset.remoteDispatchState)
        assertNull(reset.remoteRequestId)
    }

    @Test
    fun staleRequestCannotMarkReplacementAmbiguous() = runBlocking {
        val summaryId = createSummary()
        val gate = RoomSummaryRemoteCallGate(repository, summaryId, generation = 1L)
        gate.begin(
            SummaryRemoteCallDescriptor("request-1", "DIRECT", "direct"),
        )
        gate.begin(
            call = SummaryRemoteCallDescriptor("request-2", "REPAIR", "direct:repair:1"),
            replacesRequestId = "request-1",
        )

        assertFalse(
            repository.markAmbiguousRemoteResult(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-1",
            ),
        )
        assertTrue(
            repository.markAmbiguousRemoteResult(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-2",
            ),
        )
        assertEquals(
            AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
            requireNotNull(repository.find(summaryId)).status,
        )
    }

    @Test
    fun completedSummaryCanClearRemoteBoundaryWithoutReopeningBusinessState() = runBlocking {
        val summaryId = createSummary()
        val gate = RoomSummaryRemoteCallGate(repository, summaryId, generation = 1L)
        gate.begin(
            SummaryRemoteCallDescriptor("request-final", "DIRECT", "direct"),
        )

        assertTrue(
            repository.persistCompletedForGeneration(
                summaryId = summaryId,
                generation = 1L,
                contentType = "GENERAL",
                classificationConfidence = null,
                structuredPayloadJson = "{}",
                displayText = "完成",
                usageSnapshot = null,
                evidence = emptyList(),
            ),
        )
        assertEquals(
            AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT,
            requireNotNull(repository.find(summaryId)).remoteDispatchState,
        )

        gate.resolve("request-final")

        val completed = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryStateValue.COMPLETED, completed.status)
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, completed.remoteDispatchState)
        assertNull(completed.remoteRequestId)
    }

    @Test
    fun interruptedInFlightRequestCannotUseLegacyResumePath() = runBlocking {
        val summaryId = createSummary()
        val gate = RoomSummaryRemoteCallGate(repository, summaryId, generation = 1L)
        gate.begin(
            SummaryRemoteCallDescriptor("request-uncertain", "DIRECT", "direct"),
        )
        assertTrue(repository.transition(summaryId, AiSummaryStateValue.INTERRUPTED))

        assertFalse(repository.resumeInterrupted(summaryId))
        val interrupted = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryStateValue.INTERRUPTED, interrupted.status)
        assertEquals(AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT, interrupted.remoteDispatchState)
    }

    @Test
    fun safeInterruptedResumeAdvancesExecutionGeneration() = runBlocking {
        val summaryId = createSummary()
        assertTrue(repository.transition(summaryId, AiSummaryStateValue.INTERRUPTED))

        assertTrue(repository.resumeInterrupted(summaryId))
        val resumed = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryStateValue.PREPARING, resumed.status)
        assertEquals(2L, resumed.executionGeneration)
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, resumed.remoteDispatchState)
    }

    @Test
    fun staleGenerationCannotMutateStateOrCompleteNewGeneration() = runBlocking {
        val summaryId = createSummary()
        assertTrue(repository.transition(summaryId, AiSummaryStateValue.INTERRUPTED))
        assertTrue(repository.resumeInterrupted(summaryId))

        assertFalse(
            repository.transitionForGeneration(
                summaryId = summaryId,
                generation = 1L,
                status = AiSummaryStateValue.MAPPING,
            ),
        )
        assertFalse(
            repository.persistCompletedForGeneration(
                summaryId = summaryId,
                generation = 1L,
                contentType = "GENERAL",
                classificationConfidence = null,
                structuredPayloadJson = "{}",
                displayText = "stale",
                usageSnapshot = null,
                evidence = emptyList(),
            ),
        )

        val current = requireNotNull(repository.find(summaryId))
        assertEquals(2L, current.executionGeneration)
        assertEquals(AiSummaryStateValue.PREPARING, current.status)
        assertNull(current.displayText)
    }

    @Test
    fun staleGenerationCannotReadOrOverwriteCheckpoint() = runBlocking {
        val summaryId = createSummary()
        val firstGenerationStore =
            RoomSummaryCheckpointStore(
                repository = repository,
                summaryId = summaryId,
                generation = 1L,
                nowMs = { clock++ },
            )
        val record =
            SummaryCheckpointRecord(
                level = 0,
                chunkIndex = 0,
                sourceStartOrdinal = 0,
                sourceEndOrdinalExclusive = 1,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                inputDigest = "digest-1",
                structuredResultJson = "{\"schemaVersion\":1}",
            )
        firstGenerationStore.save(record)
        assertTrue(repository.transition(summaryId, AiSummaryStateValue.INTERRUPTED))
        assertTrue(repository.resumeInterrupted(summaryId))

        assertNull(firstGenerationStore.load(0, 0, "digest-1"))
        var rejected = false
        try {
            firstGenerationStore.save(record.copy(structuredResultJson = "{\"stale\":true}"))
        } catch (_: SummaryRemoteCallBoundaryChangedException) {
            rejected = true
        }
        assertTrue(rejected)

        val secondGenerationStore =
            RoomSummaryCheckpointStore(
                repository = repository,
                summaryId = summaryId,
                generation = 2L,
                nowMs = { clock++ },
            )
        assertEquals("{\"schemaVersion\":1}", secondGenerationStore.load(0, 0, "digest-1"))
    }

    private suspend fun createSummary(): String {
        insertRecording()
        insertCompletedTranscription()
        return repository.create(
            NewAiSummaryRequest(
                recordingId = RECORDING_ID,
                transcriptionId = TRANSCRIPTION_ID,
                mode = AiSummaryModeValue.SMART,
                templateId = null,
                templateSnapshot = null,
                providerProfileId = "provider",
                providerNameSnapshot = "Provider",
                baseUrlSnapshot = "https://example.invalid",
                model = "model",
                promptVersion = 1,
                resultSchemaVersion = 1,
                requestConfigSnapshot = "{}",
                alignmentIdSnapshot = null,
                sourceLineageSnapshot = "{\"transcriptionId\":\"$TRANSCRIPTION_ID\"}",
            ),
        )
    }

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "sample.wav",
                displayName = "sample",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 1_000L,
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                state = RecordingState.ACTIVE,
            ),
        )
    }

    private suspend fun insertCompletedTranscription() {
        database.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = TRANSCRIPTION_ID,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.FAST,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = "canonical",
                sourceCanonicalSha256 = "a".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = 16_000L,
                pipelineVersion = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "vad",
                vadModelVersion = "1",
                firstPassAsrModelId = "asr",
                firstPassAsrModelVersion = "1",
                secondPassAsrModelId = null,
                secondPassAsrModelVersion = null,
                punctuationModelId = "punctuation",
                punctuationModelVersion = "1",
                languageConfig = "auto",
                configSnapshot = "{}",
                modelManifestDigest = "b".repeat(64),
                createdAtMs = 2L,
                startedAtMs = 2L,
                updatedAtMs = 3L,
                completedAtMs = 3L,
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "recording-remote-gate"
        const val TRANSCRIPTION_ID = "transcription-remote-gate"
    }
}
