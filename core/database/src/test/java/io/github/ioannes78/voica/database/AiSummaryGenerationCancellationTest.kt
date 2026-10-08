package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class AiSummaryGenerationCancellationTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: AiSummaryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = AiSummaryRepository(database, nowMs = { 10_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun staleGenerationCannotCancelResumedSummary() = runBlocking {
        insertRecording()
        insertCompletedTranscription()
        val summaryId = repository.create(newSummaryRequest())

        assertTrue(repository.transition(summaryId, AiSummaryStateValue.INTERRUPTED))
        assertTrue(repository.resumeInterrupted(summaryId))

        val resumed = requireNotNull(repository.find(summaryId))
        assertEquals(2L, resumed.executionGeneration)
        assertEquals(AiSummaryStateValue.PREPARING, resumed.status)

        assertFalse(repository.cancelForGeneration(summaryId, 1L))
        val afterStaleCancel = requireNotNull(repository.find(summaryId))
        assertEquals(2L, afterStaleCancel.executionGeneration)
        assertEquals(AiSummaryStateValue.PREPARING, afterStaleCancel.status)

        assertTrue(repository.cancelForGeneration(summaryId, 2L))
        val cancelled = requireNotNull(repository.find(summaryId))
        assertEquals(2L, cancelled.executionGeneration)
        assertEquals(AiSummaryStateValue.CANCELLED, cancelled.status)
    }

    private fun newSummaryRequest() =
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
        )

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
        const val RECORDING_ID = "recording-generation-cancel"
        const val TRANSCRIPTION_ID = "transcription-generation-cancel"
    }
}
