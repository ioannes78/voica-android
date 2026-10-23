package io.github.ioannes78.voica.database

import kotlinx.coroutines.flow.Flow

/** Provider-neutral access to retained historical summary content. */
class AiSummaryRepository(
    private val database: VoicaDatabase,
) {
    private val dao = database.aiSummaryDao()
    private val transcriptionDao = database.transcriptionDao()

    fun observeForRecording(recordingId: String): Flow<List<AiSummaryEntity>> =
        dao.observeForRecording(recordingId)

    suspend fun find(summaryId: String): AiSummaryEntity? =
        dao.findSummary(summaryId)

    suspend fun recordingIdForTranscription(transcriptionId: String): String? =
        transcriptionDao.findTranscription(transcriptionId)?.recordingId

    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity> =
        dao.loadEvidence(summaryId)
}
