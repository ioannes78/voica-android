package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.ai.OfflineSummaryChunkPlanner
import io.github.ioannes78.voica.ai.OfflineSummaryFinalPromptRenderer
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateCodec
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateException
import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import io.github.ioannes78.voica.ai.OfflineSummaryLongNodeType
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlan
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlanningException
import io.github.ioannes78.voica.ai.OfflineSummaryMapPromptRenderer
import io.github.ioannes78.voica.ai.OfflineSummaryReduceGroup
import io.github.ioannes78.voica.ai.OfflineSummaryReducePromptRenderer
import io.github.ioannes78.voica.ai.OfflineSummaryReductionPlanner
import io.github.ioannes78.voica.ai.OfflineSummaryValidatedIntermediateNode
import io.github.ioannes78.voica.ai.RenderedOfflineSummaryLongPrompt
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.database.OfflineSummaryEvidenceMapper
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongNodeDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceErrorCode
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceException
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceRepository
import io.github.ioannes78.voica.database.OfflineSummaryLongResumeSnapshot
import io.github.ioannes78.voica.database.OfflineSummaryNodeEntity
import io.github.ioannes78.voica.database.OfflineSummaryNodeStateValue
import io.github.ioannes78.voica.database.OfflineSummaryNodeTypeValue
import io.github.ioannes78.voica.database.OfflineSummaryPersistenceRepository
import io.github.ioannes78.voica.database.OfflineSummaryTaskStateValue
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class OfflineSummaryLongProgressUpdate(
    val phase: OfflineSummaryRunPhase,
    val message: String? = null,
)

data class OfflineSummaryLongRunResult(
    val finalGeneration: LocalLlmGenerationResult,
    val repairAttempted: Boolean,
    val warning: String? = null,
)

