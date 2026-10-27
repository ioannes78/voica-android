package io.github.ioannes78.voica.database

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
