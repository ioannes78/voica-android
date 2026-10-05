package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage13AQA6LibrarySearchTest {
    private lateinit var database: VoicaDatabase
    private lateinit var contentRepository: Stage12CContentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        contentRepository =
            Stage12CContentRepository(
                database = database,
                nowMs = { 90_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun libraryQuerySearchesEffectiveTranscriptAndSummaryButNotCandidates() = runBlocking {
        insertRecording()

        insertCompletedTranscription(T1, 10L, 20L)
        insertSegment(T1, SEGMENT_1, "中国当前正文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T1)

        insertCompletedTranscription(T2, 30L, 40L)
        insertSegment(T2, SEGMENT_2, "候选火星正文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T2)

        insertCompletedSummary(S1, T1, "当前总结包含海豚关键词", 50L, 60L)
        contentRepository.onAiSummaryCompleted(RECORDING_ID, S1)

        insertCompletedSummary(S2, T2, "候选总结包含木星关键词", 70L, 80L)
        contentRepository.onAiSummaryCompleted(RECORDING_ID, S2)

        SearchIndexRebuilder(database).rebuildAll()

        assertEquals(1, searchLibrary("中国").size)
        assertEquals(1, searchLibrary("海豚").size)
        assertTrue(searchLibrary("火星").isEmpty())
        assertTrue(searchLibrary("木星").isEmpty())
        assertEquals(1, searchLibrary("qa6-library").size)
    }

    private suspend fun searchLibrary(query: String): List<RecordingLibraryProjection> =
        database.recordingDao()
            .observeLibrary(LibraryQueryBuilder.build(LibraryQueryCriteria(query = query)))
            .first()

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "qa6-library.wav",
                displayName = "qa6-library",
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
        id: String,
        createdAtMs: Long,
        completedAtMs: Long,
    ) {
        database.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = id,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.HIGH_QUALITY,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = "canonical",
                sourceCanonicalSha256 = SHA_A,
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
                modelManifestDigest = SHA_B,
                createdAtMs = createdAtMs,
                startedAtMs = createdAtMs,
                updatedAtMs = completedAtMs,
                completedAtMs = completedAtMs,
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun insertSegment(
        transcriptionId: String,
        segmentId: String,
        text: String,
    ) {
        database.transcriptionDao().insertSegment(
            TranscriptSegmentEntity(
                id = segmentId,
                transcriptionId = transcriptionId,
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                firstPassRawText = text,
                secondPassRawText = null,
                finalText = text,
                detectedLanguage = "zh",
                confidence = 0.9f,
            ),
        )
    }

    private suspend fun insertCompletedSummary(
        id: String,
        transcriptionId: String,
        displayText: String,
        createdAtMs: Long,
        completedAtMs: Long,
    ) {
        database.aiSummaryDao().insertSummary(
            AiSummaryEntity(
                id = id,
                recordingId = RECORDING_ID,
                transcriptionId = transcriptionId,
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
                contentType = "GENERAL",
                classificationConfidence = 0.9,
                structuredPayloadJson = null,
                displayText = displayText,
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
                    """{"lineageVersion":2,"transcriptionId":"$transcriptionId","transcriptionRevisionId":null}""",
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "qa6-library-recording"
        const val T1 = "qa6-library-t1"
        const val T2 = "qa6-library-t2"
        const val SEGMENT_1 = "qa6-library-segment-1"
        const val SEGMENT_2 = "qa6-library-segment-2"
        const val S1 = "qa6-library-s1"
        const val S2 = "qa6-library-s2"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
