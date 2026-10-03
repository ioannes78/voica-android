package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    @Query("SELECT rowId FROM search_documents WHERE documentId = :documentId LIMIT 1")
    suspend fun findRowId(documentId: String): Long?

    @Query("DELETE FROM search_documents_fts WHERE rowid = :rowId")
    suspend fun deleteFts(rowId: Long): Int

    @Query("DELETE FROM search_documents WHERE rowId = :rowId")
    suspend fun deleteDocument(rowId: Long): Int

    @Query("DELETE FROM search_documents_fts")
    suspend fun clearFts(): Int

    @Query("DELETE FROM search_documents")
    suspend fun clearDocuments(): Int

    @Query("""
        SELECT d.* FROM search_documents AS d
        INNER JOIN search_documents_fts AS f ON d.rowId = f.rowid
        WHERE search_documents_fts MATCH :matchQuery
        ORDER BY d.updatedAtMs DESC, d.rowId DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun search(
        matchQuery: String,
        limit: Int,
        offset: Int,
    ): List<SearchDocumentEntity>
}
