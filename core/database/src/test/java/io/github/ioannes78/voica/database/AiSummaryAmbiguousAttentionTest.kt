package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiSummaryAmbiguousAttentionTest {
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
    fun acknowledgedAmbiguousRemainsAttentionUntilExplicitRetryExists() = runBlocking {
        insertRecording()
        insertCompletedTranscription()

        val originalId = createAmbiguousSummary()
        assertTrue(repository.acknowledgeTerminal(originalId))
        assertNotNull(requireNotNull(repository.find(originalId)).terminalAcknowledgedAtMs)

        val beforeRetry = repository.observeDurableTasks().first()
        assertTrue(beforeRetry.any { it.id == originalId })

        val retryId = repository.create(newRequest(retryOfSummaryId = originalId))
        val afterRetry = repository.observeDurableTasks().first()
        assertFalse(afterRetry.any { it.id == originalId })
        assertTrue(afterRetry.any { it.id == retryId })
    }

    @Test
    fun explicitRetrySuppressesAmbiguousAttentionEvenBeforeAckCommits() = runBlocking {
        insertRecording()
        insertCompletedTranscription()

        val originalId = createAmbiguousSummary()
        val retryId = repository.create(newRequest(retryOfSummaryId = originalId))
        val tasks = repository.observeDurableTasks().first()

        assertFalse(tasks.any { it.id == originalId })
        assertTrue(tasks.any { it.id == retryId })
    }

    private suspend fun createAmbiguousSummary(): String {
        val summaryId = repository.create(newRequest())
        assertTrue(
            repository.transition(
                summaryId = summaryId,
                status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                errorCode = "REMOTE_RESULT_UNKNOWN",
                sanitizedErrorMessage = "上一次请求状态无法确认，需要手动重试。",
            ),
        )
        return summaryId
    }

    private fun newRequest(retryOfSummaryId: String? = null): NewAiSummaryRequest =
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
            retryOfSummaryId = retryOfSummaryId,
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
        const val RECORDING_ID = "recording-ambiguous-attention"
        const val TRANSCRIPTION_ID = "transcription-ambiguous-attention"
    }
}
