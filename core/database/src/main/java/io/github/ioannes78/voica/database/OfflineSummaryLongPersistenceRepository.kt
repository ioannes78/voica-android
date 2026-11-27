package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateCodec
import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import io.github.ioannes78.voica.ai.OfflineSummaryLongNodeType
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlan
import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.ai.OfflineSummarySourceLineageCodec
import io.github.ioannes78.voica.ai.OfflineSummaryValidatedIntermediateNode
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import java.util.LinkedHashSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

enum class OfflineSummaryLongPersistenceErrorCode {
    SUMMARY_NOT_FOUND,
    TASK_NOT_FOUND,
    INVALID_STATE,
    NOT_RESUMABLE,
    IDENTITY_MISMATCH,
    CONTRACT_MISMATCH,
    CHECKPOINT_INVALID,
}

class OfflineSummaryLongPersistenceException(
    val code: OfflineSummaryLongPersistenceErrorCode,
    message: String,
) : IllegalStateException(message)

data class OfflineSummaryLongNodeDraft(
    val id: String,
    val nodeType: OfflineSummaryLongNodeType,
    val level: Int,
    val ordinal: Int,
    val sourceStartUnitOrdinal: Int,
    val sourceEndUnitOrdinalExclusive: Int,
    val inputNodeIds: List<String>,
    val sourceRefs: Set<String>,
    val evidenceRefs: Set<String>,
    val inputDigest: String,
    val estimatedPromptTokens: Int,
) {
    init {
        require(id.isNotBlank())
        require(level >= 0)
        require(ordinal >= 0)
        require(sourceStartUnitOrdinal >= 0)
        require(sourceEndUnitOrdinalExclusive > sourceStartUnitOrdinal)
        require(inputNodeIds.size == inputNodeIds.distinct().size)
        require(sourceRefs.isNotEmpty())
        require(sourceRefs.all(SOURCE_REF_PATTERN::matches))
        require(evidenceRefs.all(EVIDENCE_REF_PATTERN::matches))
        require(inputDigest.matches(DIGEST_PATTERN))
        require(estimatedPromptTokens >= 0)
    }

    private companion object {
        val SOURCE_REF_PATTERN = Regex("""U\d{5}(?::F\d{3,})?""")
        val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

data class OfflineSummaryLongResumeSnapshot(
    val task: OfflineSummaryTaskEntity,
    val nodes: List<OfflineSummaryNodeEntity>,
)

/**
 * Durable Stage 17.3 checkpoint boundary.
 *
 * Room is the only business-state truth. This repository never owns a LiteRT-LM engine and never
 * invokes inference. Startup reconciliation only makes persisted work resumable; an explicit
 * [prepareResume] call is required before any later orchestration may continue generation.
 */
class OfflineSummaryLongPersistenceRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val longDao = database.offlineSummaryLongDao()
    private val summaryDao = database.aiSummaryDao()

    suspend fun create(
        summaryId: String,
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
        plan: OfflineSummaryLongPlan,
        directPromptEstimatedTokens: Int,
    ): OfflineSummaryTaskEntity =
        database.withTransaction {
            require(directPromptEstimatedTokens >= 0)
            require(plan.inputContentDigest == input.inputContentDigest) {
                "long-summary plan/input digest mismatch"
            }
            require(plan.pipelineVersion == OfflineSummaryLongContract.PIPELINE_VERSION)
            require(plan.plannerVersion == OfflineSummaryLongContract.PLANNER_VERSION)
            val summary = requireSummary(summaryId)
            validateIdentity(summary, input, modelIdentitySnapshot)
            requireState(
                summary.status in setOf(
                    AiSummaryStateValue.CREATED,
                    AiSummaryStateValue.PREPARING,
                    AiSummaryStateValue.ANALYZING,
                ),
                "long-summary task can only be created from a prepared summary",
            )
            requireState(longDao.findTask(summaryId) == null, "long-summary task already exists")

            val now = nowMs()
            val task =
                OfflineSummaryTaskEntity(
                    aiSummaryId = summaryId,
                    pipelineVersion = OfflineSummaryLongContract.PIPELINE_VERSION,
                    plannerVersion = OfflineSummaryLongContract.PLANNER_VERSION,
                    mapPromptVersion = OfflineSummaryLongContract.MAP_PROMPT_VERSION,
                    reducePromptVersion = OfflineSummaryLongContract.REDUCE_PROMPT_VERSION,
                    finalPromptVersion = OfflineSummaryLongContract.FINAL_PROMPT_VERSION,
                    intermediateSchemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                    inputContentDigest = input.inputContentDigest,
                    route = OfflineSummaryTaskRouteValue.LONG,
                    directPromptEstimatedTokens = directPromptEstimatedTokens,
                    status = OfflineSummaryTaskStateValue.PLANNING,
                    currentLevel = 0,
                    totalLeafChunks = plan.chunks.size,
                    completedLeafChunks = 0,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            longDao.insertTask(task)
            longDao.insertNodes(
                plan.chunks.map { chunk ->
                    OfflineSummaryNodeEntity(
                        id = mapNodeId(summaryId, chunk.index),
                        aiSummaryId = summaryId,
                        nodeType = OfflineSummaryNodeTypeValue.MAP,
                        level = 0,
                        ordinal = chunk.index,
                        sourceStartUnitOrdinal = chunk.sourceUnitStartOrdinal,
                        sourceEndUnitOrdinalExclusive = chunk.sourceUnitEndOrdinalExclusive,
                        inputNodeIdsJson = EMPTY_JSON_ARRAY,
                        sourceRefsJson = encodeStrings(chunk.allowedSourceRefs),
                        evidenceRefsJson = encodeStrings(chunk.allowedEvidenceRefs),
                        inputDigest = chunk.sourceDigest,
                        estimatedPromptTokens = chunk.estimatedPromptTokens,
                        status = OfflineSummaryNodeStateValue.PENDING,
                        attemptCount = 0,
                        validatedPayloadJson = null,
                        errorCode = null,
                        sanitizedErrorMessage = null,
                        createdAtMs = now,
                        updatedAtMs = now,
                        completedAtMs = null,
                    )
                },
            )
            persistSummaryState(summary, AiSummaryStateValue.PLANNING, now)
            task
        }

    suspend fun insertPlannedNodes(
        summaryId: String,
        drafts: List<OfflineSummaryLongNodeDraft>,
    ): List<OfflineSummaryNodeEntity> =
        database.withTransaction {
            if (drafts.isEmpty()) return@withTransaction emptyList()
            val task = requireTask(summaryId)
            requireState(task.status in OfflineSummaryTaskStateValue.ACTIVE, "task is not active")
            requireState(drafts.map { it.id }.size == drafts.map { it.id }.toSet().size, "duplicate node id")
            requireState(
                drafts.map { it.level to it.ordinal }.size == drafts.map { it.level to it.ordinal }.toSet().size,
                "duplicate node level/ordinal",
            )

            val existing = longDao.loadNodes(summaryId)
            val existingById = existing.associateBy { it.id }
            val existingPositions = existing.mapTo(mutableSetOf()) { it.level to it.ordinal }
            val now = nowMs()
            val entities =
                drafts.map { draft ->
                    requireState(draft.nodeType != OfflineSummaryLongNodeType.MAP, "MAP nodes are created from the frozen plan")
                    requireState(draft.level > 0, "reduce/final node level must be positive")
                    requireState(draft.inputNodeIds.isNotEmpty(), "reduce/final node must reference validated inputs")
                    requireState(draft.id !in existingById, "node id already exists")
                    requireState((draft.level to draft.ordinal) !in existingPositions, "node position already exists")
                    val inputs =
                        draft.inputNodeIds.map { inputId ->
                            existingById[inputId]
                                ?: checkpointInvalid("input node is missing: $inputId")
                        }
                    requireCheckpoint(inputs.all { it.status == OfflineSummaryNodeStateValue.VALIDATED }, "node input is not validated")
                    requireCheckpoint(inputs.all { it.level < draft.level }, "node input level is not below output level")
                    val expectedSourceRefs = inputs.flatMapTo(LinkedHashSet()) { decodeStrings(it.sourceRefsJson) }
                    val expectedEvidenceRefs = inputs.flatMapTo(LinkedHashSet()) { decodeStrings(it.evidenceRefsJson) }
                    requireCheckpoint(draft.sourceRefs == expectedSourceRefs, "node source refs do not cover inputs exactly")
                    requireCheckpoint(draft.evidenceRefs == expectedEvidenceRefs, "node evidence refs do not cover inputs exactly")
                    requireCheckpoint(
                        draft.sourceStartUnitOrdinal == inputs.minOf { it.sourceStartUnitOrdinal } &&
                            draft.sourceEndUnitOrdinalExclusive == inputs.maxOf { it.sourceEndUnitOrdinalExclusive },
                        "node source range does not cover inputs exactly",
                    )
                    OfflineSummaryNodeEntity(
                        id = draft.id,
                        aiSummaryId = summaryId,
                        nodeType = draft.nodeType.name,
                        level = draft.level,
                        ordinal = draft.ordinal,
                        sourceStartUnitOrdinal = draft.sourceStartUnitOrdinal,
                        sourceEndUnitOrdinalExclusive = draft.sourceEndUnitOrdinalExclusive,
                        inputNodeIdsJson = encodeStrings(draft.inputNodeIds),
                        sourceRefsJson = encodeStrings(draft.sourceRefs),
                        evidenceRefsJson = encodeStrings(draft.evidenceRefs),
                        inputDigest = draft.inputDigest,
                        estimatedPromptTokens = draft.estimatedPromptTokens,
                        status = OfflineSummaryNodeStateValue.PENDING,
                        attemptCount = 0,
                        validatedPayloadJson = null,
                        errorCode = null,
                        sanitizedErrorMessage = null,
                        createdAtMs = now,
                        updatedAtMs = now,
                        completedAtMs = null,
                    )
                }
            longDao.insertNodes(entities)
            entities
        }

    suspend fun markTaskPhase(
        summaryId: String,
        status: String,
        currentLevel: Int,
    ): OfflineSummaryTaskEntity =
        database.withTransaction {
            require(status in setOf(
                OfflineSummaryTaskStateValue.MAPPING,
                OfflineSummaryTaskStateValue.REDUCING,
                OfflineSummaryTaskStateValue.VALIDATING,
            )) { "unsupported long-summary active phase" }
            require(currentLevel >= 0)
            val task = requireTask(summaryId)
            requireState(task.status in OfflineSummaryTaskStateValue.ACTIVE, "task is not active")
            val summary = requireSummary(summaryId)
            val now = nowMs()
            val updated = task.copy(status = status, currentLevel = currentLevel, updatedAtMs = now)
            require(longDao.updateTask(updated) == 1) { "failed to update long-summary task" }
            persistSummaryState(summary, status, now)
            updated
        }

    suspend fun markNodeRunning(nodeId: String): OfflineSummaryNodeEntity =
        database.withTransaction {
            val node = requireNode(nodeId)
            val task = requireTask(node.aiSummaryId)
            requireState(task.status in OfflineSummaryTaskStateValue.ACTIVE, "task is not active")
            requireState(node.status == OfflineSummaryNodeStateValue.PENDING, "node is not pending")
            val now = nowMs()
            val updated =
                node.copy(
                    status = OfflineSummaryNodeStateValue.RUNNING,
                    attemptCount = node.attemptCount + 1,
                    validatedPayloadJson = null,
                    errorCode = null,
                    sanitizedErrorMessage = null,
                    updatedAtMs = now,
                    completedAtMs = null,
                )
            require(longDao.updateNode(updated) == 1) { "failed to mark long-summary node running" }
            updated
        }

    suspend fun persistValidatedIntermediate(
        nodeId: String,
        rawPayload: String,
    ): OfflineSummaryNodeEntity =
        database.withTransaction {
            val node = requireNode(nodeId)
            val task = requireTask(node.aiSummaryId)
            requireState(task.status in OfflineSummaryTaskStateValue.ACTIVE, "task is not active")
            requireState(node.status == OfflineSummaryNodeStateValue.RUNNING, "node is not running")
            requireCheckpoint(
                node.nodeType == OfflineSummaryNodeTypeValue.MAP ||
                    node.nodeType == OfflineSummaryNodeTypeValue.REDUCE,
                "only MAP/REDUCE nodes may persist validated intermediate payloads",
            )
            val sourceRefs = decodeStrings(node.sourceRefsJson).toSet()
            val evidenceRefs = decodeStrings(node.evidenceRefsJson).toSet()
            val validated =
                OfflineSummaryIntermediateCodec.decode(
                    raw = rawPayload,
                    allowedSourceRefs = sourceRefs,
                    allowedEvidenceRefs = evidenceRefs,
                )
            val now = nowMs()
            val updatedNode =
                node.copy(
                    status = OfflineSummaryNodeStateValue.VALIDATED,
                    validatedPayloadJson = OfflineSummaryIntermediateCodec.encode(validated),
                    errorCode = null,
                    sanitizedErrorMessage = null,
                    updatedAtMs = now,
                    completedAtMs = now,
                )
            require(longDao.updateNode(updatedNode) == 1) { "failed to checkpoint long-summary node" }
            if (node.nodeType == OfflineSummaryNodeTypeValue.MAP) {
                val completed = task.completedLeafChunks + 1
                requireCheckpoint(completed <= task.totalLeafChunks, "completed MAP count exceeds frozen plan")
                require(longDao.updateTask(task.copy(completedLeafChunks = completed, updatedAtMs = now)) == 1) {
                    "failed to update long-summary leaf progress"
                }
            }
            updatedNode
        }

    suspend fun markNodeFailed(
        nodeId: String,
        errorCode: String,
        sanitizedMessage: String?,
    ): OfflineSummaryNodeEntity =
        database.withTransaction {
            val node = requireNode(nodeId)
            requireState(node.status == OfflineSummaryNodeStateValue.RUNNING, "node is not running")
            val now = nowMs()
            val updated =
                node.copy(
                    status = OfflineSummaryNodeStateValue.FAILED,
                    validatedPayloadJson = null,
                    errorCode = errorCode.take(MAX_ERROR_CODE_CHARS),
                    sanitizedErrorMessage = sanitizeMessage(sanitizedMessage),
                    updatedAtMs = now,
                    completedAtMs = null,
                )
            require(longDao.updateNode(updated) == 1) { "failed to persist long-summary node failure" }
            updated
        }

    suspend fun fail(
        summaryId: String,
        code: OfflineSummaryFailureCode,
        sanitizedMessage: String?,
    ): OfflineSummaryTaskEntity =
        database.withTransaction {
            val task = requireTask(summaryId)
            requireState(task.status !in TERMINAL_TASK_STATES, "task is already terminal")
            val summary = requireSummary(summaryId)
            val now = nowMs()
            longDao.loadNodes(summaryId)
                .filter { it.status == OfflineSummaryNodeStateValue.RUNNING }
                .forEach { node ->
                    require(longDao.updateNode(
                        node.copy(
                            status = OfflineSummaryNodeStateValue.FAILED,
                            validatedPayloadJson = null,
                            errorCode = code.name,
                            sanitizedErrorMessage = sanitizeMessage(sanitizedMessage),
                            updatedAtMs = now,
                            completedAtMs = null,
                        ),
                    ) == 1) { "failed to close running long-summary node" }
                }
            val updated = task.copy(status = OfflineSummaryTaskStateValue.FAILED, updatedAtMs = now)
            require(longDao.updateTask(updated) == 1) { "failed to persist long-summary task failure" }
            persistSummaryTerminal(
                summary = summary,
                status = AiSummaryStateValue.FAILED,
                errorCode = code.name,
                message = sanitizedMessage,
                now = now,
            )
            updated
        }

    suspend fun cancel(summaryId: String): OfflineSummaryTaskEntity =
        database.withTransaction {
            val task = requireTask(summaryId)
            requireState(task.status !in setOf(OfflineSummaryTaskStateValue.COMPLETED, OfflineSummaryTaskStateValue.CANCELLED), "task is already terminal")
            val summary = requireSummary(summaryId)
            val now = nowMs()
            longDao.deleteNodes(summaryId)
            val updated = task.copy(status = OfflineSummaryTaskStateValue.CANCELLED, updatedAtMs = now)
            require(longDao.updateTask(updated) == 1) { "failed to cancel long-summary task" }
            persistSummaryTerminal(
                summary = summary,
                status = AiSummaryStateValue.CANCELLED,
                errorCode = OfflineSummaryFailureCode.GENERATION_CANCELLED.name,
                message = null,
                now = now,
            )
            updated
        }

    suspend fun markCompletedAndCleanup(summaryId: String): OfflineSummaryTaskEntity =
        database.withTransaction {
            val task = requireTask(summaryId)
            val summary = requireSummary(summaryId)
            requireState(summary.status == AiSummaryStateValue.COMPLETED, "summary is not completed")
            requireState(task.status != OfflineSummaryTaskStateValue.CANCELLED, "cancelled task cannot complete")
            val now = nowMs()
            longDao.deleteNodes(summaryId)
            val updated = task.copy(
                status = OfflineSummaryTaskStateValue.COMPLETED,
                completedLeafChunks = task.totalLeafChunks,
                updatedAtMs = now,
            )
            require(longDao.updateTask(updated) == 1) { "failed to finalize long-summary task" }
            updated
        }

    /**
     * Reconciles persisted ownership after process death. It never schedules or invokes inference.
     * VALIDATED MAP/REDUCE checkpoints are retained exactly; only formerly RUNNING nodes are marked
     * INTERRUPTED, and explicit [prepareResume] is required to make them PENDING again.
     */
    suspend fun reconcileOnStartup(): Int {
        val active = longDao.loadTasksByStatuses(OfflineSummaryTaskStateValue.ACTIVE.toList())
        var reconciled = 0
        active.forEach { candidate ->
            database.withTransaction {
                val task = longDao.findTask(candidate.aiSummaryId) ?: return@withTransaction
                if (task.status !in OfflineSummaryTaskStateValue.ACTIVE) return@withTransaction
                val summary = requireSummary(task.aiSummaryId)
                val now = nowMs()
                when (summary.status) {
                    AiSummaryStateValue.COMPLETED -> {
                        longDao.deleteNodes(task.aiSummaryId)
                        require(longDao.updateTask(
                            task.copy(
                                status = OfflineSummaryTaskStateValue.COMPLETED,
                                completedLeafChunks = task.totalLeafChunks,
                                updatedAtMs = now,
                            ),
                        ) == 1)
                    }
                    AiSummaryStateValue.CANCELLED -> {
                        longDao.deleteNodes(task.aiSummaryId)
                        require(longDao.updateTask(task.copy(status = OfflineSummaryTaskStateValue.CANCELLED, updatedAtMs = now)) == 1)
                    }
                    else -> {
                        longDao.loadNodes(task.aiSummaryId)
                            .filter { it.status == OfflineSummaryNodeStateValue.RUNNING }
                            .forEach { node ->
                                require(longDao.updateNode(
                                    node.copy(
                                        status = OfflineSummaryNodeStateValue.INTERRUPTED,
                                        validatedPayloadJson = null,
                                        errorCode = OfflineSummaryFailureCode.INTERRUPTED.name,
                                        sanitizedErrorMessage = PROCESS_INTERRUPTED_MESSAGE,
                                        updatedAtMs = now,
                                        completedAtMs = null,
                                    ),
                                ) == 1) { "failed to interrupt running long-summary node" }
                            }
                        require(longDao.updateTask(task.copy(status = OfflineSummaryTaskStateValue.INTERRUPTED, updatedAtMs = now)) == 1) {
                            "failed to interrupt long-summary task"
                        }
                        persistSummaryTerminal(
                            summary = summary,
                            status = AiSummaryStateValue.INTERRUPTED,
                            errorCode = OfflineSummaryFailureCode.INTERRUPTED.name,
                            message = PROCESS_INTERRUPTED_MESSAGE,
                            now = now,
                        )
                    }
                }
                reconciled += 1
            }
        }
        return reconciled
    }

    /** Explicit, identity-checked resume boundary. No generation is started by this method. */
    suspend fun prepareResume(
        summaryId: String,
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
    ): OfflineSummaryLongResumeSnapshot =
        database.withTransaction {
            val task = requireTask(summaryId)
            val summary = requireSummary(summaryId)
            if (task.status !in RESUMABLE_TASK_STATES || summary.status !in RESUMABLE_SUMMARY_STATES) {
                throw OfflineSummaryLongPersistenceException(
                    OfflineSummaryLongPersistenceErrorCode.NOT_RESUMABLE,
                    "long-summary task is not resumable",
                )
            }
            validateContract(task)
            validateIdentity(summary, input, modelIdentitySnapshot)
            requireIdentity(task.inputContentDigest == input.inputContentDigest, "task input digest changed")

            val nodes = longDao.loadNodes(summaryId)
            validateCheckpointGraph(task, nodes)
            val completedMapCount =
                nodes.count {
                    it.nodeType == OfflineSummaryNodeTypeValue.MAP &&
                        it.status == OfflineSummaryNodeStateValue.VALIDATED
                }
            requireCheckpoint(completedMapCount == task.completedLeafChunks, "task MAP progress does not match checkpoints")

            val now = nowMs()
            val resumedNodes =
                nodes.map { node ->
                    if (node.status in RESTARTABLE_NODE_STATES) {
                        val reset =
                            node.copy(
                                status = OfflineSummaryNodeStateValue.PENDING,
                                validatedPayloadJson = null,
                                errorCode = null,
                                sanitizedErrorMessage = null,
                                updatedAtMs = now,
                                completedAtMs = null,
                            )
                        require(longDao.updateNode(reset) == 1) { "failed to reset resumable long-summary node" }
                        reset
                    } else {
                        node
                    }
                }
            val resumeStatus =
                if (resumedNodes.any {
                        it.nodeType == OfflineSummaryNodeTypeValue.MAP &&
                            it.status != OfflineSummaryNodeStateValue.VALIDATED
                    }
                ) {
                    OfflineSummaryTaskStateValue.MAPPING
                } else {
                    OfflineSummaryTaskStateValue.REDUCING
                }
            val currentLevel = if (resumeStatus == OfflineSummaryTaskStateValue.MAPPING) 0 else maxOf(1, task.currentLevel)
            val resumedTask =
                task.copy(
                    status = resumeStatus,
                    currentLevel = currentLevel,
                    completedLeafChunks = completedMapCount,
                    updatedAtMs = now,
                )
            require(longDao.updateTask(resumedTask) == 1) { "failed to resume long-summary task" }
            persistSummaryState(summary, resumeStatus, now)
            OfflineSummaryLongResumeSnapshot(resumedTask, resumedNodes)
        }

    suspend fun snapshot(summaryId: String): OfflineSummaryLongResumeSnapshot? =
        longDao.findTask(summaryId)?.let { task ->
            OfflineSummaryLongResumeSnapshot(task, longDao.loadNodes(summaryId))
        }

    suspend fun loadValidatedIntermediateNodes(
        summaryId: String,
        level: Int,
    ): List<OfflineSummaryValidatedIntermediateNode> {
        require(level >= 0)
        return longDao.loadNodesByStatuses(summaryId, listOf(OfflineSummaryNodeStateValue.VALIDATED))
            .filter { it.level == level }
            .map { node -> decodeValidatedNode(node) }
    }

    private fun decodeValidatedNode(node: OfflineSummaryNodeEntity): OfflineSummaryValidatedIntermediateNode {
        requireCheckpoint(
            node.nodeType == OfflineSummaryNodeTypeValue.MAP || node.nodeType == OfflineSummaryNodeTypeValue.REDUCE,
            "FINAL node cannot be an intermediate checkpoint",
        )
        val sourceRefs = decodeStrings(node.sourceRefsJson).toSet()
        val evidenceRefs = decodeStrings(node.evidenceRefsJson).toSet()
        val payload = node.validatedPayloadJson ?: checkpointInvalid("validated node payload is missing")
        return OfflineSummaryValidatedIntermediateNode(
            nodeId = node.id,
            summary = OfflineSummaryIntermediateCodec.decode(payload, sourceRefs, evidenceRefs),
            sourceRefs = sourceRefs,
            evidenceRefs = evidenceRefs,
        )
    }

    private fun validateCheckpointGraph(
        task: OfflineSummaryTaskEntity,
        nodes: List<OfflineSummaryNodeEntity>,
    ) {
        requireCheckpoint(nodes.isNotEmpty(), "long-summary task has no nodes")
        val byId = nodes.associateBy { it.id }
        requireCheckpoint(byId.size == nodes.size, "duplicate node id")
        val positions = nodes.map { it.level to it.ordinal }
        requireCheckpoint(positions.size == positions.toSet().size, "duplicate node position")
        val mapNodes = nodes.filter { it.nodeType == OfflineSummaryNodeTypeValue.MAP }
        requireCheckpoint(mapNodes.size == task.totalLeafChunks, "MAP node count does not match frozen plan")
        requireCheckpoint(mapNodes.all { it.level == 0 }, "MAP node level changed")

        nodes.forEach { node ->
            requireCheckpoint(node.inputDigest.matches(DIGEST_PATTERN), "invalid node input digest")
            requireCheckpoint(node.sourceStartUnitOrdinal >= 0, "invalid node source start")
            requireCheckpoint(node.sourceEndUnitOrdinalExclusive > node.sourceStartUnitOrdinal, "invalid node source end")
            requireCheckpoint(node.attemptCount >= 0, "invalid node attempt count")
            requireCheckpoint(node.status in ALL_NODE_STATES, "unsupported node state")
            val sourceRefs = decodeStrings(node.sourceRefsJson)
            val evidenceRefs = decodeStrings(node.evidenceRefsJson)
            requireCheckpoint(sourceRefs.isNotEmpty() && sourceRefs.size == sourceRefs.toSet().size, "invalid node source refs")
            requireCheckpoint(sourceRefs.all(SOURCE_REF_PATTERN::matches), "invalid node source ref")
            requireCheckpoint(evidenceRefs.size == evidenceRefs.toSet().size, "duplicate node evidence refs")
            requireCheckpoint(evidenceRefs.all(EVIDENCE_REF_PATTERN::matches), "invalid node evidence ref")
            val inputIds = decodeStrings(node.inputNodeIdsJson)
            requireCheckpoint(inputIds.size == inputIds.toSet().size, "duplicate node input ids")

            if (node.nodeType == OfflineSummaryNodeTypeValue.MAP) {
                requireCheckpoint(inputIds.isEmpty(), "MAP node must not have node inputs")
            } else {
                requireCheckpoint(
                    node.nodeType == OfflineSummaryNodeTypeValue.REDUCE || node.nodeType == OfflineSummaryNodeTypeValue.FINAL,
                    "unsupported node type",
                )
                requireCheckpoint(inputIds.isNotEmpty(), "reduce/final node has no inputs")
                val inputs = inputIds.map { byId[it] ?: checkpointInvalid("node input is missing: $it") }
                requireCheckpoint(inputs.all { it.level < node.level }, "node input level is invalid")
                requireCheckpoint(inputs.all { it.status == OfflineSummaryNodeStateValue.VALIDATED }, "node depends on non-validated input")
                requireCheckpoint(
                    sourceRefs.toSet() == inputs.flatMapTo(LinkedHashSet()) { decodeStrings(it.sourceRefsJson) },
                    "node source refs do not match inputs",
                )
                requireCheckpoint(
                    evidenceRefs.toSet() == inputs.flatMapTo(LinkedHashSet()) { decodeStrings(it.evidenceRefsJson) },
                    "node evidence refs do not match inputs",
                )
            }

            if (node.status == OfflineSummaryNodeStateValue.VALIDATED) {
                decodeValidatedNode(node)
                requireCheckpoint(node.completedAtMs != null, "validated node has no completion time")
            } else {
                requireCheckpoint(node.validatedPayloadJson == null, "non-validated node carries checkpoint payload")
            }
        }
    }

    private fun validateContract(task: OfflineSummaryTaskEntity) {
        requireContract(task.route == OfflineSummaryTaskRouteValue.LONG, "task route changed")
        requireContract(task.pipelineVersion == OfflineSummaryLongContract.PIPELINE_VERSION, "pipeline version changed")
        requireContract(task.plannerVersion == OfflineSummaryLongContract.PLANNER_VERSION, "planner version changed")
        requireContract(task.mapPromptVersion == OfflineSummaryLongContract.MAP_PROMPT_VERSION, "MAP prompt version changed")
        requireContract(task.reducePromptVersion == OfflineSummaryLongContract.REDUCE_PROMPT_VERSION, "REDUCE prompt version changed")
        requireContract(task.finalPromptVersion == OfflineSummaryLongContract.FINAL_PROMPT_VERSION, "FINAL prompt version changed")
        requireContract(
            task.intermediateSchemaVersion == OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
            "intermediate schema version changed",
        )
    }

    private fun validateIdentity(
        summary: AiSummaryEntity,
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
    ) {
        requireIdentity(summary.inputMode == AiSummaryInputModeValue.TRANSCRIPT_TEXT, "summary input mode changed")
        requireIdentity(summary.mode == AiSummaryModeValue.SMART, "summary mode changed")
        requireIdentity(summary.recordingId == input.recordingId, "recording changed")
        requireIdentity(summary.transcriptionId == input.transcriptionId, "transcription changed")
        requireIdentity(summary.alignmentIdSnapshot == input.alignmentId, "alignment changed")
        requireIdentity(summary.model == modelIdentitySnapshot, "model identity changed")
        requireIdentity(summary.promptVersion == OfflineSummaryPromptContract.PROMPT_VERSION, "outer prompt contract changed")
        requireIdentity(summary.resultSchemaVersion == OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION, "result schema changed")

        val lineage =
            runCatching { OfflineSummarySourceLineageCodec.decode(summary.sourceLineageSnapshot) }
                .getOrElse { error ->
                    throw OfflineSummaryLongPersistenceException(
                        OfflineSummaryLongPersistenceErrorCode.IDENTITY_MISMATCH,
                        error.message ?: "source lineage is not resumable",
                    )
                }
        requireIdentity(lineage.recordingId == input.recordingId, "lineage recording changed")
        requireIdentity(lineage.transcriptionId == input.transcriptionId, "lineage transcription changed")
        requireIdentity(lineage.transcriptionRevisionId == input.transcriptionRevisionId, "lineage revision changed")
        requireIdentity(lineage.inputContentDigest == input.inputContentDigest, "lineage input digest changed")
        requireIdentity(lineage.alignmentId == input.alignmentId, "lineage alignment changed")
        requireIdentity(lineage.canonicalAssetId == input.canonicalAssetId, "canonical asset changed")
        requireIdentity(lineage.canonicalSha256 == input.canonicalSha256, "canonical sha changed")
        requireIdentity(lineage.canonicalProfileId == input.canonicalProfileId, "canonical profile changed")
        requireIdentity(lineage.transcriptionMode == input.transcriptionMode, "transcription mode changed")
        requireIdentity(lineage.languageConfig == input.languageConfig, "language config changed")
    }

    private suspend fun persistSummaryState(
        summary: AiSummaryEntity,
        status: String,
        now: Long,
    ) {
        val updated =
            summary.copy(
                status = status,
                startedAtMs = summary.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = null,
                sanitizedErrorMessage = null,
            )
        require(summaryDao.updateSummary(updated) == 1) { "failed to update long-summary parent state" }
    }

    private suspend fun persistSummaryTerminal(
        summary: AiSummaryEntity,
        status: String,
        errorCode: String,
        message: String?,
        now: Long,
    ) {
        val updated =
            summary.copy(
                status = status,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = errorCode,
                sanitizedErrorMessage = sanitizeMessage(message),
            )
        require(summaryDao.updateSummary(updated) == 1) { "failed to persist long-summary parent terminal state" }
    }

    private suspend fun requireSummary(summaryId: String): AiSummaryEntity =
        summaryDao.findSummary(summaryId)
            ?: throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.SUMMARY_NOT_FOUND,
                "AI summary not found",
            )

    private suspend fun requireTask(summaryId: String): OfflineSummaryTaskEntity =
        longDao.findTask(summaryId)
            ?: throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.TASK_NOT_FOUND,
                "long-summary task not found",
            )

    private suspend fun requireNode(nodeId: String): OfflineSummaryNodeEntity =
        longDao.findNode(nodeId)
            ?: throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.CHECKPOINT_INVALID,
                "long-summary node not found",
            )

    private fun mapNodeId(summaryId: String, index: Int): String =
        "$summaryId:map:" + (index + 1).toString().padStart(5, '0')

    private fun encodeStrings(values: Collection<String>): String =
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }.toString()

    private fun decodeStrings(raw: String): List<String> =
        try {
            json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        } catch (error: Throwable) {
            checkpointInvalid(error.message ?: "invalid checkpoint JSON array")
        }

    private fun sanitizeMessage(message: String?): String? =
        message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_SANITIZED_ERROR_CHARS)

    private fun requireState(condition: Boolean, message: String) {
        if (!condition) {
            throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.INVALID_STATE,
                message,
            )
        }
    }

    private fun requireIdentity(condition: Boolean, message: String) {
        if (!condition) {
            throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.IDENTITY_MISMATCH,
                message,
            )
        }
    }

    private fun requireContract(condition: Boolean, message: String) {
        if (!condition) {
            throw OfflineSummaryLongPersistenceException(
                OfflineSummaryLongPersistenceErrorCode.CONTRACT_MISMATCH,
                message,
            )
        }
    }

    private fun requireCheckpoint(condition: Boolean, message: String) {
        if (!condition) checkpointInvalid(message)
    }

    private fun checkpointInvalid(message: String): Nothing =
        throw OfflineSummaryLongPersistenceException(
            OfflineSummaryLongPersistenceErrorCode.CHECKPOINT_INVALID,
            message,
        )

    private companion object {
        val json = Json { ignoreUnknownKeys = false; isLenient = false }
        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
        val SOURCE_REF_PATTERN = Regex("""U\d{5}(?::F\d{3,})?""")
        val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
        val ALL_NODE_STATES =
            setOf(
                OfflineSummaryNodeStateValue.PENDING,
                OfflineSummaryNodeStateValue.RUNNING,
                OfflineSummaryNodeStateValue.VALIDATED,
                OfflineSummaryNodeStateValue.FAILED,
                OfflineSummaryNodeStateValue.INTERRUPTED,
            )
        val RESTARTABLE_NODE_STATES =
            setOf(
                OfflineSummaryNodeStateValue.RUNNING,
                OfflineSummaryNodeStateValue.FAILED,
                OfflineSummaryNodeStateValue.INTERRUPTED,
            )
        val RESUMABLE_TASK_STATES =
            setOf(OfflineSummaryTaskStateValue.FAILED, OfflineSummaryTaskStateValue.INTERRUPTED)
        val RESUMABLE_SUMMARY_STATES =
            setOf(AiSummaryStateValue.FAILED, AiSummaryStateValue.INTERRUPTED)
        val TERMINAL_TASK_STATES =
            setOf(OfflineSummaryTaskStateValue.COMPLETED, OfflineSummaryTaskStateValue.CANCELLED)
        const val EMPTY_JSON_ARRAY = "[]"
        const val MAX_SANITIZED_ERROR_CHARS = 240
        const val MAX_ERROR_CODE_CHARS = 80
        const val PROCESS_INTERRUPTED_MESSAGE = "应用进程中断，长文本离线总结可从已验证检查点继续"
    }
}
