package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranscription(transcription: TranscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: TranscriptSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTokens(tokens: List<TranscriptTokenEntity>)

    @Query(
        """
        SELECT * FROM transcriptions
        WHERE id = :transcriptionId
        LIMIT 1
        """,
    )
    suspend fun findTranscription(transcriptionId: String): TranscriptionEntity?

    @Query(
        """
        SELECT * FROM transcriptions
        WHERE recordingId = :recordingId
        ORDER BY createdAtMs DESC
        """,
    )
    fun observeVersions(recordingId: String): Flow<List<TranscriptionEntity>>

    @Query(
        """
        SELECT * FROM transcriptions
        WHERE recordingId = :recordingId AND state = 'COMPLETED'
        ORDER BY completedAtMs DESC, createdAtMs DESC
        LIMIT 1
        """,
    )
    fun observeLatestCompleted(recordingId: String): Flow<TranscriptionEntity?>

    @Query(
        """
        SELECT * FROM transcript_segments
        WHERE transcriptionId = :transcriptionId
        ORDER BY segmentIndex ASC
        """,
    )
    fun observeSegments(transcriptionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query(
        """
        SELECT * FROM transcript_segments
        WHERE transcriptionId = :transcriptionId
        ORDER BY segmentIndex ASC
        """,
    )
    suspend fun loadSegments(transcriptionId: String): List<TranscriptSegmentEntity>

    @Query(
        """
        SELECT * FROM transcript_tokens
        WHERE transcriptSegmentId = :segmentId
        ORDER BY source ASC, tokenIndex ASC
        """,
    )
    suspend fun loadTokens(segmentId: String): List<TranscriptTokenEntity>

    @Query(
        """
        SELECT transcript_tokens.* FROM transcript_tokens
        INNER JOIN transcript_segments
            ON transcript_segments.id = transcript_tokens.transcriptSegmentId
        WHERE transcript_segments.transcriptionId = :transcriptionId
        ORDER BY transcript_segments.segmentIndex ASC,
                 transcript_tokens.source ASC,
                 transcript_tokens.tokenIndex ASC
        """,
    )
    suspend fun loadTokensForTranscription(
        transcriptionId: String,
    ): List<TranscriptTokenEntity>

    @Query(
        """
        UPDATE transcriptions
        SET state = :state,
            startedAtMs = COALESCE(startedAtMs, :startedAtMs),
            updatedAtMs = :updatedAtMs,
            completedAtMs = :completedAtMs,
            errorCode = :errorCode,
            errorMessage = :errorMessage
        WHERE id = :transcriptionId
        """,
    )
    suspend fun updateState(
        transcriptionId: String,
        state: String,
        startedAtMs: Long?,
        updatedAtMs: Long,
        completedAtMs: Long?,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        """
        UPDATE transcript_segments
        SET secondPassRawText = :secondPassRawText,
            detectedLanguage = COALESCE(:detectedLanguage, detectedLanguage),
            confidence = COALESCE(:confidence, confidence)
        WHERE id = :segmentId
        """,
    )
    suspend fun updateSecondPass(
        segmentId: String,
        secondPassRawText: String,
        detectedLanguage: String?,
        confidence: Float?,
    ): Int

    @Query(
        """
        UPDATE transcript_segments
        SET finalText = :finalText
        WHERE id = :segmentId
        """,
    )
    suspend fun updateFinalText(
        segmentId: String,
        finalText: String,
    ): Int

    @Query("DELETE FROM transcript_tokens WHERE transcriptSegmentId = :segmentId AND source = :source")
    suspend fun deleteTokens(
        segmentId: String,
        source: String,
    ): Int

    @Query(
        """
        SELECT * FROM transcriptions
        WHERE state IN (:states)
        """,
    )
    suspend fun findByStates(states: List<String>): List<TranscriptionEntity>

    @Query(
        """
        UPDATE transcriptions
        SET state = 'INTERRUPTED',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'PROCESS_INTERRUPTED',
            errorMessage = 'previous transcription was interrupted before completion'
        WHERE state IN (:activeStates)
        """,
    )
    suspend fun markActiveInterrupted(
        activeStates: List<String>,
        nowMs: Long,
    ): Int
}
