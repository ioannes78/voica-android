package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ai.AiContentType
import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.AiSummaryItem
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.AiSummarySection
import io.github.ioannes78.voica.ai.AiSummarySectionType
import io.github.ioannes78.voica.ai.EvidenceSourceRef
import io.github.ioannes78.voica.ai.OfflineSummaryChunkPlanner
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceRepository
import io.github.ioannes78.voica.database.OfflineSummaryPersistenceRepository
import io.github.ioannes78.voica.database.OfflineSummaryTaskStateValue
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.RecordingState
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionModeValue
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.database.VoicaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 17.3 P6 regression: Long execution must terminate on the exact same summary/evidence data
 * surface as Direct execution. Existing history, current/candidate, search/export and playback
 * consumers must not need a Long-specific result type or evidence coordinate path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineSummaryLongCompatibilityRegressionTest {
    private lateinit var database: VoicaDatabase
    private lateinit var directPersistence: OfflineSummaryPersistenceRepository
    private lateinit var longPersistence: OfflineSummaryLongPersistenceRepository
    private lateinit var longStore: RoomOfflineSummaryLongStateStore
    private lateinit var contentRepository: Stage12CContentRepository
    private var now = 50_000L
    private var idCounter = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        directPersistence =
            OfflineSummaryPersistenceRepository(
                database = database,
                nowMs = { now++ },
                idFactory = { "stage173-compat-${++idCounter}" },
            )
        longPersistence =
            OfflineSummaryLongPersistenceRepository(
                database = database,
                nowMs = { now++ },
            )
        longStore = RoomOfflineSummaryLongStateStore(longPersistence, directPersistence)
        contentRepository = Stage12CContentRepository(database, nowMs = { now++ })
        runBlocking {
            insertRecording()
            insertCompletedTranscription()
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun longCompletionPreservesSummaryEvidenceHistoryAndCurrentCandidateContracts() = runBlocking {
        val firstId = completeLongSummary("第一次长文本总结")
        val firstDecision = contentRepository.onAiSummaryCompleted(RECORDING_ID, firstId)

        assertTrue(firstDecision.adopted)
        assertEquals(firstId, firstDecision.currentId)
        assertEquals(firstId, contentRepository.resolveCurrentAiSummaryId(RECORDING_ID))
        assertLongResultSurface(firstId, "第一次长文本总结")

        val secondId = completeLongSummary("第二次长文本总结")
        val secondDecision = contentRepository.onAiSummaryCompleted(RECORDING_ID, secondId)

        assertFalse(secondDecision.adopted)
        assertEquals(firstId, secondDecision.currentId)
        assertEquals(secondId, secondDecision.candidateId)
        assertEquals(firstId, contentRepository.resolveCurrentAiSummaryId(RECORDING_ID))
        assertEquals(secondId, contentRepository.resolveAiSummaryCandidateId(RECORDING_ID))
        assertLongResultSurface(secondId, "第二次长文本总结")

        val history = database.aiSummaryDao().observeForRecording(RECORDING_ID).first()
        assertTrue(history.any { it.id == firstId && it.status == AiSummaryStateValue.COMPLETED })
        assertTrue(history.any { it.id == secondId && it.status == AiSummaryStateValue.COMPLETED })
        assertEquals(2, history.count { it.id == firstId || it.id == secondId })
    }

    private suspend fun completeLongSummary(title: String): String {
        val input = input()
        val summary =
            directPersistence.create(
                input = input,
                modelIdentitySnapshot = Stage172OfflineSummaryModelContract.persistedIdentitySnapshot,
            )
        directPersistence.markPreparing(summary.id)
        val plan = OfflineSummaryChunkPlanner().plan(input)
        longPersistence.create(
            summaryId = summary.id,
            input = input,
            modelIdentitySnapshot = Stage172OfflineSummaryModelContract.persistedIdentitySnapshot,
            plan = plan,
            directPromptEstimatedTokens = 3_200,
        )
        longPersistence.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        longPersistence.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.VALIDATING, currentLevel = 1)

        val warning =
            longStore.complete(
                summaryId = summary.id,
                result = result(title),
                evidence = listOf(evidenceDraft()),
            )

        assertNull(warning)
        val longSnapshot = longPersistence.snapshot(summary.id)
        assertNotNull(longSnapshot)
        assertEquals(OfflineSummaryTaskStateValue.COMPLETED, longSnapshot?.task?.status)
        assertTrue(longSnapshot?.nodes?.isEmpty() == true)
        return summary.id
    }

    private suspend fun assertLongResultSurface(summaryId: String, expectedTitle: String) {
        val completed = checkNotNull(directPersistence.find(summaryId))
        assertEquals(AiSummaryStateValue.COMPLETED, completed.status)
        assertEquals(AiContentType.MEETING.name, completed.contentType)
        assertTrue(completed.displayText?.contains(expectedTitle) == true)
        assertTrue(completed.displayText?.contains("周四前完成测试") == true)
        assertNotNull(completed.completedAtMs)
        assertNull(completed.errorCode)

        val decoded =
            SummaryResultCodec.decode(
                raw = checkNotNull(completed.structuredPayloadJson),
                allowedEvidenceRefs = setOf("S00001"),
            )
        assertEquals(expectedTitle, decoded.title)
        assertEquals("周四前完成测试。", decoded.sections.single().items.single().text)

        val evidence = database.aiSummaryDao().loadEvidence(summaryId)
        assertEquals(1, evidence.size)
        assertEquals("item-1", evidence.single().summaryItemId)
        assertEquals("S00001", evidence.single().sourceRef)
        assertEquals(TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT.name, evidence.single().sourceKind)
        assertEquals(0L, evidence.single().startSampleIndex)
        assertEquals(16_000L, evidence.single().endSampleIndexExclusive)
    }

    private fun input(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = TRANSCRIPTION_ID,
            transcriptionRevisionId = REVISION_ID,
            inputContentDigest = SHA_C,
            transcriptionMode = TranscriptionModeValue.HIGH_QUALITY,
            canonicalAssetId = CANONICAL_ASSET_ID,
            canonicalSha256 = SHA_A,
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 16_000L,
            languageConfig = "auto",
            alignmentId = ALIGNMENT_ID,
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = SEGMENT_ID,
                                speakerId = null,
                                speakerDisplayName = null,
                                assignmentQuality = null,
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 0L,
                                endSampleIndexExclusive = 16_000L,
                            ),
                        text = "周四前完成测试，周五发布第一版。",
                        detectedLanguage = "zh",
                    ),
                ),
        )

    private fun result(title: String): AiSummaryResult =
        AiSummaryResult(
            schemaVersion = 1,
            contentType = AiContentType.MEETING,
            classificationConfidence = 0.91,
            title = title,
            overview = "确认测试与发布安排。",
            sections =
                listOf(
                    AiSummarySection(
                        id = "section-1",
                        type = AiSummarySectionType.ACTION_ITEM,
                        label = "行动项",
                        items =
                            listOf(
                                AiSummaryItem(
                                    id = "item-1",
                                    text = "周四前完成测试。",
                                    evidenceRefs = listOf("S00001"),
                                    epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                                ),
                            ),
                    ),
                ),
        )

    private fun evidenceDraft(): OfflineSummaryEvidencePersistenceDraft =
        OfflineSummaryEvidencePersistenceDraft(
            summaryItemId = "item-1",
            sourceRef = "S00001",
            sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
            sourceId = SEGMENT_ID,
            startSampleIndex = 0L,
            endSampleIndexExclusive = 16_000L,
            speakerId = null,
            assignmentQuality = null,
        )

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "stage173-compat.wav",
                displayName = "stage173-compat",
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
                mode = TranscriptionModeValue.HIGH_QUALITY,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = CANONICAL_ASSET_ID,
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
                createdAtMs = 10L,
                startedAtMs = 10L,
                updatedAtMs = 20L,
                completedAtMs = 20L,
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "stage173-compat-recording"
        const val TRANSCRIPTION_ID = "stage173-compat-transcription"
        const val REVISION_ID = "stage173-compat-revision"
        const val ALIGNMENT_ID = "stage173-compat-alignment"
        const val CANONICAL_ASSET_ID = "stage173-compat-canonical"
        const val SEGMENT_ID = "stage173-compat-segment"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
