package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

data class SearchDocumentDraft(
    val documentId: String,
    val documentType: String,
    val recordingId: String? = null,
    val transcriptionId: String? = null,
    val revisionId: String? = null,
    val sourceAnchorId: String? = null,
    val aiSummaryId: String? = null,
    val sectionId: String? = null,
    val itemId: String? = null,
    val folderId: String? = null,
    val tagId: String? = null,
    val displayTitle: String,
    val displayText: String,
    val updatedAtMs: Long,
)

class UnifiedSearchRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.searchDao()

    suspend fun state(): SearchIndexStateEntity? = dao.findState()

    fun observeState(): Flow<SearchIndexStateEntity?> = dao.observeState()

    suspend fun upsert(draft: SearchDocumentDraft) {
        require(draft.documentId.isNotBlank())
        require(draft.documentType.isNotBlank())
        require(draft.displayTitle.isNotBlank() || draft.displayText.isNotBlank())
        database.withTransaction {
            deleteInternal(draft.documentId)
            val indexTitle = CjkSearchTokenizer.toIndexText(draft.displayTitle)
            val indexBody = CjkSearchTokenizer.toIndexText(draft.displayText)
            val rowId =
                dao.insertDocument(
                    SearchDocumentEntity(
                        documentId = draft.documentId,
                        documentType = draft.documentType,
                        recordingId = draft.recordingId,
                        transcriptionId = draft.transcriptionId,
                        revisionId = draft.revisionId,
                        sourceAnchorId = draft.sourceAnchorId,
                        aiSummaryId = draft.aiSummaryId,
                        sectionId = draft.sectionId,
                        itemId = draft.itemId,
                        folderId = draft.folderId,
                        tagId = draft.tagId,
                        displayTitle = draft.displayTitle,
                        displayText = draft.displayText,
                        indexTitle = indexTitle,
                        indexBody = indexBody,
                        updatedAtMs = draft.updatedAtMs,
                    ),
                )
            dao.insertFts(
                SearchDocumentFtsEntity(
                    rowId = rowId,
                    documentId = draft.documentId,
                    indexTitle = indexTitle,
                    indexBody = indexBody,
                ),
            )
        }
    }

    suspend fun delete(documentId: String) {
        database.withTransaction {
            deleteInternal(documentId)
        }
    }

    suspend fun deleteForTranscription(transcriptionId: String) {
        database.withTransaction {
            deleteRows(dao.findRowIdsForTranscription(transcriptionId))
        }
    }

    suspend fun deleteForAiSummary(summaryId: String) {
        database.withTransaction {
            deleteRows(dao.findRowIdsForSummary(summaryId))
        }
    }

    suspend fun deleteForRecording(recordingId: String) {
        database.withTransaction {
            deleteRows(dao.findRowIdsForRecording(recordingId))
        }
    }

    suspend fun deleteForFolder(folderId: String) {
        database.withTransaction {
            deleteRows(dao.findRowIdsForFolder(folderId))
        }
    }

    suspend fun deleteForTag(tagId: String) {
        database.withTransaction {
            deleteRows(dao.findRowIdsForTag(tagId))
        }
    }

    suspend fun clearForRebuild() {
        database.withTransaction {
            dao.clearFts()
            dao.clearDocuments()
            dao.upsertState(
                SearchIndexStateEntity(
                    status = SearchIndexStatusValue.REBUILDING,
                    indexedDocumentCount = 0,
                    startedAtMs = nowMs(),
                    updatedAtMs = nowMs(),
                    completedAtMs = null,
                    errorMessage = null,
                ),
            )
        }
    }

    suspend fun markReady(indexedDocumentCount: Long) {
        val now = nowMs()
        dao.upsertState(
            SearchIndexStateEntity(
                status = SearchIndexStatusValue.READY,
                indexedDocumentCount = indexedDocumentCount,
                startedAtMs = dao.findState()?.startedAtMs,
                updatedAtMs = now,
                completedAtMs = now,
                errorMessage = null,
            ),
        )
    }

    suspend fun markFailed(message: String?) {
        val now = nowMs()
        val current = dao.findState()
        dao.upsertState(
            SearchIndexStateEntity(
                status = SearchIndexStatusValue.FAILED,
                indexedDocumentCount = current?.indexedDocumentCount ?: 0,
                startedAtMs = current?.startedAtMs,
                updatedAtMs = now,
                completedAtMs = now,
                errorMessage = message?.take(500),
            ),
        )
    }

    suspend fun search(
        query: String,
        documentTypes: Set<String>? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<SearchDocumentEntity> {
        require(limit in 1..100)
        require(offset >= 0)
        val match = CjkSearchTokenizer.toMatchQuery(query)
        if (match.isBlank()) return emptyList()
        val types = documentTypes?.toList().orEmpty()
        return dao.search(
            matchQuery = match,
            filterByType = if (documentTypes == null) 0 else 1,
            documentTypes = if (types.isEmpty()) listOf("__NONE__") else types,
            limit = limit,
            offset = offset,
        )
    }

    private suspend fun deleteInternal(documentId: String) {
        val rowId = dao.findRowId(documentId) ?: return
        dao.deleteFts(rowId)
        dao.deleteDocument(rowId)
    }

    private suspend fun deleteRows(rowIds: List<Long>) {
        rowIds.forEach { rowId ->
            dao.deleteFts(rowId)
            dao.deleteDocument(rowId)
        }
    }
}
