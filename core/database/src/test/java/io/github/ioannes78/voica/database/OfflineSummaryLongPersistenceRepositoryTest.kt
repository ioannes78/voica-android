package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.EvidenceSourceRef
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediate
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateCodec
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateItem
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateItemType
import io.github.ioannes78.voica.ai.OfflineSummaryLongChunk
import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlan
import io.github.ioannes78.voica.ai.OfflineSummaryLongSourceFragment
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineSummaryLongPersistenceRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var directRepository: OfflineSummaryPersistenceRepository
    private lateinit var longRepository: OfflineSummaryLongPersistenceRepository
    private var now = 10_000L
    private var summaryCounter = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        directRepository =
            OfflineSummaryPersistenceRepository(
                database = database,
                nowMs = { now++ },
                idFactory = { "long-summary-${++summaryCounter}" },
            )
        longRepository =
            OfflineSummaryLongPersistenceRepository(
                database = database,
                nowMs = { now++ },
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
    fun createPersistsFrozenContractAndLeafMapNodes() = runBlocking {
        val summary = createPreparedSummary()

        val task =
            longRepository.create(
                summaryId = summary.id,
                input = input(),
                modelIdentitySnapshot = MODEL_IDENTITY,
                plan = plan(),
                directPromptEstimatedTokens = 3_100,
            )

        assertEquals(OfflineSummaryTaskStateValue.PLANNING, task.status)
        assertEquals(OfflineSummaryLongContract.PIPELINE_VERSION, task.pipelineVersion)
        assertEquals(OfflineSummaryLongContract.PLANNER_VERSION, task.plannerVersion)
        assertEquals(OfflineSummaryLongContract.MAP_PROMPT_VERSION, task.mapPromptVersion)
        assertEquals(OfflineSummaryLongContract.REDUCE_PROMPT_VERSION, task.reducePromptVersion)
        assertEquals(OfflineSummaryLongContract.FINAL_PROMPT_VERSION, task.finalPromptVersion)
        assertEquals(OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION, task.intermediateSchemaVersion)
        assertEquals(2, task.totalLeafChunks)
        assertEquals(0, task.completedLeafChunks)
        assertEquals(AiSummaryStateValue.PLANNING, database.aiSummaryDao().findSummary(summary.id)?.status)

        val nodes = database.offlineSummaryLongDao().loadNodes(summary.id)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.nodeType == OfflineSummaryNodeTypeValue.MAP })
        assertTrue(nodes.all { it.status == OfflineSummaryNodeStateValue.PENDING })
        assertEquals(listOf(0, 1), nodes.map { it.ordinal })
        assertTrue(nodes.all { it.validatedPayloadJson == null })
    }

    @Test
    fun startupReconcileRetainsValidatedCheckpointAndExplicitResumeOnlyResetsInterruptedWork() = runBlocking {
        val summary = createLongTask()
        longRepository.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        val nodes = database.offlineSummaryLongDao().loadNodes(summary.id)

        longRepository.markNodeRunning(nodes[0].id)
        val validated =
            longRepository.persistValidatedIntermediate(
                nodes[0].id,
                intermediatePayload(sourceRef = "U00001", evidenceRef = "S00001", text = "第一段要点"),
            )
        longRepository.markNodeRunning(nodes[1].id)

        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, validated.status)
        assertNotNull(validated.validatedPayloadJson)
        assertEquals(1, database.offlineSummaryLongDao().findTask(summary.id)?.completedLeafChunks)

        assertEquals(1, longRepository.reconcileOnStartup())
        val interrupted = checkNotNull(longRepository.snapshot(summary.id))
        assertEquals(OfflineSummaryTaskStateValue.INTERRUPTED, interrupted.task.status)
        assertEquals(AiSummaryStateValue.INTERRUPTED, database.aiSummaryDao().findSummary(summary.id)?.status)
        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, interrupted.nodes[0].status)
        assertNotNull(interrupted.nodes[0].validatedPayloadJson)
        assertEquals(OfflineSummaryNodeStateValue.INTERRUPTED, interrupted.nodes[1].status)
        assertNull(interrupted.nodes[1].validatedPayloadJson)

        val resumed = longRepository.prepareResume(summary.id, input(), MODEL_IDENTITY)
        assertEquals(OfflineSummaryTaskStateValue.MAPPING, resumed.task.status)
        assertEquals(AiSummaryStateValue.MAPPING, database.aiSummaryDao().findSummary(summary.id)?.status)
        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, resumed.nodes[0].status)
        assertNotNull(resumed.nodes[0].validatedPayloadJson)
        assertEquals(OfflineSummaryNodeStateValue.PENDING, resumed.nodes[1].status)
        assertEquals(1, resumed.nodes[1].attemptCount)
    }

    @Test
    fun explicitResumeRejectsInputModelAndContractDriftWithoutMutatingInterruptedTask() = runBlocking {
        val summary = createLongTask()
        longRepository.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        assertEquals(1, longRepository.reconcileOnStartup())

        val changedInput = input(inputContentDigest = SHA_D)
        val inputFailure =
            runCatching { longRepository.prepareResume(summary.id, changedInput, MODEL_IDENTITY) }
                .exceptionOrNull() as OfflineSummaryLongPersistenceException
        assertEquals(OfflineSummaryLongPersistenceErrorCode.IDENTITY_MISMATCH, inputFailure.code)

        val modelFailure =
            runCatching { longRepository.prepareResume(summary.id, input(), MODEL_IDENTITY + ";changed=true") }
                .exceptionOrNull() as OfflineSummaryLongPersistenceException
        assertEquals(OfflineSummaryLongPersistenceErrorCode.IDENTITY_MISMATCH, modelFailure.code)
        assertEquals(OfflineSummaryTaskStateValue.INTERRUPTED, database.offlineSummaryLongDao().findTask(summary.id)?.status)

        val task = checkNotNull(database.offlineSummaryLongDao().findTask(summary.id))
        database.offlineSummaryLongDao().updateTask(task.copy(plannerVersion = task.plannerVersion + 1))
        val contractFailure =
            runCatching { longRepository.prepareResume(summary.id, input(), MODEL_IDENTITY) }
                .exceptionOrNull() as OfflineSummaryLongPersistenceException
        assertEquals(OfflineSummaryLongPersistenceErrorCode.CONTRACT_MISMATCH, contractFailure.code)
        assertEquals(OfflineSummaryTaskStateValue.INTERRUPTED, database.offlineSummaryLongDao().findTask(summary.id)?.status)
    }

    @Test
    fun failedTaskRetainsValidatedNodesAndResumeRetriesOnlyFailedNode() = runBlocking {
        val summary = createLongTask()
        longRepository.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        val nodes = database.offlineSummaryLongDao().loadNodes(summary.id)

        longRepository.markNodeRunning(nodes[0].id)
        longRepository.persistValidatedIntermediate(
            nodes[0].id,
            intermediatePayload(sourceRef = "U00001", evidenceRef = "S00001", text = "已完成检查点"),
        )
        longRepository.markNodeRunning(nodes[1].id)
        longRepository.markNodeFailed(nodes[1].id, "GENERATION_FAILED", "local generation failed")
        longRepository.fail(summary.id, io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.GENERATION_FAILED, "local generation failed")

        val failed = checkNotNull(longRepository.snapshot(summary.id))
        assertEquals(OfflineSummaryTaskStateValue.FAILED, failed.task.status)
        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, failed.nodes[0].status)
        assertEquals(OfflineSummaryNodeStateValue.FAILED, failed.nodes[1].status)
        assertNotNull(failed.nodes[0].validatedPayloadJson)

        val resumed = longRepository.prepareResume(summary.id, input(), MODEL_IDENTITY)
        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, resumed.nodes[0].status)
        assertEquals(OfflineSummaryNodeStateValue.PENDING, resumed.nodes[1].status)
        assertEquals(1, resumed.task.completedLeafChunks)
    }

    @Test
    fun cancelledTaskDeletesNodesAndCannotResume() = runBlocking {
        val summary = createLongTask()
        longRepository.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)

        val cancelled = longRepository.cancel(summary.id)

        assertEquals(OfflineSummaryTaskStateValue.CANCELLED, cancelled.status)
        assertEquals(AiSummaryStateValue.CANCELLED, database.aiSummaryDao().findSummary(summary.id)?.status)
        assertTrue(database.offlineSummaryLongDao().loadNodes(summary.id).isEmpty())
        val failure =
            runCatching { longRepository.prepareResume(summary.id, input(), MODEL_IDENTITY) }
                .exceptionOrNull() as OfflineSummaryLongPersistenceException
        assertEquals(OfflineSummaryLongPersistenceErrorCode.NOT_RESUMABLE, failure.code)
    }

    @Test
    fun completedSummaryCleanupDeletesNodesButRetainsTerminalTaskAuditRow() = runBlocking {
        val summary = createLongTask()
        val persistedSummary = checkNotNull(database.aiSummaryDao().findSummary(summary.id))
        database.aiSummaryDao().updateSummary(
            persistedSummary.copy(
                status = AiSummaryStateValue.COMPLETED,
                completedAtMs = now,
                updatedAtMs = now++,
            ),
        )

        val task = longRepository.markCompletedAndCleanup(summary.id)

        assertEquals(OfflineSummaryTaskStateValue.COMPLETED, task.status)
        assertEquals(task.totalLeafChunks, task.completedLeafChunks)
        assertTrue(database.offlineSummaryLongDao().loadNodes(summary.id).isEmpty())
        assertNotNull(database.offlineSummaryLongDao().findTask(summary.id))
    }

    @Test
    fun validatedCheckpointCodecRejectsRefsOutsideFrozenNodeAllowlist() = runBlocking {
        val summary = createLongTask()
        longRepository.markTaskPhase(summary.id, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        val node = database.offlineSummaryLongDao().loadNodes(summary.id).first()
        longRepository.markNodeRunning(node.id)

        assertTrue(
            runCatching {
                longRepository.persistValidatedIntermediate(
                    node.id,
                    intermediatePayload(sourceRef = "U00002", evidenceRef = "S00002", text = "错误引用"),
                )
            }.isFailure,
        )
        val persisted = checkNotNull(database.offlineSummaryLongDao().findNode(node.id))
        assertEquals(OfflineSummaryNodeStateValue.RUNNING, persisted.status)
        assertNull(persisted.validatedPayloadJson)
    }

    private suspend fun createPreparedSummary(): AiSummaryEntity {
        val summary = directRepository.create(input(), MODEL_IDENTITY)
        directRepository.markPreparing(summary.id)
        return checkNotNull(directRepository.find(summary.id))
    }

    private suspend fun createLongTask(): AiSummaryEntity {
        val summary = createPreparedSummary()
        longRepository.create(
            summaryId = summary.id,
            input = input(),
            modelIdentitySnapshot = MODEL_IDENTITY,
            plan = plan(),
            directPromptEstimatedTokens = 3_100,
        )
        return summary
    }

    private fun plan(): OfflineSummaryLongPlan =
        OfflineSummaryLongPlan(
            pipelineVersion = OfflineSummaryLongContract.PIPELINE_VERSION,
            plannerVersion = OfflineSummaryLongContract.PLANNER_VERSION,
            inputContentDigest = SHA_C,
            chunks =
                listOf(
                    chunk(
                        index = 0,
                        sourceOrdinal = 0,
                        sourceRef = "U00001",
                        evidenceRef = "S00001",
                        digest = SHA_A,
                        text = "第一段内容",
                    ),
                    chunk(
                        index = 1,
                        sourceOrdinal = 1,
                        sourceRef = "U00002",
                        evidenceRef = "S00002",
                        digest = SHA_B,
                        text = "第二段内容",
                    ),
                ),
        )

    private fun chunk(
        index: Int,
        sourceOrdinal: Int,
        sourceRef: String,
        evidenceRef: String,
        digest: String,
        text: String,
    ): OfflineSummaryLongChunk =
        OfflineSummaryLongChunk(
            index = index,
            sourceUnitStartOrdinal = sourceOrdinal,
            sourceUnitEndOrdinalExclusive = sourceOrdinal + 1,
            sourceDigest = digest,
            fragments =
                listOf(
                    OfflineSummaryLongSourceFragment(
                        sourceRef = sourceRef,
                        sourceUnitOrdinal = sourceOrdinal,
                        fragmentOrdinal = null,
                        text = text,
                        evidenceRef = evidenceRef,
                        speakerDisplayName = null,
                        detectedLanguage = "zh",
                    ),
                ),
            estimatedPromptTokens = 1_100,
        )

    private fun intermediatePayload(
        sourceRef: String,
        evidenceRef: String,
        text: String,
    ): String =
        OfflineSummaryIntermediateCodec.encode(
            OfflineSummaryIntermediate(
                schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                synopsis = text,
                items =
                    listOf(
                        OfflineSummaryIntermediateItem(
                            id = "i1",
                            type = OfflineSummaryIntermediateItemType.KEY_POINT,
                            text = text,
                            sourceRefs = listOf(sourceRef),
                            evidenceRefs = listOf(evidenceRef),
                            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                        ),
                    ),
            ),
        )

    private fun input(inputContentDigest: String = SHA_C): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = TRANSCRIPTION_ID,
            transcriptionRevisionId = REVISION_ID,
            inputContentDigest = inputContentDigest,
            transcriptionMode = TranscriptionModeValue.HIGH_QUALITY,
            canonicalAssetId = CANONICAL_ASSET_ID,
            canonicalSha256 = SHA_A,
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 32_000L,
            languageConfig = "auto",
            alignmentId = ALIGNMENT_ID,
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = "segment-1",
                                speakerId = null,
                                speakerDisplayName = null,
                                assignmentQuality = null,
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 0L,
                                endSampleIndexExclusive = 16_000L,
                            ),
                        text = "第一段内容",
                        detectedLanguage = "zh",
                    ),
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00002",
                                sourceKind = io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = "segment-2",
                                speakerId = null,
                                speakerDisplayName = null,
                                assignmentQuality = null,
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 16_000L,
                                endSampleIndexExclusive = 32_000L,
                            ),
                        text = "第二段内容",
                        detectedLanguage = "zh",
                    ),
                ),
        )

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "long-summary.wav",
                displayName = "long-summary",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 2_000L,
                mediaDurationMs = 2_000L,
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
                totalSampleCount = 32_000L,
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
        const val RECORDING_ID = "long-summary-recording"
        const val TRANSCRIPTION_ID = "long-summary-transcription"
        const val REVISION_ID = "long-summary-revision"
        const val ALIGNMENT_ID = "long-summary-alignment"
        const val CANONICAL_ASSET_ID = "canonical-long"
        const val MODEL_IDENTITY =
            "modelId=gemma-4-e2b-it-litertlm;modelRevision=7fa1d78473894f7e736a21d920c3aa80f950c0db:r1;artifact=gemma-4-E2B-it.litertlm;artifactSha256=ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42;runtimeId=litert-lm;runtimeVersion=0.18.0"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
