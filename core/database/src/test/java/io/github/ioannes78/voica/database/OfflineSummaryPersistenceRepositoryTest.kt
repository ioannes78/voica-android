package io.github.ioannes78.voica.database

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
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineSummaryPersistenceRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: OfflineSummaryPersistenceRepository
    private var now = 1_000L
    private var idCounter = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            OfflineSummaryPersistenceRepository(
                database = database,
                nowMs = { now++ },
                idFactory = { "offline-id-${++idCounter}" },
            )
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
    fun createTransitionsAndCompletionPersistImmutableLineageAndEvidence() = runBlocking {
        val created = repository.create(input(), MODEL_IDENTITY)

        assertEquals(AiSummaryStateValue.CREATED, created.status)
        assertEquals(AiSummaryInputModeValue.TRANSCRIPT_TEXT, created.inputMode)
        assertEquals(AiSummaryModeValue.SMART, created.mode)
        assertEquals(OfflineSummaryPromptContract.PROMPT_VERSION, created.promptVersion)
        assertEquals(1, created.resultSchemaVersion)
        assertEquals(MODEL_IDENTITY, created.model)
        assertEquals(ALIGNMENT_ID, created.alignmentIdSnapshot)
        assertTrue(created.sourceLineageSnapshot.contains("\"lineageVersion\":3"))
        assertTrue(created.sourceLineageSnapshot.contains("\"schemaVersion\":1"))
        assertTrue(created.sourceLineageSnapshot.contains("\"source\":\"LOCAL_OFFLINE_SUMMARY\""))
        assertTrue(created.sourceLineageSnapshot.contains("\"recordingId\":\"$RECORDING_ID\""))
        assertTrue(created.sourceLineageSnapshot.contains("\"transcriptionId\":\"$TRANSCRIPTION_ID\""))
        assertTrue(created.sourceLineageSnapshot.contains("\"transcriptionRevisionId\":\"$REVISION_ID\""))
        assertTrue(created.sourceLineageSnapshot.contains("\"inputContentDigest\":\"${"c".repeat(64)}\""))
        assertTrue(created.sourceLineageSnapshot.contains("\"canonicalSha256\":\"$SHA_A\""))

        val preparing = repository.markPreparing(created.id)
        val analyzing = repository.markAnalyzing(created.id)
        val validating = repository.markValidating(created.id)
        assertEquals(AiSummaryStateValue.PREPARING, preparing.status)
        assertTrue(preparing.startedAtMs != null)
        assertEquals(AiSummaryStateValue.ANALYZING, analyzing.status)
        assertEquals(AiSummaryStateValue.VALIDATING, validating.status)

        val result = result()
        val completed =
            repository.complete(
                summaryId = created.id,
                result = result,
                evidence = listOf(evidenceDraft()),
            )

        assertEquals(AiSummaryStateValue.COMPLETED, completed.status)
        assertEquals(AiContentType.MEETING.name, completed.contentType)
        assertEquals(0.92, completed.classificationConfidence ?: 0.0, 0.0001)
        assertTrue(completed.displayText!!.contains("项目周会"))
        assertTrue(completed.displayText!!.contains("周四前完成测试"))
        assertTrue(completed.completedAtMs != null)
        assertNull(completed.errorCode)
        assertNull(completed.sanitizedErrorMessage)

        val persistedResult =
            SummaryResultCodec.decode(
                raw = checkNotNull(completed.structuredPayloadJson),
                allowedEvidenceRefs = setOf("S00001"),
            )
        assertEquals(result, persistedResult)

        val evidence = database.aiSummaryDao().loadEvidence(created.id)
        assertEquals(1, evidence.size)
        assertEquals(created.id, evidence.single().aiSummaryId)
        assertEquals("item-1", evidence.single().summaryItemId)
        assertEquals("S00001", evidence.single().sourceRef)
        assertEquals(TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT.name, evidence.single().sourceKind)
        assertEquals(0L, evidence.single().startSampleIndex)
        assertEquals(16_000L, evidence.single().endSampleIndexExclusive)
    }

    @Test
    fun regenerationCreatesNewSummaryAndExistingCompletedRowRemainsImmutable() = runBlocking {
        val first = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(first.id)
        repository.markAnalyzing(first.id)
        repository.markValidating(first.id)
        val completedFirst = repository.complete(first.id, result(), listOf(evidenceDraft()))
        val firstSnapshot = repository.find(first.id)

        val second = repository.create(input(), MODEL_IDENTITY)

        assertNotEquals(first.id, second.id)
        assertEquals(AiSummaryStateValue.CREATED, second.status)
        assertEquals(completedFirst, firstSnapshot)
        assertEquals(AiSummaryStateValue.COMPLETED, repository.find(first.id)?.status)
    }

    @Test
    fun firstCompletedSummaryBecomesCurrentAndLaterCompletionRemainsCandidate() = runBlocking {
        val contentRepository = Stage12CContentRepository(database, nowMs = { now++ })
        val first = completeNewSummary()
        val firstDecision = contentRepository.onAiSummaryCompleted(RECORDING_ID, first.id)

        val second = completeNewSummary()
        val secondDecision = contentRepository.onAiSummaryCompleted(RECORDING_ID, second.id)

        assertTrue(firstDecision.adopted)
        assertEquals(first.id, firstDecision.currentId)
        assertFalse(secondDecision.adopted)
        assertEquals(first.id, secondDecision.currentId)
        assertEquals(second.id, secondDecision.candidateId)
        assertEquals(second.id, contentRepository.resolveAiSummaryCandidateId(RECORDING_ID))
    }

    @Test
    fun failCancelAndInterruptUseOnlyStage172ActiveStates() = runBlocking {
        val failed = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(failed.id)
        val failedTerminal =
            repository.fail(
                failed.id,
                OfflineSummaryFailureCode.GENERATION_FAILED,
                "  native\n  failure  details  ",
            )
        assertEquals(AiSummaryStateValue.FAILED, failedTerminal.status)
        assertEquals(OfflineSummaryFailureCode.GENERATION_FAILED.name, failedTerminal.errorCode)
        assertEquals("native failure details", failedTerminal.sanitizedErrorMessage)

        val cancelled = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(cancelled.id)
        val cancelledTerminal = repository.cancel(cancelled.id)
        assertEquals(AiSummaryStateValue.CANCELLED, cancelledTerminal.status)
        assertEquals(OfflineSummaryFailureCode.GENERATION_CANCELLED.name, cancelledTerminal.errorCode)

        val interrupted = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(interrupted.id)
        repository.markAnalyzing(interrupted.id)
        val interruptedTerminal = repository.interrupt(interrupted.id)
        assertEquals(AiSummaryStateValue.INTERRUPTED, interruptedTerminal.status)
        assertEquals(OfflineSummaryFailureCode.INTERRUPTED.name, interruptedTerminal.errorCode)
    }

    @Test
    fun startupReconciliationOnlyInterruptsStage172Rows() = runBlocking {
        val created = repository.create(input(), MODEL_IDENTITY)
        val analyzing = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(analyzing.id)
        repository.markAnalyzing(analyzing.id)
        insertHistoricalActiveSummary()

        val activeBefore = repository.loadActive().map { it.id }.toSet()
        val reconciled = repository.interruptAllActive()

        assertEquals(setOf(created.id, analyzing.id), activeBefore)
        assertEquals(setOf(created.id, analyzing.id), reconciled.map { it.id }.toSet())
        assertTrue(reconciled.all { it.status == AiSummaryStateValue.INTERRUPTED })
        assertEquals(
            AiSummaryStateValue.ANALYZING,
            database.aiSummaryDao().findSummary(HISTORICAL_SUMMARY_ID)?.status,
        )
    }

    @Test
    fun invalidLifecycleTransitionAndIncompleteEvidenceAreRejected() = runBlocking {
        val created = repository.create(input(), MODEL_IDENTITY)
        assertTrue(runCatching { repository.markAnalyzing(created.id) }.isFailure)

        repository.markPreparing(created.id)
        repository.markAnalyzing(created.id)
        repository.markValidating(created.id)
        assertTrue(runCatching { repository.complete(created.id, result(), emptyList()) }.isFailure)
        assertEquals(AiSummaryStateValue.VALIDATING, repository.find(created.id)?.status)
        assertTrue(database.aiSummaryDao().loadEvidence(created.id).isEmpty())
    }

    private suspend fun completeNewSummary(): AiSummaryEntity {
        val created = repository.create(input(), MODEL_IDENTITY)
        repository.markPreparing(created.id)
        repository.markAnalyzing(created.id)
        repository.markValidating(created.id)
        return repository.complete(created.id, result(), listOf(evidenceDraft()))
    }

    private fun input(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = TRANSCRIPTION_ID,
            transcriptionRevisionId = REVISION_ID,
            inputContentDigest = "c".repeat(64),
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

    private fun result(): AiSummaryResult =
        AiSummaryResult(
            schemaVersion = 1,
            contentType = AiContentType.MEETING,
            classificationConfidence = 0.92,
            title = "项目周会",
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

    private fun evidenceDraft() =
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
                originalFilename = "offline-summary.wav",
                displayName = "offline-summary",
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

    private suspend fun insertHistoricalActiveSummary() {
        database.aiSummaryDao().insertSummary(
            AiSummaryEntity(
                id = HISTORICAL_SUMMARY_ID,
                recordingId = RECORDING_ID,
                transcriptionId = TRANSCRIPTION_ID,
                inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
                mode = AiSummaryModeValue.SMART,
                model = "historical-provider-model",
                promptVersion = 1,
                resultSchemaVersion = 1,
                contentType = null,
                classificationConfidence = null,
                structuredPayloadJson = null,
                displayText = null,
                status = AiSummaryStateValue.ANALYZING,
                createdAtMs = 1L,
                startedAtMs = 1L,
                updatedAtMs = 1L,
                completedAtMs = null,
                errorCode = null,
                sanitizedErrorMessage = null,
                alignmentIdSnapshot = null,
                sourceLineageSnapshot =
                    """{"lineageVersion":2,"transcriptionId":"$TRANSCRIPTION_ID","transcriptionRevisionId":null}""",
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "offline-summary-recording"
        const val TRANSCRIPTION_ID = "offline-summary-transcription"
        const val REVISION_ID = "offline-summary-revision"
        const val ALIGNMENT_ID = "offline-summary-alignment"
        const val CANONICAL_ASSET_ID = "canonical"
        const val SEGMENT_ID = "segment-1"
        const val HISTORICAL_SUMMARY_ID = "historical-active-summary"
        const val MODEL_IDENTITY =
            "local:gemma-4-e2b-it-litertlm@1:r1#ab7838cd/runtime=litert-lm:0.18.0"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
