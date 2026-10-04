package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage12CContentRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: Stage12CContentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                VoicaDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            Stage12CContentRepository(
                database = database,
                nowMs = { 10_000L },
                idFactory = { java.util.UUID.randomUUID().toString() },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun transcriptionDeleteIsBlockedWhileAiSummaryReferencesVersion() = runBlocking {
        insertRecording()
        insertCompletedTranscription()
        insertCompletedSummary()

        val blocked = repository.deleteTranscriptionVersion(TRANSCRIPTION_ID)
        assertTrue(blocked is ContentVersionDeleteResult.ReferencedByAiSummaries)
        assertEquals(
            1,
            (blocked as ContentVersionDeleteResult.ReferencedByAiSummaries).count,
        )
        assertEquals(
            TRANSCRIPTION_ID,
            database.transcriptionDao().findTranscription(TRANSCRIPTION_ID)?.id,
        )
        assertEquals(
            SUMMARY_ID,
            database.aiSummaryDao().findSummary(SUMMARY_ID)?.id,
        )

        repository.createAiSummaryRevision(
            summaryId = SUMMARY_ID,
            revisionPayloadJson = """{"schemaVersion":1,"title":"人工稿"}""",
        )
        val revision =
            database.stage12cContentDao()
                .findLatestAiSummaryRevision(SUMMARY_ID)
        assertTrue(revision != null)

        val summaryDelete = repository.deleteAiSummaryVersion(SUMMARY_ID)
        assertTrue(summaryDelete is ContentVersionDeleteResult.Deleted)
        assertNull(database.aiSummaryDao().findSummary(SUMMARY_ID))
        assertNull(
            revision?.id?.let {
                database.stage12cContentDao().findAiSummaryRevision(it)
            },
        )
        assertEquals(
            TRANSCRIPTION_ID,
            database.transcriptionDao().findTranscription(TRANSCRIPTION_ID)?.id,
        )

        val transcriptionDelete =
            repository.deleteTranscriptionVersion(TRANSCRIPTION_ID)
        assertTrue(transcriptionDelete is ContentVersionDeleteResult.Deleted)
        assertNull(
            database.transcriptionDao().findTranscription(TRANSCRIPTION_ID),
        )
    }

    @Test
    fun currentVersionSelectionPersistsAndFallsBackAfterDelete() = runBlocking {
        insertRecording()
        insertCompletedTranscription()
        insertCompletedTranscription(
            id = SECOND_TRANSCRIPTION_ID,
            createdAtMs = 6L,
            completedAtMs = 7L,
        )

        repository.setCurrentTranscriptionVersion(
            recordingId = RECORDING_ID,
            transcriptionId = TRANSCRIPTION_ID,
        )
        assertEquals(
            TRANSCRIPTION_ID,
            repository.resolveCurrentTranscriptionId(RECORDING_ID),
        )

        val deleted = repository.deleteTranscriptionVersion(TRANSCRIPTION_ID)
        assertTrue(deleted is ContentVersionDeleteResult.Deleted)
        assertEquals(
            SECOND_TRANSCRIPTION_ID,
            repository.resolveCurrentTranscriptionId(RECORDING_ID),
        )
    }

    @Test
    fun currentSummarySelectionPersistsAndFallsBackAfterDelete() = runBlocking {
        insertRecording()
        insertCompletedTranscription()
        insertCompletedSummary()
        insertCompletedSummary(
            id = SECOND_SUMMARY_ID,
            createdAtMs = 8L,
            completedAtMs = 9L,
        )

        repository.setCurrentAiSummaryVersion(
            recordingId = RECORDING_ID,
            summaryId = SUMMARY_ID,
        )
        assertEquals(
            SUMMARY_ID,
            repository.resolveCurrentAiSummaryId(RECORDING_ID),
        )

        val deleted = repository.deleteAiSummaryVersion(SUMMARY_ID)
        assertTrue(deleted is ContentVersionDeleteResult.Deleted)
        assertEquals(
            SECOND_SUMMARY_ID,
            repository.resolveCurrentAiSummaryId(RECORDING_ID),
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

    private suspend fun insertCompletedTranscription(
        id: String = TRANSCRIPTION_ID,
        createdAtMs: Long = 2L,
        completedAtMs: Long = 3L,
    ) {
        database.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = id,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.FAST,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = "canonical",
                sourceCanonicalSha256 =
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
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
                modelManifestDigest =
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                createdAtMs = createdAtMs,
                startedAtMs = createdAtMs,
                updatedAtMs = completedAtMs,
                completedAtMs = completedAtMs,
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun insertCompletedSummary(
        id: String = SUMMARY_ID,
        createdAtMs: Long = 4L,
        completedAtMs: Long = 5L,
    ) {
        database.aiSummaryDao().insertSummary(
            AiSummaryEntity(
                id = id,
                recordingId = RECORDING_ID,
                transcriptionId = TRANSCRIPTION_ID,
                inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
                mode = AiSummaryModeValue.SMART,
                templateId = null,
                templateSnapshot = null,
                providerProfileId = "provider",
                providerNameSnapshot = "Provider",
                baseUrlSnapshot = "https://example.invalid",
                model = "model",
                promptVersion = 1,
                resultSchemaVersion = 1,
                contentType = "MEETING",
                classificationConfidence = 0.9,
                structuredPayloadJson = """{"schemaVersion":1}""",
                displayText = "summary",
                status = AiSummaryStateValue.COMPLETED,
                createdAtMs = createdAtMs,
                startedAtMs = createdAtMs,
                updatedAtMs = completedAtMs,
                completedAtMs = completedAtMs,
                errorCode = null,
                sanitizedErrorMessage = null,
                requestConfigSnapshot = "{}",
                usageSnapshot = null,
                alignmentIdSnapshot = null,
                sourceLineageSnapshot =
                    """{"transcriptionId":"$TRANSCRIPTION_ID"}""",
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "recording-stage12c"
        const val TRANSCRIPTION_ID = "transcription-stage12c"
        const val SECOND_TRANSCRIPTION_ID = "transcription-stage12c-2"
        const val SUMMARY_ID = "summary-stage12c"
        const val SECOND_SUMMARY_ID = "summary-stage12c-2"
    }
}
