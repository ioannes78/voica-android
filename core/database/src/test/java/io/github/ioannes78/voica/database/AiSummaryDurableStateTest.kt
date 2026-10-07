package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class AiSummaryDurableStateTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: AiSummaryRepository
    private var clock = 10_000L

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
    fun remoteDispatchRequiresMatchingGenerationAndPersistsSendBoundary() = runBlocking {
        val summaryId = createSummary()
        val created = requireNotNull(repository.find(summaryId))
        assertEquals(1L, created.executionGeneration)
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, created.remoteDispatchState)
        assertEquals(0, created.remoteCallOrdinal)

        assertFalse(
            repository.prepareRemoteCall(
                summaryId = summaryId,
                generation = 2L,
                requestId = "request-1",
                stepKind = "MAP",
                stepKey = "map:0",
            ),
        )
        assertTrue(
            repository.prepareRemoteCall(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-1",
                stepKind = "MAP",
                stepKey = "map:0",
            ),
        )
        assertTrue(
            repository.markRemoteRequestInFlight(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-1",
                stepKind = "MAP",
                stepKey = "map:0",
            ),
        )

        val inFlight = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT, inFlight.remoteDispatchState)
        assertEquals("request-1", inFlight.remoteRequestId)
        assertEquals("MAP", inFlight.remoteStepKind)
        assertEquals("map:0", inFlight.remoteStepKey)
        assertEquals(1, inFlight.remoteCallOrdinal)
        assertTrue(inFlight.remoteStartedAtMs != null)

        assertFalse(repository.clearRemoteDispatch(summaryId, 2L, "request-1"))
        assertTrue(repository.clearRemoteDispatch(summaryId, 1L, "request-1"))

        val cleared = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.NONE, cleared.remoteDispatchState)
        assertNull(cleared.remoteRequestId)
        assertNull(cleared.remoteStartedAtMs)
        assertEquals(1, cleared.remoteCallOrdinal)
    }

    @Test
    fun followUpRemoteRequestAtomicallyReplacesInFlightIdentity() = runBlocking {
        val summaryId = createSummary()
        assertTrue(
            repository.prepareRemoteCall(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-1",
                stepKind = "DIRECT",
                stepKey = "direct",
            ),
        )
        assertTrue(
            repository.markRemoteRequestInFlight(
                summaryId = summaryId,
                generation = 1L,
                requestId = "request-1",
                stepKind = "DIRECT",
                stepKey = "direct",
            ),
        )

        assertFalse(
            repository.replaceRemoteRequestInFlight(
                summaryId = summaryId,
                generation = 1L,
                expectedRequestId = "stale-request",
                newRequestId = "request-2",
                stepKind = "REPAIR",
                stepKey = "direct:repair:1",
            ),
        )
        assertTrue(
            repository.replaceRemoteRequestInFlight(
                summaryId = summaryId,
                generation = 1L,
                expectedRequestId = "request-1",
                newRequestId = "request-2",
                stepKind = "REPAIR",
                stepKey = "direct:repair:1",
            ),
        )

        val replaced = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT, replaced.remoteDispatchState)
        assertEquals("request-2", replaced.remoteRequestId)
        assertEquals("REPAIR", replaced.remoteStepKind)
        assertEquals("direct:repair:1", replaced.remoteStepKey)
        assertEquals(2, replaced.remoteCallOrdinal)
    }

    @Test
    fun inFlightRequestCanBecomeAmbiguousAndCannotBeRevivedByLateProgress() = runBlocking {
        val summaryId = createSummary()
        assertTrue(
            repository.prepareRemoteCall(
                summaryId,
                1L,
                "request-ambiguous",
                "DIRECT",
                "direct",
            ),
        )
        assertTrue(
            repository.markRemoteRequestInFlight(
                summaryId,
                1L,
                "request-ambiguous",
                "DIRECT",
                "direct",
            ),
        )
        assertTrue(repository.markAmbiguousRemoteResult(summaryId, 1L))

        val ambiguous = requireNotNull(repository.find(summaryId))
        assertEquals(AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT, ambiguous.status)
        assertEquals("REMOTE_RESULT_UNKNOWN", ambiguous.errorCode)
        assertEquals(AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT, ambiguous.remoteDispatchState)
        assertFalse(repository.transition(summaryId, AiSummaryStateValue.VALIDATING))
        assertFalse(repository.markAmbiguousRemoteResult(summaryId, 1L))

        assertTrue(repository.acknowledgeTerminal(summaryId))
        assertTrue(requireNotNull(repository.find(summaryId)).terminalAcknowledgedAtMs != null)
    }

    @Test
    fun staleGenerationCannotCancelCurrentExecution() = runBlocking {
        val summaryId = createSummary()
        assertFalse(repository.cancelForGeneration(summaryId, 2L))
        assertTrue(repository.cancelForGeneration(summaryId, 1L))
        assertEquals(AiSummaryStateValue.CANCELLED, requireNotNull(repository.find(summaryId)).status)
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
        const val RECORDING_ID = "recording-durable"
        const val TRANSCRIPTION_ID = "transcription-durable"
    }
}