internal interface OfflineSummaryLongRunnerPort {
    suspend fun executeNew(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        directPromptEstimatedTokens: Int,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult

    suspend fun prepareResume(
        summaryId: String,
        input: StructuredTranscriptInput,
    ): OfflineSummaryRunPhase

    suspend fun executeResume(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult

    suspend fun fail(
        summaryId: String,
        code: io.github.ioannes78.voica.ai.OfflineSummaryFailureCode,
        message: String?,
    ): Boolean

    suspend fun cancel(summaryId: String): Boolean

    suspend fun reconcileOnStartup(): Int
}

internal interface OfflineSummaryLongStateStore {
    suspend fun create(
        summaryId: String,
        input: StructuredTranscriptInput,
        plan: OfflineSummaryLongPlan,
        directPromptEstimatedTokens: Int,
    )

    suspend fun markTaskPhase(
        summaryId: String,
        status: String,
        currentLevel: Int,
    )

    suspend fun snapshot(summaryId: String): OfflineSummaryLongResumeSnapshot?

    suspend fun insertNodes(
        summaryId: String,
        drafts: List<OfflineSummaryLongNodeDraft>,
    )

    suspend fun markNodeRunning(nodeId: String)

    suspend fun checkpointIntermediate(
        nodeId: String,
        rawPayload: String,
    )

    suspend fun markNodeFailed(
        nodeId: String,
        code: String,
        message: String?,
    )

    suspend fun prepareResume(
        summaryId: String,
        input: StructuredTranscriptInput,
    ): OfflineSummaryLongResumeSnapshot

    suspend fun complete(
        summaryId: String,
        result: AiSummaryResult,
        evidence: List<OfflineSummaryEvidencePersistenceDraft>,
    ): String?

    suspend fun fail(
        summaryId: String,
        code: io.github.ioannes78.voica.ai.OfflineSummaryFailureCode,
        message: String?,
    ): Boolean

    suspend fun cancel(summaryId: String): Boolean

    suspend fun reconcileOnStartup(): Int
}

internal class RoomOfflineSummaryLongStateStore(
    private val longPersistence: OfflineSummaryLongPersistenceRepository,
    private val directPersistence: OfflineSummaryPersistenceRepository,
) : OfflineSummaryLongStateStore {
    override suspend fun create(
        summaryId: String,
        input: StructuredTranscriptInput,
        plan: OfflineSummaryLongPlan,
        directPromptEstimatedTokens: Int,
    ) {
        longPersistence.create(
            summaryId = summaryId,
            input = input,
            modelIdentitySnapshot = Stage172OfflineSummaryModelContract.persistedIdentitySnapshot,
            plan = plan,
            directPromptEstimatedTokens = directPromptEstimatedTokens,
        )
    }

    override suspend fun markTaskPhase(summaryId: String, status: String, currentLevel: Int) {
        longPersistence.markTaskPhase(summaryId, status, currentLevel)
    }

    override suspend fun snapshot(summaryId: String): OfflineSummaryLongResumeSnapshot? =
        longPersistence.snapshot(summaryId)

    override suspend fun insertNodes(summaryId: String, drafts: List<OfflineSummaryLongNodeDraft>) {
        longPersistence.insertPlannedNodes(summaryId, drafts)
    }

    override suspend fun markNodeRunning(nodeId: String) {
        longPersistence.markNodeRunning(nodeId)
    }

    override suspend fun checkpointIntermediate(nodeId: String, rawPayload: String) {
        longPersistence.persistValidatedIntermediate(nodeId, rawPayload)
    }

    override suspend fun markNodeFailed(nodeId: String, code: String, message: String?) {
        longPersistence.markNodeFailed(nodeId, code, message)
    }

    override suspend fun prepareResume(
        summaryId: String,
        input: StructuredTranscriptInput,
    ): OfflineSummaryLongResumeSnapshot =
        longPersistence.prepareResume(
            summaryId = summaryId,
            input = input,
            modelIdentitySnapshot = Stage172OfflineSummaryModelContract.persistedIdentitySnapshot,
        )

    override suspend fun complete(
        summaryId: String,
        result: AiSummaryResult,
        evidence: List<OfflineSummaryEvidencePersistenceDraft>,
    ): String? {
        directPersistence.complete(summaryId, result, evidence)
        return runCatching { longPersistence.markCompletedAndCleanup(summaryId) }
            .exceptionOrNull()
            ?.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(180)
    }

    override suspend fun fail(
        summaryId: String,
        code: io.github.ioannes78.voica.ai.OfflineSummaryFailureCode,
        message: String?,
    ): Boolean {
        if (longPersistence.snapshot(summaryId) == null) return false
        return runCatching {
            longPersistence.fail(summaryId, code, message)
            true
        }.getOrDefault(false)
    }

    override suspend fun cancel(summaryId: String): Boolean {
        if (longPersistence.snapshot(summaryId) == null) return false
        return runCatching {
            longPersistence.cancel(summaryId)
            true
        }.getOrDefault(false)
    }

    override suspend fun reconcileOnStartup(): Int = longPersistence.reconcileOnStartup()
}

/**
 * Serial Stage 17.3 long-summary runner. It owns no Engine/Conversation; every native request is
 * delegated to the same generateLocal function supplied by the application-scoped coordinator.
 */
internal class OfflineSummaryLongRunner(
    private val store: OfflineSummaryLongStateStore,
    private val generateLocal: suspend (String, io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig) -> LocalLlmGenerationResult,
    private val chunkPlanner: OfflineSummaryChunkPlanner = OfflineSummaryChunkPlanner(),
    private val reductionPlanner: OfflineSummaryReductionPlanner = OfflineSummaryReductionPlanner(),
    private val mapRenderer: OfflineSummaryMapPromptRenderer = OfflineSummaryMapPromptRenderer(),
    private val reduceRenderer: OfflineSummaryReducePromptRenderer = OfflineSummaryReducePromptRenderer(),
    private val finalRenderer: OfflineSummaryFinalPromptRenderer = OfflineSummaryFinalPromptRenderer(),
) : OfflineSummaryLongRunnerPort {
    override suspend fun executeNew(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        directPromptEstimatedTokens: Int,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult {
        onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.ANALYZING, "正在规划长文本分段"))
        val plan = chunkPlanner.plan(input)
        store.create(summaryId, input, plan, directPromptEstimatedTokens)
        return executePlanned(summaryId, input, preparedIdentity, plan, onProgress)
    }

    override suspend fun prepareResume(
        summaryId: String,
        input: StructuredTranscriptInput,
    ): OfflineSummaryRunPhase {
        val snapshot =
            try {
                store.prepareResume(summaryId, input)
            } catch (error: OfflineSummaryLongPersistenceException) {
                throw resumeException(error)
            }
        val plan = chunkPlanner.plan(input)
        try {
            requireFrozenMapPlan(snapshot, plan)
        } catch (error: Throwable) {
            runCatching {
                store.fail(
                    summaryId,
                    io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID,
                    error.message,
                )
            }
            throw error
        }
        return OfflineSummaryRunPhase.ANALYZING
    }

    override suspend fun executeResume(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult {
        val plan = chunkPlanner.plan(input)
        val snapshot = store.snapshot(summaryId)
            ?: throw OfflineSummaryCoordinatorException(
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                "长文本总结检查点不存在",
            )
        requireFrozenMapPlan(snapshot, plan)
        return executePlanned(summaryId, input, preparedIdentity, plan, onProgress)
    }

    override suspend fun fail(
        summaryId: String,
        code: io.github.ioannes78.voica.ai.OfflineSummaryFailureCode,
        message: String?,
    ): Boolean = store.fail(summaryId, code, message)

    override suspend fun cancel(summaryId: String): Boolean = store.cancel(summaryId)

    override suspend fun reconcileOnStartup(): Int = store.reconcileOnStartup()

    private suspend fun executePlanned(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        plan: OfflineSummaryLongPlan,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult {
        var anyRepair = false

        store.markTaskPhase(summaryId, OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0)
        var snapshot = requireSnapshot(summaryId)
        requireFrozenMapPlan(snapshot, plan)
        val mapNodes = snapshot.nodes.filter { it.nodeType == OfflineSummaryNodeTypeValue.MAP }.sortedBy { it.ordinal }
        mapNodes.forEachIndexed { index, node ->
            if (node.status == OfflineSummaryNodeStateValue.VALIDATED) return@forEachIndexed
            requirePending(node)
            val chunk = plan.chunks[index]
            onProgress(
                OfflineSummaryLongProgressUpdate(
                    OfflineSummaryRunPhase.ANALYZING,
                    "正在分析分段 ${index + 1}/${plan.chunks.size}",
                ),
            )
            store.markNodeRunning(node.id)
            try {
                val generated =
                    generateValidated(
                        prompt = mapRenderer.render(chunk.fragments),
                        preparedIdentity = preparedIdentity,
                        finalResult = false,
                    )
                anyRepair = anyRepair || generated.repairAttempted
                store.checkpointIntermediate(node.id, generated.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                throw error
            }
        }

        snapshot = requireSnapshot(summaryId)
        var current = validatedIntermediateNodes(snapshot, level = 0)
        if (current.size != plan.chunks.size) invalidCheckpoint("长文本 MAP 检查点不完整")

        var level = 1
        while (true) {
            snapshot = requireSnapshot(summaryId)
            val existingAtLevel = snapshot.nodes.filter { it.level == level }.sortedBy { it.ordinal }
            val finalPrompt = finalRenderer.render(current)
            val finalEstimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(finalPrompt.text)

            if (existingAtLevel.any { it.nodeType == OfflineSummaryNodeTypeValue.FINAL }) {
                val expectedFinal = finalDraft(summaryId, level, current, finalEstimate)
                requireExistingLevelMatches(existingAtLevel, listOf(expectedFinal))
                return runFinal(
                    summaryId = summaryId,
                    input = input,
                    preparedIdentity = preparedIdentity,
                    current = current,
                    finalNode = existingAtLevel.single(),
                    inheritedRepairAttempted = anyRepair,
                    onProgress = onProgress,
                )
            }

            val shouldFinalize = finalEstimate <= OfflineSummaryLongContract.PLANNER_TARGET_ESTIMATED_PROMPT_TOKENS
            if (existingAtLevel.isEmpty() && shouldFinalize) {
                val finalDraft = finalDraft(summaryId, level, current, finalEstimate)
                store.insertNodes(summaryId, listOf(finalDraft))
                val finalNode = requireSnapshot(summaryId).nodes.single { it.id == finalDraft.id }
                return runFinal(
                    summaryId = summaryId,
                    input = input,
                    preparedIdentity = preparedIdentity,
                    current = current,
                    finalNode = finalNode,
                    inheritedRepairAttempted = anyRepair,
                    onProgress = onProgress,
                )
            }

            val groups = reductionPlanner.group(current)
            if (groups.size >= current.size && current.size > 1) {
                throw OfflineSummaryLongPlanningException("long-summary reduce tree cannot make deterministic progress")
            }
            val expectedDrafts = groups.map { group -> reduceDraft(summaryId, level, group) }
            if (existingAtLevel.isEmpty()) {
                store.insertNodes(summaryId, expectedDrafts)
            } else {
                requireExistingLevelMatches(existingAtLevel, expectedDrafts)
            }

            store.markTaskPhase(summaryId, OfflineSummaryTaskStateValue.REDUCING, currentLevel = level)
            val levelNodes = requireSnapshot(summaryId).nodes.filter { it.level == level }.sortedBy { it.ordinal }
            levelNodes.forEachIndexed { index, node ->
                if (node.status == OfflineSummaryNodeStateValue.VALIDATED) return@forEachIndexed
                requirePending(node)
                val group = groups[index]
                onProgress(
                    OfflineSummaryLongProgressUpdate(
                        OfflineSummaryRunPhase.ANALYZING,
                        "正在合并第 $level 层 ${index + 1}/${groups.size}",
                    ),
                )
                store.markNodeRunning(node.id)
                try {
                    val generated =
                        generateValidated(
                            prompt = reduceRenderer.render(group.nodes),
                            preparedIdentity = preparedIdentity,
                            finalResult = false,
                        )
                    anyRepair = anyRepair || generated.repairAttempted
                    store.checkpointIntermediate(node.id, generated.text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                    throw error
                }
            }
            current = validatedIntermediateNodes(requireSnapshot(summaryId), level)
            if (current.size != groups.size) invalidCheckpoint("长文本 REDUCE 检查点不完整")
            level += 1
        }
    }

    private suspend fun runFinal(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        current: List<OfflineSummaryValidatedIntermediateNode>,
        finalNode: OfflineSummaryNodeEntity,
        inheritedRepairAttempted: Boolean,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult {
        requirePending(finalNode)
        store.markTaskPhase(summaryId, OfflineSummaryTaskStateValue.VALIDATING, currentLevel = finalNode.level)
        onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.VALIDATING, "正在生成并校验最终总结"))
        store.markNodeRunning(finalNode.id)
        try {
            val generated =
                generateValidated(
                    prompt = finalRenderer.render(current),
                    preparedIdentity = preparedIdentity,
                    finalResult = true,
                )
            val allowedEvidenceRefs = current.flatMapTo(LinkedHashSet()) { it.evidenceRefs }
            val result = SummaryResultCodec.decode(generated.text, allowedEvidenceRefs)
            val evidence = OfflineSummaryEvidenceMapper.map(input, result)
            val cleanupWarning = withContext(NonCancellable) { store.complete(summaryId, result, evidence) }
            return OfflineSummaryLongRunResult(
                finalGeneration = generated.generation,
                repairAttempted = inheritedRepairAttempted || generated.repairAttempted,
                warning = cleanupWarning?.let { "总结已保存，但长文本检查点清理待启动恢复：${sanitize(it)}" },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { store.markNodeFailed(finalNode.id, nodeErrorCode(error), sanitize(error.message)) }
            throw error
        }
    }

    private data class GeneratedStructured(
        val text: String,
        val generation: LocalLlmGenerationResult,
        val repairAttempted: Boolean,
    )

    private suspend fun generateValidated(
        prompt: RenderedOfflineSummaryLongPrompt,
        preparedIdentity: LocalLlmModelIdentity,
        finalResult: Boolean,
    ): GeneratedStructured {
        requirePromptWithinHardBudget(prompt.text)
        val config =
            if (finalResult) {
                OfflineSummaryLocalLlmPreset.configFor(prompt.allowedEvidenceRefs)
            } else {
                OfflineSummaryLocalLlmPreset.configForIntermediate(
                    allowedSourceRefs = prompt.allowedSourceRefs,
                    allowedEvidenceRefs = prompt.allowedEvidenceRefs,
                )
            }
        val first = generateLocal(prompt.text, config)
        requireGenerationIdentity(first, preparedIdentity)
        val firstValidation = validateStructured(first.text, prompt, finalResult)
        if (firstValidation == null) {
            return GeneratedStructured(first.text, first, repairAttempted = false)
        }

        val repairPrompt = buildRepairPrompt(prompt.text, firstValidation)
        requirePromptWithinHardBudget(repairPrompt)
        val repaired = generateLocal(repairPrompt, config)
        requireGenerationIdentity(repaired, preparedIdentity)
        val secondValidation = validateStructured(repaired.text, prompt, finalResult)
        if (secondValidation != null) {
            throw OfflineSummaryCoordinatorException(
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.REPAIR_FAILED,
                "长文本节点一次结构修复后仍未通过校验：$secondValidation",
            )
        }
        return GeneratedStructured(repaired.text, repaired, repairAttempted = true)
    }

    private fun validateStructured(
        raw: String,
        prompt: RenderedOfflineSummaryLongPrompt,
        finalResult: Boolean,
    ): String? =
        try {
            if (finalResult) {
                SummaryResultCodec.decode(raw, prompt.allowedEvidenceRefs)
            } else {
                OfflineSummaryIntermediateCodec.decode(raw, prompt.allowedSourceRefs, prompt.allowedEvidenceRefs)
            }
            null
        } catch (error: OfflineSummaryIntermediateException) {
            error.code.name
        } catch (error: SummaryStructuredOutputException) {
            error.code.name
        }

    private fun buildRepairPrompt(originalPrompt: String, validationError: String): String =
        buildString {
            append(originalPrompt)
            appendLine()
            appendLine()
            append("上一次输出未通过本地结构校验（error=")
            append(validationError)
            appendLine("）。请基于完全相同的输入重新输出一次；不得新增事实或引用。")
            append("仍然只返回完整 JSON 对象。")
        }

    private fun requirePromptWithinHardBudget(text: String) {
        val estimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(text)
        if (estimate > OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS) {
            throw OfflineSummaryCoordinatorException(
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY,
                "长文本节点请求超过安全预算：$estimate > ${OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS}",
            )
        }
    }

    private fun requireGenerationIdentity(
        generated: LocalLlmGenerationResult,
        preparedIdentity: LocalLlmModelIdentity,
    ) {
        Stage172OfflineSummaryModelContract.requireMatches(generated.modelIdentity)
        if (generated.modelIdentity != preparedIdentity) {
            throw OfflineSummaryCoordinatorException(
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.MODEL_NOT_READY,
                "长文本总结运行期间本地模型身份发生变化",
            )
        }
    }

    private fun requireFrozenMapPlan(
        snapshot: OfflineSummaryLongResumeSnapshot,
        plan: OfflineSummaryLongPlan,
    ) {
        if (snapshot.task.inputContentDigest != plan.inputContentDigest || snapshot.task.totalLeafChunks != plan.chunks.size) {
            invalidCheckpoint("MAP 冻结计划与当前输入不一致")
        }
        val mapNodes = snapshot.nodes.filter { it.nodeType == OfflineSummaryNodeTypeValue.MAP }.sortedBy { it.ordinal }
        if (mapNodes.size != plan.chunks.size) invalidCheckpoint("MAP 节点数量与冻结计划不一致")
        mapNodes.zip(plan.chunks).forEach { (node, chunk) ->
            if (
                node.level != 0 ||
                node.ordinal != chunk.index ||
                node.sourceStartUnitOrdinal != chunk.sourceUnitStartOrdinal ||
                node.sourceEndUnitOrdinalExclusive != chunk.sourceUnitEndOrdinalExclusive ||
                node.inputDigest != chunk.sourceDigest ||
                decodeJsonStrings(node.sourceRefsJson).toSet() != chunk.allowedSourceRefs ||
                decodeJsonStrings(node.evidenceRefsJson).toSet() != chunk.allowedEvidenceRefs
            ) {
                invalidCheckpoint("MAP 节点与确定性规划不一致")
            }
        }
    }

    private fun requireExistingLevelMatches(
        existing: List<OfflineSummaryNodeEntity>,
        drafts: List<OfflineSummaryLongNodeDraft>,
    ) {
        if (existing.size != drafts.size) invalidCheckpoint("已持久化层节点数量与确定性规划不一致")
        existing.zip(drafts).forEach { (node, draft) ->
            if (
                node.id != draft.id ||
                node.nodeType != draft.nodeType.name ||
                node.level != draft.level ||
                node.ordinal != draft.ordinal ||
                node.sourceStartUnitOrdinal != draft.sourceStartUnitOrdinal ||
                node.sourceEndUnitOrdinalExclusive != draft.sourceEndUnitOrdinalExclusive ||
                node.inputDigest != draft.inputDigest ||
                decodeJsonStrings(node.inputNodeIdsJson) != draft.inputNodeIds ||
                decodeJsonStrings(node.sourceRefsJson).toSet() != draft.sourceRefs ||
                decodeJsonStrings(node.evidenceRefsJson).toSet() != draft.evidenceRefs
            ) {
                invalidCheckpoint("已持久化层与确定性规划不一致")
            }
        }
    }

    private fun validatedIntermediateNodes(
        snapshot: OfflineSummaryLongResumeSnapshot,
        level: Int,
    ): List<OfflineSummaryValidatedIntermediateNode> =
        snapshot.nodes
            .filter { it.level == level && it.status == OfflineSummaryNodeStateValue.VALIDATED }
            .sortedBy { it.ordinal }
            .map { node ->
                val sourceRefs = decodeJsonStrings(node.sourceRefsJson).toSet()
                val evidenceRefs = decodeJsonStrings(node.evidenceRefsJson).toSet()
                val payload = node.validatedPayloadJson ?: invalidCheckpoint("VALIDATED 节点缺少 payload")
                OfflineSummaryValidatedIntermediateNode(
                    nodeId = node.id,
                    summary = OfflineSummaryIntermediateCodec.decode(payload, sourceRefs, evidenceRefs),
                    sourceRefs = sourceRefs,
                    evidenceRefs = evidenceRefs,
                )
            }

    private fun reduceDraft(
        summaryId: String,
        level: Int,
        group: OfflineSummaryReduceGroup,
    ): OfflineSummaryLongNodeDraft {
        val sourceRefs = group.nodes.flatMapTo(LinkedHashSet()) { it.sourceRefs }
        val evidenceRefs = group.nodes.flatMapTo(LinkedHashSet()) { it.evidenceRefs }
        return OfflineSummaryLongNodeDraft(
            id = "$summaryId:reduce:${level.toString().padStart(3, '0')}:${(group.index + 1).toString().padStart(5, '0')}",
            nodeType = OfflineSummaryLongNodeType.REDUCE,
            level = level,
            ordinal = group.index,
            sourceStartUnitOrdinal = sourceUnitStart(sourceRefs),
            sourceEndUnitOrdinalExclusive = sourceUnitEndExclusive(sourceRefs),
            inputNodeIds = group.nodes.map { it.nodeId },
            sourceRefs = sourceRefs,
            evidenceRefs = evidenceRefs,
            inputDigest = digestInputs(group.nodes),
            estimatedPromptTokens = group.estimatedPromptTokens,
        )
    }

    private fun finalDraft(
        summaryId: String,
        level: Int,
        nodes: List<OfflineSummaryValidatedIntermediateNode>,
        estimatedTokens: Int,
    ): OfflineSummaryLongNodeDraft {
        val sourceRefs = nodes.flatMapTo(LinkedHashSet()) { it.sourceRefs }
        val evidenceRefs = nodes.flatMapTo(LinkedHashSet()) { it.evidenceRefs }
        return OfflineSummaryLongNodeDraft(
            id = "$summaryId:final:${level.toString().padStart(3, '0')}:00001",
            nodeType = OfflineSummaryLongNodeType.FINAL,
            level = level,
            ordinal = 0,
            sourceStartUnitOrdinal = sourceUnitStart(sourceRefs),
            sourceEndUnitOrdinalExclusive = sourceUnitEndExclusive(sourceRefs),
            inputNodeIds = nodes.map { it.nodeId },
            sourceRefs = sourceRefs,
            evidenceRefs = evidenceRefs,
            inputDigest = digestInputs(nodes),
            estimatedPromptTokens = estimatedTokens,
        )
    }

    private fun sourceUnitStart(sourceRefs: Set<String>): Int = sourceRefs.minOf(::sourceUnitOrdinal)

    private fun sourceUnitEndExclusive(sourceRefs: Set<String>): Int = sourceRefs.maxOf(::sourceUnitOrdinal) + 1

    private fun sourceUnitOrdinal(sourceRef: String): Int = sourceRef.substring(1, 6).toInt() - 1

    private fun digestInputs(nodes: List<OfflineSummaryValidatedIntermediateNode>): String {
        val canonical =
            nodes.joinToString("\u001e") { node ->
                node.nodeId + "\u001f" + OfflineSummaryIntermediateCodec.encode(node.summary)
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun decodeJsonStrings(raw: String): List<String> =
        try {
            Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        } catch (error: Throwable) {
            invalidCheckpoint(error.message ?: "检查点引用数组无效")
        }

    private fun requirePending(node: OfflineSummaryNodeEntity) {
        if (node.status != OfflineSummaryNodeStateValue.PENDING) {
            invalidCheckpoint("节点 ${node.id} 不是可执行的 PENDING 状态：${node.status}")
        }
    }

    private suspend fun requireSnapshot(summaryId: String): OfflineSummaryLongResumeSnapshot =
        store.snapshot(summaryId) ?: invalidCheckpoint("长文本任务不存在")

    private fun nodeErrorCode(error: Throwable): String =
        when (error) {
            is OfflineSummaryCoordinatorException -> error.code.name
            is OfflineSummaryIntermediateException -> "INTERMEDIATE_${error.code.name}"
            is SummaryStructuredOutputException -> "FINAL_${error.code.name}"
            else -> error::class.java.simpleName.ifBlank { "UNKNOWN" }
        }

    private fun sanitize(message: String?): String? =
        message?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(180)

    private fun resumeException(error: OfflineSummaryLongPersistenceException): OfflineSummaryCoordinatorException {
        val code =
            if (error.code == OfflineSummaryLongPersistenceErrorCode.CHECKPOINT_INVALID) {
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID
            } else {
                io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE
            }
        return OfflineSummaryCoordinatorException(code, error.message ?: "长文本总结无法继续", error)
    }

    private fun invalidCheckpoint(message: String): Nothing =
        throw OfflineSummaryCoordinatorException(
            io.github.ioannes78.voica.ai.OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID,
            message,
        )
}
