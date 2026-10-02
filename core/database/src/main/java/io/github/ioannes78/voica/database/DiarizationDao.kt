package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiarizationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: DiarizationRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpeakers(speakers: List<DiarizationSpeakerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurns(turns: List<SpeakerTurnEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlignment(alignment: TranscriptSpeakerAlignmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpans(spans: List<TranscriptSpeakerSpanEntity>)

    @Query("SELECT * FROM diarization_runs WHERE id = :runId LIMIT 1")
    suspend fun findRun(runId: String): DiarizationRunEntity?

    @Query(
        """
        SELECT * FROM diarization_runs
        WHERE recordingId = :recordingId
        ORDER BY createdAtMs DESC
        """,
    )
    fun observeRuns(recordingId: String): Flow<List<DiarizationRunEntity>>

    @Query(
        """
        SELECT * FROM diarization_runs
        WHERE recordingId = :recordingId AND state = 'COMPLETED'
        ORDER BY completedAtMs DESC, createdAtMs DESC
        LIMIT 1
        """,
    )
    fun observeLatestCompletedRun(recordingId: String): Flow<DiarizationRunEntity?>

    @Query(
        """
        SELECT * FROM diarization_speakers
        WHERE diarizationRunId = :runId
        ORDER BY speakerOrdinal ASC
        """,
    )
    suspend fun loadSpeakers(runId: String): List<DiarizationSpeakerEntity>

    @Query(
        """
        SELECT * FROM speaker_turns
        WHERE diarizationRunId = :runId
        ORDER BY turnIndex ASC
        """,
    )
    suspend fun loadTurns(runId: String): List<SpeakerTurnEntity>

    @Query(
        """
        SELECT * FROM transcript_speaker_alignments
        WHERE transcriptionId = :transcriptionId
        ORDER BY createdAtMs DESC
        """,
    )
    fun observeAlignments(transcriptionId: String): Flow<List<TranscriptSpeakerAlignmentEntity>>

    @Query("SELECT * FROM transcript_speaker_alignments WHERE id = :alignmentId LIMIT 1")
    suspend fun findAlignment(alignmentId: String): TranscriptSpeakerAlignmentEntity?

    @Query(
        """
        SELECT * FROM transcript_speaker_spans
        WHERE alignmentId = :alignmentId
        ORDER BY spanIndex ASC
        """,
    )
    suspend fun loadSpans(alignmentId: String): List<TranscriptSpeakerSpanEntity>

    @Query(
        """
        UPDATE diarization_runs
        SET state = :state,
            startedAtMs = COALESCE(startedAtMs, :startedAtMs),
            updatedAtMs = :updatedAtMs,
            completedAtMs = :completedAtMs,
            errorCode = :errorCode,
            errorMessage = :errorMessage
        WHERE id = :runId
        """,
    )
    suspend fun updateRunState(
        runId: String,
        state: String,
        startedAtMs: Long?,
        updatedAtMs: Long,
        completedAtMs: Long?,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        """
        UPDATE transcript_speaker_alignments
        SET state = :state,
            updatedAtMs = :updatedAtMs,
            completedAtMs = :completedAtMs,
            errorCode = :errorCode,
            errorMessage = :errorMessage
        WHERE id = :alignmentId
        """,
    )
    suspend fun updateAlignmentState(
        alignmentId: String,
        state: String,
        updatedAtMs: Long,
        completedAtMs: Long?,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        """
        UPDATE diarization_speakers
        SET displayName = :displayName
        WHERE id = :speakerId
        """,
    )
    suspend fun updateSpeakerDisplayName(
        speakerId: String,
        displayName: String?,
    ): Int

    @Query(
        """
        UPDATE transcript_speaker_alignments
        SET state = 'INTERRUPTED',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'PROCESS_INTERRUPTED',
            errorMessage = 'previous transcript speaker alignment was interrupted before completion'
        WHERE state IN (:activeStates)
        """,
    )
    suspend fun markActiveAlignmentsInterrupted(
        activeStates: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE diarization_runs
        SET state = 'INTERRUPTED',
            updatedAtMs = :nowMs,
            completedAtMs = :nowMs,
            errorCode = 'PROCESS_INTERRUPTED',
            errorMessage = 'previous diarization was interrupted before completion'
        WHERE state IN (:activeStates)
        """,
    )
    suspend fun markActiveRunsInterrupted(
        activeStates: List<String>,
        nowMs: Long,
    ): Int
}
