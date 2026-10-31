package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.EvidenceSourceRef
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediate
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateCodec
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateItem
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateItemType
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlan
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongNodeDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongResumeSnapshot
import io.github.ioannes78.voica.database.OfflineSummaryNodeEntity
import io.github.ioannes78.voica.database.OfflineSummaryNodeStateValue
import io.github.ioannes78.voica.database.OfflineSummaryNodeTypeValue
import io.github.ioannes78.voica.database.OfflineSummaryTaskEntity
import io.github.ioannes78.voica.database.OfflineSummaryTaskRouteValue
import io.github.ioannes78.voica.database.OfflineSummaryTaskStateValue
import io.github.ioannes78.voica.localllm.LiteRtLmRuntimeContract
import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmBackendResolution
import io.github.ioannes78.voica.localllm.LocalLlmDiagnostics
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import java.util.LinkedHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryLongRunnerTest {
    @Test
    fun serialMapThenFinalUsesSameAutoRuntimeBoundaryAndPersistsFinalResult() = runBlocking {
        val store = FakeLongStore()
        val prompts = mutableListOf<String>()
        val configs = mutableListOf<LocalLlmRuntimeConfig>()
        var mapIndex = 0
        val runner =
            OfflineSummaryLongRunner(
                store = store,
                generateLocal = { prompt, config ->
                    prompts += prompt
                    configs += config
                    when {
                        prompt.contains("MAP 引擎") -> {
                            mapIndex += 1
                            generation(intermediateJson(mapIndex))
                        }
                        prompt.contains("FINAL 引擎") -> generation(finalJson())
                        else -> error("unexpected prompt")
                    }
                },
            )

        val result =
            runner.executeNew(
                summaryId = SUMMARY_ID,
                input = longInput(),
                preparedIdentity = modelIdentity(),
                directPromptEstimatedTokens = 3_500,
                onProgress = {},
            )

        assertEquals(3, prompts.size)
        assertTrue(prompts[0].contains("MAP 引擎"))
        assertTrue(prompts[1].contains("MAP 引擎"))
        assertTrue(prompts[2].contains("FINAL 引擎"))
        assertTrue(configs.all { it.backendPolicy == LocalLlmBackendPolicy.AUTO })
        assertTrue(configs.all { it.maxNumTokens == 4_000 && it.maxOutputTokens == 1_024 })
        assertFalse(result.repairAttempted)
        assertNotNull(store.completedResult)
        assertEquals(listOf("S00001", "S00002"), store.completedEvidence.map { it.sourceRef }.sorted())
        assertEquals(2, store.initialLeafCount)
    }

    @Test
    fun resumeSkipsValidatedMapAndRetriesOnlyFailedMapBeforeFinal() = runBlocking {
        val store = FakeLongStore()
        val promptKinds = mutableListOf<String>()
        var failSecondMap = true
        var mapCallOrdinal = 0
        val runner =
            OfflineSummaryLongRunner(
                store = store,
                generateLocal = { prompt, _ ->
                    when {
                        prompt.contains("MAP 引擎") -> {
                            mapCallOrdinal += 1
                            val source = if (prompt.contains("U00001")) 1 else 2
                            promptKinds += "MAP$source"
                            if (source == 2 && failSecondMap) {
                                failSecondMap = false
                                error("synthetic map failure")
                            }
                            generation(intermediateJson(source))
                        }
                        prompt.contains("FINAL 引擎") -> {
                            promptKinds += "FINAL"
                            generation(finalJson())
                        }
                        else -> error("unexpected prompt")
                    }
                },
            )

        val firstFailure =
            runCatching {
                runner.executeNew(
                    summaryId = SUMMARY_ID,
                    input = longInput(),
                    preparedIdentity = modelIdentity(),
                    directPromptEstimatedTokens = 3_500,
                    onProgress = {},
                )
            }.exceptionOrNull()
        assertNotNull(firstFailure)
        assertEquals(OfflineSummaryNodeStateValue.VALIDATED, store.nodeByOrdinal(0)?.status)
        assertEquals(OfflineSummaryNodeStateValue.FAILED, store.nodeByOrdinal(1)?.status)
        store.fail(SUMMARY_ID, OfflineSummaryFailureCode.GENERATION_FAILED, "synthetic")

        assertEquals(OfflineSummaryRunPhase.ANALYZING, runner.prepareResume(SUMMARY_ID, longInput()))
        val result =
            runner.executeResume(
                summaryId = SUMMARY_ID,
                input = longInput(),
                preparedIdentity = modelIdentity(),
                onProgress = {},
            )

        assertEquals(listOf("MAP1", "MAP2", "MAP2", "FINAL"), promptKinds)
        assertFalse(result.repairAttempted)
        assertNotNull(store.completedResult)
    }

    @Test
    fun invalidIntermediateGetsExactlyOneBoundedRepair() = runBlocking {
        val store = FakeLongStore()
        var callCount = 0
        val runner =
            OfflineSummaryLongRunner(
                store = store,
                generateLocal = { prompt, _ ->
                    callCount += 1
                    when {
                        prompt.contains("MAP 引擎") && !prompt.contains("上一次输出未通过") ->
                            generation("""{"schemaVersion":1}""")
                        prompt.contains("MAP 引擎") -> generation(intermediateJson(if (prompt.contains("U00001")) 1 else 2))
                        prompt.contains("FINAL 引擎") -> generation(finalJson())
                        else -> error("unexpected prompt")
                    }
                },
            )

        val result =
            runner.executeNew(
                summaryId = SUMMARY_ID,
                input = longInput(),
                preparedIdentity = modelIdentity(),
                directPromptEstimatedTokens = 3_500,
                onProgress = {},
            )

        assertTrue(result.repairAttempted)
        assertEquals(5, callCount)
        assertNotNull(store.completedResult)
    }

    private class FakeLongStore : OfflineSummaryLongStateStore {
        private var task: OfflineSummaryTaskEntity? = null
        private val nodes = LinkedHashMap<String, OfflineSummaryNodeEntity>()
        var completedResult: AiSummaryResult? = null
        var completedEvidence: List<OfflineSummaryEvidencePersistenceDraft> = emptyList()
        var initialLeafCount: Int = 0

        override suspend fun create(
            summaryId: String,
            input: StructuredTranscriptInput,
            plan: OfflineSummaryLongPlan,
            directPromptEstimatedTokens: Int,
        ) {
            initialLeafCount = plan.chunks.size
            task =
                OfflineSummaryTaskEntity(
                    aiSummaryId = summaryId,
                    pipelineVersion = plan.pipelineVersion,
                    plannerVersion = plan.plannerVersion,
                    mapPromptVersion = 173101,
                    reducePromptVersion = 173201,
                    finalPromptVersion = 173301,
                    intermediateSchemaVersion = 1,
                    inputContentDigest = input.inputContentDigest,
                    route = OfflineSummaryTaskRouteValue.LONG,
                    directPromptEstimatedTokens = directPromptEstimatedTokens,
                    status = OfflineSummaryTaskStateValue.PLANNING,
                    currentLevel = 0,
                    totalLeafChunks = plan.chunks.size,
                    completedLeafChunks = 0,
                    createdAtMs = 1L,
                    updatedAtMs = 1L,
                )
            plan.chunks.forEach { chunk ->
                val id = "$summaryId:map:${(chunk.index + 1).toString().padStart(5, '0')}"
                nodes[id] =
                    OfflineSummaryNodeEntity(
                        id = id,
                        aiSummaryId = summaryId,
                        nodeType = OfflineSummaryNodeTypeValue.MAP,
                        level = 0,
                        ordinal = chunk.index,
                        sourceStartUnitOrdinal = chunk.sourceUnitStartOrdinal,
                        sourceEndUnitOrdinalExclusive = chunk.sourceUnitEndOrdinalExclusive,
                        inputNodeIdsJson = "[]",
                        sourceRefsJson = jsonArray(chunk.allowedSourceRefs),
                        evidenceRefsJson = jsonArray(chunk.allowedEvidenceRefs),
                        inputDigest = chunk.sourceDigest,
                        estimatedPromptTokens = chunk.estimatedPromptTokens,
                        status = OfflineSummaryNodeStateValue.PENDING,
                        attemptCount = 0,
                        validatedPayloadJson = null,
                        errorCode = null,
                        sanitizedErrorMessage = null,
                        createdAtMs = 1L,
                        updatedAtMs = 1L,
                        completedAtMs = null,
                    )
            }
        }

        override suspend fun markTaskPhase(summaryId: String, status: String, currentLevel: Int) {
            task = checkNotNull(task).copy(status = status, currentLevel = currentLevel, updatedAtMs = 2L)
        }

        override suspend fun snapshot(summaryId: String): OfflineSummaryLongResumeSnapshot? =
            task?.let { OfflineSummaryLongResumeSnapshot(it, nodes.values.toList()) }

        override suspend fun insertNodes(summaryId: String, drafts: List<OfflineSummaryLongNodeDraft>) {
            drafts.forEach { draft ->
                nodes[draft.id] =
                    OfflineSummaryNodeEntity(
                        id = draft.id,
                        aiSummaryId = summaryId,
                        nodeType = draft.nodeType.name,
                        level = draft.level,
                        ordinal = draft.ordinal,
                        sourceStartUnitOrdinal = draft.sourceStartUnitOrdinal,
                        sourceEndUnitOrdinalExclusive = draft.sourceEndUnitOrdinalExclusive,
                        inputNodeIdsJson = jsonArray(draft.inputNodeIds),
                        sourceRefsJson = jsonArray(draft.sourceRefs),
                        evidenceRefsJson = jsonArray(draft.evidenceRefs),
                        inputDigest = draft.inputDigest,
                        estimatedPromptTokens = draft.estimatedPromptTokens,
                        status = OfflineSummaryNodeStateValue.PENDING,
                        attemptCount = 0,
                        validatedPayloadJson = null,
                        errorCode = null,
                        sanitizedErrorMessage = null,
                        createdAtMs = 2L,
                        updatedAtMs = 2L,
                        completedAtMs = null,
                    )
            }
        }

        override suspend fun markNodeRunning(nodeId: String) {
            val node = checkNotNull(nodes[nodeId])
            nodes[nodeId] =
                node.copy(
                    status = OfflineSummaryNodeStateValue.RUNNING,
                    attemptCount = node.attemptCount + 1,
                    errorCode = null,
                    sanitizedErrorMessage = null,
                )
        }

        override suspend fun checkpointIntermediate(nodeId: String, rawPayload: String) {
            val node = checkNotNull(nodes[nodeId])
            nodes[nodeId] =
                node.copy(
                    status = OfflineSummaryNodeStateValue.VALIDATED,
                    validatedPayloadJson = rawPayload,
                    completedAtMs = 3L,
                )
            if (node.nodeType == OfflineSummaryNodeTypeValue.MAP) {
                val current = checkNotNull(task)
                task = current.copy(completedLeafChunks = current.completedLeafChunks + 1)
            }
        }

        override suspend fun markNodeFailed(nodeId: String, code: String, message: String?) {
            val node = checkNotNull(nodes[nodeId])
            nodes[nodeId] =
                node.copy(
                    status = OfflineSummaryNodeStateValue.FAILED,
                    validatedPayloadJson = null,
                    errorCode = code,
                    sanitizedErrorMessage = message,
                )
        }

        override suspend fun prepareResume(
            summaryId: String,
            input: StructuredTranscriptInput,
        ): OfflineSummaryLongResumeSnapshot {
            nodes.replaceAll { _, node ->
                if (node.status == OfflineSummaryNodeStateValue.FAILED || node.status == OfflineSummaryNodeStateValue.INTERRUPTED) {
                    node.copy(
                        status = OfflineSummaryNodeStateValue.PENDING,
                        validatedPayloadJson = null,
                        errorCode = null,
                        sanitizedErrorMessage = null,
                    )
                } else {
                    node
                }
            }
            val current = checkNotNull(task)
            task = current.copy(status = OfflineSummaryTaskStateValue.MAPPING)
            return checkNotNull(snapshot(summaryId))
        }

        override suspend fun complete(
            summaryId: String,
            result: AiSummaryResult,
            evidence: List<OfflineSummaryEvidencePersistenceDraft>,
        ): String? {
            completedResult = result
            completedEvidence = evidence
            task = checkNotNull(task).copy(status = OfflineSummaryTaskStateValue.COMPLETED)
            return null
        }

        override suspend fun fail(
            summaryId: String,
            code: OfflineSummaryFailureCode,
            message: String?,
        ): Boolean {
            task = task?.copy(status = OfflineSummaryTaskStateValue.FAILED)
            return task != null
        }

        override suspend fun cancel(summaryId: String): Boolean {
            task = task?.copy(status = OfflineSummaryTaskStateValue.CANCELLED)
            nodes.clear()
            return task != null
        }

        override suspend fun reconcileOnStartup(): Int = 0

        fun nodeByOrdinal(ordinal: Int): OfflineSummaryNodeEntity? =
            nodes.values.firstOrNull { it.level == 0 && it.ordinal == ordinal }

        private fun jsonArray(values: Collection<String>): String =
            values.joinToString(prefix = "[", postfix = "]") { value -> "\"$value\"" }
    }

    private fun longInput(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = "transcription-long",
            transcriptionRevisionId = "revision-long",
            inputContentDigest = "c".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-long",
            canonicalSha256 = "a".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 32_000L,
            languageConfig = "zh",
            alignmentId = "alignment-long",
            units =
                listOf(
                    unit(1, 0L, 16_000L, "甲".repeat(1_200)),
                    unit(2, 16_000L, 32_000L, "乙".repeat(1_200)),
                ),
        )

    private fun unit(index: Int, start: Long, end: Long, text: String): StructuredTranscriptUnit =
        StructuredTranscriptUnit(
            evidence =
                EvidenceSourceRef(
                    ref = "S" + index.toString().padStart(5, '0'),
                    sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                    sourceId = "segment-$index",
                    speakerId = null,
                    speakerDisplayName = null,
                    assignmentQuality = null,
                    overlap = false,
                    ambiguous = false,
                    startSampleIndex = start,
                    endSampleIndexExclusive = end,
                ),
            text = text,
            detectedLanguage = "zh",
        )

    private fun intermediateJson(index: Int): String =
        OfflineSummaryIntermediateCodec.encode(
            OfflineSummaryIntermediate(
                schemaVersion = 1,
                synopsis = "分段 $index 摘要",
                items =
                    listOf(
                        OfflineSummaryIntermediateItem(
                            id = "i$index",
                            type = OfflineSummaryIntermediateItemType.KEY_POINT,
                            text = "分段 $index 要点",
                            sourceRefs = listOf("U" + index.toString().padStart(5, '0')),
                            evidenceRefs = listOf("S" + index.toString().padStart(5, '0')),
                            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                        ),
                    ),
            ),
        )

    private fun finalJson(): String =
        """
        {
          "schemaVersion":1,
          "contentType":"MEETING",
          "classificationConfidence":0.9,
          "title":"长文本会议",
          "overview":"包含两个分段的主要信息。",
          "sections":[
            {
              "id":"section-1",
              "type":"SUMMARY",
              "label":"总结",
              "items":[
                {
                  "id":"item-1",
                  "text":"两个分段均已纳入总结。",
                  "evidenceRefs":["S00001","S00002"],
                  "epistemicStatus":"TRANSCRIPT_STATED",
                  "attributes":{}
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private fun modelIdentity(): LocalLlmModelIdentity =
        LocalLlmModelIdentity(
            modelId = Stage172OfflineSummaryModelContract.MODEL_ID,
            modelFamily = "Gemma 4 E2B",
            artifact = Stage172OfflineSummaryModelContract.ARTIFACT,
            revision = Stage172OfflineSummaryModelContract.MODEL_REVISION,
            sha256 = Stage172OfflineSummaryModelContract.ARTIFACT_SHA256,
            runtimeId = LiteRtLmRuntimeContract.RUNTIME_ID,
            runtimeVersion = LiteRtLmRuntimeContract.RUNTIME_VERSION,
        )

    private fun generation(text: String): LocalLlmGenerationResult {
        val identity = modelIdentity()
        val resolution =
            LocalLlmBackendResolution(
                requestedPolicy = LocalLlmBackendPolicy.AUTO,
                resolvedBackend = LocalLlmBackend.CPU,
                reason = "TEST_CPU",
                fallbackAttempted = false,
            )
        return LocalLlmGenerationResult(
            text = text,
            modelIdentity = identity,
            backendResolution = resolution,
            diagnostics =
                LocalLlmDiagnostics(
                    runtimeVersion = LiteRtLmRuntimeContract.RUNTIME_VERSION,
                    modelIdentity = identity,
                    backendResolution = resolution,
                ),
        )
    }

    private companion object {
        const val RECORDING_ID = "recording-long"
        const val SUMMARY_ID = "summary-long"
    }
}
