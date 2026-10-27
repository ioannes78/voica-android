package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import kotlinx.coroutines.flow.Flow

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
