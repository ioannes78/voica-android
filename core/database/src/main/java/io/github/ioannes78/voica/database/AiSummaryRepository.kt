package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import kotlinx.coroutines.flow.Flow

/** Structured Stage 17.3 product phase derived from Room business-state truth. */
enum class OfflineSummaryLongProductStage {
    PLANNING,
    MAPPING,
    REDUCING,
    VALIDATING,
}

data class OfflineSummaryLongProductProgress(
    val stage: OfflineSummaryLongProductStage,
    /** 1-based active/pending step within [total], when the phase has bounded work. */
    val current: Int? = null,
    val total: Int? = null,
    /** REDUCE tree level; null outside REDUCING. */
    val level: Int? = null,
) {
    init {
        require((current == null) == (total == null)) { "current/total must be present together" }
        if (current != null && total != null) {
            require(total > 0)
            require(current in 1..total)
        }
        require(level == null || level > 0)
        require(stage == OfflineSummaryLongProductStage.REDUCING || level == null)
    }
}

/** Provider-neutral access to retained historical summary content. */
class AiSummaryRepository(
    private val database: VoicaDatabase,
) {
    private val dao = database.aiSummaryDao()
    private val transcriptionDao = database.transcriptionDao()
    private val structuredTranscriptInputBuilder = StructuredTranscriptInputBuilder(database)
    private val offlineSummaryPersistence = OfflineSummaryPersistenceRepository(database)
    private val offlineSummaryLongPersistence = OfflineSummaryLongPersistenceRepository(database)

    fun observeForRecording(recordingId: String): Flow<List<AiSummaryEntity>> =
        dao.observeForRecording(recordingId)

    suspend fun find(summaryId: String): AiSummaryEntity? =
        dao.findSummary(summaryId)

    suspend fun recordingIdForTranscription(transcriptionId: String): String? =
        transcriptionDao.findTranscription(transcriptionId)?.recordingId

    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity> =
        dao.loadEvidence(summaryId)

    /**
     * Read-only product hint for explicit Stage 17.3 Resume.
     *
     * The actual resume boundary still re-validates the complete identity and checkpoint graph
     * transactionally before it mutates any state. This method only prevents ordinary Direct or
     * terminal Long summaries from being offered a misleading Continue action.
     */
    suspend fun isResumableOfflineSummary(summaryId: String): Boolean {
        val summary = dao.findSummary(summaryId) ?: return false
        if (summary.status !in setOf(AiSummaryStateValue.FAILED, AiSummaryStateValue.INTERRUPTED)) {
            return false
        }
        if (isTerminalOfflineSummaryResumeFailure(summary.errorCode)) {
            return false
        }
        val task = offlineSummaryLongPersistence.snapshot(summaryId)?.task ?: return false
        return task.route == OfflineSummaryTaskRouteValue.LONG &&
            task.status in setOf(OfflineSummaryTaskStateValue.FAILED, OfflineSummaryTaskStateValue.INTERRUPTED) &&
            task.pipelineVersion == OfflineSummaryLongContract.PIPELINE_VERSION &&
            task.plannerVersion == OfflineSummaryLongContract.PLANNER_VERSION &&
            task.mapPromptVersion == OfflineSummaryLongContract.MAP_PROMPT_VERSION &&
            task.reducePromptVersion == OfflineSummaryLongContract.REDUCE_PROMPT_VERSION &&
            task.finalPromptVersion == OfflineSummaryLongContract.FINAL_PROMPT_VERSION &&
            task.intermediateSchemaVersion == OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION
    }

    /**
     * Returns the current long-summary product phase/progress directly from the durable Room task.
     * Direct summaries and terminal Long tasks return null. UI must never infer this from message text.
     */
    suspend fun offlineSummaryLongProductProgress(summaryId: String): OfflineSummaryLongProductProgress? {
        val snapshot = offlineSummaryLongPersistence.snapshot(summaryId) ?: return null
        return buildOfflineSummaryLongProductProgress(snapshot.task, snapshot.nodes)
    }

    /** Builds the exact already-resolved effective transcription lineage; it never re-selects current. */
    suspend fun buildStructuredTranscriptSnapshot(
        transcriptionId: String,
        revisionId: String?,
    ): StructuredTranscriptInput =
        structuredTranscriptInputBuilder.buildSnapshot(
            transcriptionId = transcriptionId,
            revisionId = revisionId,
        )

    /** Single existing final-summary write boundary reused by Direct and Long execution. */
    fun offlineSummaryPersistenceRepository(): OfflineSummaryPersistenceRepository =
        offlineSummaryPersistence

    /** Stage 17.3 durable MAP/REDUCE checkpoint boundary over Room v17. */
    fun offlineSummaryLongPersistenceRepository(): OfflineSummaryLongPersistenceRepository =
        offlineSummaryLongPersistence
}

internal fun isTerminalOfflineSummaryResumeFailure(errorCode: String?): Boolean =
    errorCode == OfflineSummaryFailureCode.REPAIR_FAILED.name

internal fun buildOfflineSummaryLongProductProgress(
    task: OfflineSummaryTaskEntity,
    nodes: List<OfflineSummaryNodeEntity>,
): OfflineSummaryLongProductProgress? {
    if (task.route != OfflineSummaryTaskRouteValue.LONG) return null
    return when (task.status) {
        OfflineSummaryTaskStateValue.PLANNING ->
            OfflineSummaryLongProductProgress(OfflineSummaryLongProductStage.PLANNING)

        OfflineSummaryTaskStateValue.MAPPING -> {
            val mapNodes =
                nodes
                    .filter { it.nodeType == OfflineSummaryNodeTypeValue.MAP && it.level == 0 }
                    .sortedBy { it.ordinal }
            val total = task.totalLeafChunks.takeIf { it > 0 } ?: mapNodes.size.takeIf { it > 0 }
            val current = total?.let { productStep(mapNodes, it) }
            OfflineSummaryLongProductProgress(
                stage = OfflineSummaryLongProductStage.MAPPING,
                current = current,
                total = total,
            )
        }

        OfflineSummaryTaskStateValue.REDUCING -> {
            val level = task.currentLevel.takeIf { it > 0 }
            val levelNodes =
                level?.let { currentLevel ->
                    nodes
                        .filter {
                            it.nodeType == OfflineSummaryNodeTypeValue.REDUCE &&
                                it.level == currentLevel
                        }
                        .sortedBy { it.ordinal }
                }.orEmpty()
            val total = levelNodes.size.takeIf { it > 0 }
            val current = total?.let { productStep(levelNodes, it) }
            OfflineSummaryLongProductProgress(
                stage = OfflineSummaryLongProductStage.REDUCING,
                current = current,
                total = total,
                level = level,
            )
        }

        OfflineSummaryTaskStateValue.VALIDATING ->
            OfflineSummaryLongProductProgress(OfflineSummaryLongProductStage.VALIDATING)

        else -> null
    }
}

private fun productStep(
    nodes: List<OfflineSummaryNodeEntity>,
    total: Int,
): Int {
    val runningIndex = nodes.indexOfFirst { it.status == OfflineSummaryNodeStateValue.RUNNING }
    if (runningIndex >= 0) return (runningIndex + 1).coerceIn(1, total)

    val nextIndex = nodes.indexOfFirst { it.status != OfflineSummaryNodeStateValue.VALIDATED }
    if (nextIndex >= 0) return (nextIndex + 1).coerceIn(1, total)

    return total
}
