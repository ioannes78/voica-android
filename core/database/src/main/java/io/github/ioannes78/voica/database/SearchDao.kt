package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(entity: SearchDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entity: SearchDocumentFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: SearchIndexStateEntity)

    @Query("SELECT * FROM search_index_state WHERE id = 1 LIMIT 1")
    suspend fun findState(): SearchIndexStateEntity?

    @Query("SELECT * FROM search_index_state WHERE id = 1 LIMIT 1")
    fun observeState(): Flow<SearchIndexStateEntity?>

    @Query("DELETE FROM search_documents_fts WHERE documentId = :documentId")
    suspend fun deleteFts(documentId: String): Int

    @Query("DELETE FROM search_documents WHERE documentId = :documentId")
    suspend fun deleteDocument(documentId: String): Int

    @Query("SELECT documentId FROM search_documents WHERE transcriptionId = :transcriptionId AND documentType = 'TRANSCRIPT_UNIT'")
    suspend fun findDocumentIdsForTranscription(transcriptionId: String): List<String>

    @Query("SELECT documentId FROM search_documents WHERE aiSummaryId = :summaryId")
    suspend fun findDocumentIdsForSummary(summaryId: String): List<String>

    @Query("SELECT documentId FROM search_documents WHERE recordingId = :recordingId")
    suspend fun findDocumentIdsForRecording(recordingId: String): List<String>

    @Query("SELECT documentId FROM search_documents WHERE folderId = :folderId")
    suspend fun findDocumentIdsForFolder(folderId: String): List<String>

    @Query("SELECT documentId FROM search_documents WHERE tagId = :tagId")
    suspend fun findDocumentIdsForTag(tagId: String): List<String>

    @Query("SELECT DISTINCT transcriptionId FROM search_documents WHERE documentType = 'TRANSCRIPT_UNIT' AND transcriptionId IS NOT NULL")
    suspend fun findIndexedTranscriptionIds(): List<String>

    @Query("SELECT DISTINCT aiSummaryId FROM search_documents WHERE aiSummaryId IS NOT NULL")
    suspend fun findIndexedAiSummaryIds(): List<String>

    @Query("SELECT DISTINCT folderId FROM search_documents WHERE documentType = 'FOLDER' AND folderId IS NOT NULL")
    suspend fun findIndexedFolderIds(): List<String>

    @Query("SELECT DISTINCT tagId FROM search_documents WHERE documentType = 'TAG' AND tagId IS NOT NULL")
    suspend fun findIndexedTagIds(): List<String>

    @Query("SELECT COUNT(*) FROM search_documents WHERE documentType NOT IN ('RECORDING', 'FOLDER', 'TAG', 'TRANSCRIPT_UNIT', 'SUMMARY_TITLE_OVERVIEW', 'SUMMARY_ITEM')")
    suspend fun countUnsupportedProductSearchRows(): Int

    @Query("SELECT * FROM search_documents WHERE documentType = 'RECORDING' AND recordingId IN (:recordingIds)")
    suspend fun findRecordingDocuments(recordingIds: List<String>): List<SearchDocumentEntity>

    @Query("SELECT * FROM recording_folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAllFolders(): List<FolderEntity>

    @Query("SELECT * FROM recording_folders WHERE folderId = :folderId LIMIT 1")
    suspend fun findFolder(folderId: String): FolderEntity?

    @Query("SELECT * FROM recording_tags ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAllTags(): List<TagEntity>

    @Query("SELECT * FROM recording_tags WHERE tagId = :tagId LIMIT 1")
    suspend fun findTag(tagId: String): TagEntity?

    @Query("DELETE FROM search_documents_fts")
    suspend fun clearFts(): Int

    @Query("DELETE FROM search_documents")
    suspend fun clearDocuments(): Int

    @Query(
        """
        SELECT d.* FROM search_documents AS d
        WHERE d.documentId IN (
            SELECT documentId
            FROM search_documents_fts
            WHERE search_documents_fts MATCH :matchQuery
        )
          AND (:filterByType = 0 OR d.documentType IN (:documentTypes))
        ORDER BY d.updatedAtMs DESC, d.rowId DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(
        matchQuery: String,
        filterByType: Int,
        documentTypes: List<String>,
        limit: Int,
        offset: Int,
    ): List<SearchDocumentEntity>
}
