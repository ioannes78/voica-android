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
class Stage13AQA6ContentLifecycleTest {
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
                nowMs = { 50_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstTranscriptionBecomesCurrentAndLaterCompletionStaysCandidate() = runBlocking {
        insertRecording()
        insertCompletedTranscription(T1, createdAtMs = 10L, completedAtMs = 20L)
        insertSegment(T1, SEGMENT_1, "模型原文一")

        val first = contentRepository.onTranscriptionCompleted(RECORDING_ID, T1)
        assertTrue(first.adopted)
        assertEquals(T1, first.currentId)
        assertNull(first.candidateId)

        val revisionId =
            contentRepository.createTranscriptionRevision(
                transcriptionId = T1,
                paragraphs =
                    listOf(
                        TranscriptionRevisionParagraphDraft(
                            text = "人工当前正文",
                            sourceAnchorRefsJson = "[\"SEGMENT:$SEGMENT_1\"]",
                            anchorStartSampleIndex = 0L,
                            anchorEndSampleIndexExclusive = 8_000L,
                            timingQuality = TranscriptRevisionTimingQualityValue.EXACT,
                            isUserModified = true,
                        ),
                    ),
            )

        insertCompletedTranscription(T2, createdAtMs = 30L, completedAtMs = 40L)
        insertSegment(T2, SEGMENT_2, "新候选正文")
        val second = contentRepository.onTranscriptionCompleted(RECORDING_ID, T2)

        assertFalse(second.adopted)
        assertEquals(T1, second.currentId)
        assertEquals(T2, second.candidateId)
        assertEquals(T2, contentRepository.resolveTranscriptionCandidateId(RECORDING_ID))
        assertEquals(
            EffectiveTranscriptionRef(T1, revisionId),
            contentRepository.resolveEffectiveTranscription(RECORDING_ID),
        )

        contentRepository.setCurrentTranscriptionVersion(RECORDING_ID, T2)
        assertEquals(T2, contentRepository.resolveCurrentTranscriptionId(RECORDING_ID))
        assertNull(contentRepository.resolveTranscriptionCandidateId(RECORDING_ID))
    }

    @Test
    fun firstSummaryBecomesCurrentAndLaterCompletionStaysCandidate() = runBlocking {
        insertRecording()
        insertCompletedTranscription(T1, createdAtMs = 10L, completedAtMs = 20L)
        insertSegment(T1, SEGMENT_1, "正文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T1)

        insertCompletedSummary(S1, T1, "当前总结", createdAtMs = 30L, completedAtMs = 40L)
        val first = contentRepository.onAiSummaryCompleted(RECORDING_ID, S1)
        assertTrue(first.adopted)
        assertEquals(S1, first.currentId)
        assertNull(first.candidateId)

        insertCompletedSummary(S2, T1, "新候选总结", createdAtMs = 50L, completedAtMs = 60L)
        val second = contentRepository.onAiSummaryCompleted(RECORDING_ID, S2)
        assertFalse(second.adopted)
        assertEquals(S1, second.currentId)
        assertEquals(S2, second.candidateId)
        assertEquals(S2, contentRepository.resolveAiSummaryCandidateId(RECORDING_ID))

        contentRepository.setCurrentAiSummaryVersion(RECORDING_ID, S2)
        assertEquals(S2, contentRepository.resolveCurrentAiSummaryId(RECORDING_ID))
        assertNull(contentRepository.resolveAiSummaryCandidateId(RECORDING_ID))
    }

    @Test
    fun effectiveRevisionIsActualAiInputAndUnanchoredTextHasNoFakeEvidence() = runBlocking {
        insertRecording()
        insertCompletedTranscription(T1, createdAtMs = 10L, completedAtMs = 20L)
        insertSegment(T1, SEGMENT_1, "模型原文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T1)

        val revisionId =
            contentRepository.createTranscriptionRevision(
                transcriptionId = T1,
                paragraphs =
                    listOf(
                        TranscriptionRevisionParagraphDraft(
                            text = "人工修改正文",
                            sourceAnchorRefsJson = "[\"SEGMENT:$SEGMENT_1\"]",
                            anchorStartSampleIndex = 0L,
                            anchorEndSampleIndexExclusive = 8_000L,
                            timingQuality = TranscriptRevisionTimingQualityValue.EXACT,
                            isUserModified = true,
                        ),
                        TranscriptionRevisionParagraphDraft(
                            text = "人工新增补充",
                            sourceAnchorRefsJson = "[]",
                            anchorStartSampleIndex = null,
                            anchorEndSampleIndexExclusive = null,
                            timingQuality = TranscriptRevisionTimingQualityValue.APPROXIMATE,
                            isUserModified = true,
                        ),
                    ),
            )

        val input = StructuredTranscriptInputBuilder(database).buildEffective(T1)

        assertEquals(revisionId, input.transcriptionRevisionId)
        assertEquals("人工修改正文\n人工新增补充", input.finalText)
        assertFalse(input.finalText.contains("模型原文"))
        assertEquals(64, input.inputContentDigest.length)
        assertTrue(input.units[0].evidence != null)
        assertNull(input.units[1].evidence)
    }

    @Test
    fun ordinarySearchIndexesOnlyEffectiveTranscriptionAndSummary() = runBlocking {
        insertRecording()
        insertCompletedTranscription(T1, createdAtMs = 10L, completedAtMs = 20L)
        insertSegment(T1, SEGMENT_1, "当前检索正文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T1)

        insertCompletedTranscription(T2, createdAtMs = 30L, completedAtMs = 40L)
        insertSegment(T2, SEGMENT_2, "候选检索正文")
        contentRepository.onTranscriptionCompleted(RECORDING_ID, T2)

        insertCompletedSummary(S1, T1, "当前检索总结", createdAtMs = 50L, completedAtMs = 60L)
        contentRepository.onAiSummaryCompleted(RECORDING_ID, S1)
        insertCompletedSummary(S2, T2, "候选检索总结", createdAtMs = 70L, completedAtMs = 80L)
        contentRepository.onAiSummaryCompleted(RECORDING_ID, S2)

        SearchIndexRebuilder(database).rebuildAll()
        val search = UnifiedSearchRepository(database)

        assertEquals(1, search.search("当前检索正文").size)
        assertTrue(search.search("候选检索正文").isEmpty())
        assertEquals(1, search.search("当前检索总结").size)
        assertTrue(search.search("候选检索总结").isEmpty())
    }

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "qa6.wav",
                displayName = "qa6",
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
        const val RECORDING_ID = "qa6-recording"
        const val T1 = "qa6-t1"
        const val T2 = "qa6-t2"
        const val SEGMENT_1 = "qa6-segment-1"
        const val SEGMENT_2 = "qa6-segment-2"
        const val S1 = "qa6-s1"
        const val S2 = "qa6-s2"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
