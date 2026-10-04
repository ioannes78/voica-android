package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface Stage12CContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscriptionMetadata(entity: TranscriptionUserMetadataEntity)

    @Query("SELECT * FROM transcription_user_metadata WHERE transcriptionId = :transcriptionId LIMIT 1")
    suspend fun findTranscriptionMetadata(transcriptionId: String): TranscriptionUserMetadataEntity?

    @Query("SELECT * FROM transcription_user_metadata WHERE transcriptionId = :transcriptionId LIMIT 1")
    fun observeTranscriptionMetadata(transcriptionId: String): Flow<TranscriptionUserMetadataEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranscriptionRevision(entity: TranscriptionRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranscriptionRevisionParagraphs(entities: List<TranscriptionRevisionParagraphEntity>)

    @Query("""
        SELECT * FROM transcription_revisions
        WHERE transcriptionId = :transcriptionId
        ORDER BY revisionNumber DESC
    """)
    fun observeTranscriptionRevisions(transcriptionId: String): Flow<List<TranscriptionRevisionEntity>>

    @Query("SELECT * FROM transcription_revisions WHERE id = :revisionId LIMIT 1")
    suspend fun findTranscriptionRevision(revisionId: String): TranscriptionRevisionEntity?

    @Query("""
        SELECT * FROM transcription_revisions
        WHERE transcriptionId = :transcriptionId
        ORDER BY revisionNumber DESC
        LIMIT 1
    """)
    suspend fun findLatestTranscriptionRevision(transcriptionId: String): TranscriptionRevisionEntity?

    @Query("""
        SELECT COALESCE(MAX(revisionNumber), 0)
        FROM transcription_revisions
        WHERE transcriptionId = :transcriptionId
    """)
    suspend fun maxTranscriptionRevisionNumber(transcriptionId: String): Int

    @Query("""
        SELECT * FROM transcription_revision_paragraphs
        WHERE revisionId = :revisionId
        ORDER BY paragraphIndex ASC
    """)
    suspend fun loadTranscriptionRevisionParagraphs(revisionId: String): List<TranscriptionRevisionParagraphEntity>

    @Query("DELETE FROM transcription_revisions WHERE id = :revisionId")
    suspend fun deleteTranscriptionRevision(revisionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiSummaryMetadata(entity: AiSummaryUserMetadataEntity)

    @Query("SELECT * FROM ai_summary_user_metadata WHERE aiSummaryId = :summaryId LIMIT 1")
    suspend fun findAiSummaryMetadata(summaryId: String): AiSummaryUserMetadataEntity?

    @Query("SELECT * FROM ai_summary_user_metadata WHERE aiSummaryId = :summaryId LIMIT 1")
    fun observeAiSummaryMetadata(summaryId: String): Flow<AiSummaryUserMetadataEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAiSummaryRevision(entity: AiSummaryRevisionEntity)

    @Query("""
        SELECT * FROM ai_summary_revisions
        WHERE aiSummaryId = :summaryId
        ORDER BY revisionNumber DESC
    """)
    fun observeAiSummaryRevisions(summaryId: String): Flow<List<AiSummaryRevisionEntity>>

    @Query("SELECT * FROM ai_summary_revisions WHERE id = :revisionId LIMIT 1")
    suspend fun findAiSummaryRevision(revisionId: String): AiSummaryRevisionEntity?

    @Query("""
        SELECT * FROM ai_summary_revisions
        WHERE aiSummaryId = :summaryId
        ORDER BY revisionNumber DESC
        LIMIT 1
    """)
    suspend fun findLatestAiSummaryRevision(summaryId: String): AiSummaryRevisionEntity?

    @Query("""
        SELECT COALESCE(MAX(revisionNumber), 0)
        FROM ai_summary_revisions
        WHERE aiSummaryId = :summaryId
    """)
    suspend fun maxAiSummaryRevisionNumber(summaryId: String): Int

    @Query("DELETE FROM ai_summary_revisions WHERE id = :revisionId")
    suspend fun deleteAiSummaryRevision(revisionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContentSelection(entity: RecordingContentSelectionEntity)

    @Query("SELECT * FROM recording_content_selection WHERE recordingId = :recordingId LIMIT 1")
    suspend fun findContentSelection(recordingId: String): RecordingContentSelectionEntity?

    @Query("SELECT * FROM recording_content_selection WHERE recordingId = :recordingId LIMIT 1")
    fun observeContentSelection(recordingId: String): Flow<RecordingContentSelectionEntity?>
}
