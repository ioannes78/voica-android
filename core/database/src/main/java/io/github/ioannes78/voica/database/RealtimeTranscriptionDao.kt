package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RealtimeTranscriptionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RealtimeTranscriptionRunEntity)

    @Update
    suspend fun updateRun(run: RealtimeTranscriptionRunEntity)

    @Query("SELECT * FROM realtime_transcription_runs WHERE id = :runId")
    suspend fun getRun(runId: String): RealtimeTranscriptionRunEntity?

    @Query(
        """
        UPDATE realtime_transcription_runs
        SET recordingId = :recordingId, updatedAtMs = :updatedAtMs
        WHERE id = :runId
        """,
    )
    suspend fun attachRecording(runId: String, recordingId: String, updatedAtMs: Long): Int

    @Query(
        """
        SELECT * FROM realtime_transcription_runs
        WHERE recordingId = :recordingId
        ORDER BY startedAtMs DESC
        """,
    )
    suspend fun getRunsForRecording(recordingId: String): List<RealtimeTranscriptionRunEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: RealtimeMediaSegmentEntity)

    @Update
    suspend fun updateSegment(segment: RealtimeMediaSegmentEntity)

    @Query(
        """
        SELECT * FROM realtime_media_segments
        WHERE runId = :runId
        ORDER BY segmentIndex ASC
        """,
    )
    suspend fun getSegments(runId: String): List<RealtimeMediaSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUtterance(utterance: RealtimeUtteranceEntity)

    @Query(
        """
        SELECT * FROM realtime_utterances
        WHERE segmentId = :segmentId
        ORDER BY utteranceIndex ASC
        """,
    )
    suspend fun getUtterances(segmentId: String): List<RealtimeUtteranceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimelineMarker(marker: RealtimeTimelineMarkerEntity)

    @Query(
        """
        SELECT * FROM realtime_timeline_markers
        WHERE runId = :runId
        ORDER BY markerIndex ASC
        """,
    )
    suspend fun getTimelineMarkers(runId: String): List<RealtimeTimelineMarkerEntity>

    @Query("DELETE FROM realtime_transcription_runs WHERE id = :runId")
    suspend fun deleteRun(runId: String): Int
}

class RealtimeTranscriptPersistenceRepository(
    database: VoicaDatabase,
) {
    private val dao = database.realtimeTranscriptionDao()

    suspend fun createRun(run: RealtimeTranscriptionRunEntity) = dao.insertRun(run)

    suspend fun updateRun(run: RealtimeTranscriptionRunEntity) = dao.updateRun(run)

    suspend fun attachRecording(runId: String, recordingId: String, updatedAtMs: Long): Boolean =
        dao.attachRecording(runId, recordingId, updatedAtMs) == 1

    suspend fun getRun(runId: String): RealtimeTranscriptionRunEntity? = dao.getRun(runId)

    suspend fun getRunsForRecording(recordingId: String): List<RealtimeTranscriptionRunEntity> =
        dao.getRunsForRecording(recordingId)

    suspend fun createSegment(segment: RealtimeMediaSegmentEntity) = dao.insertSegment(segment)

    suspend fun updateSegment(segment: RealtimeMediaSegmentEntity) = dao.updateSegment(segment)

    suspend fun getSegments(runId: String): List<RealtimeMediaSegmentEntity> = dao.getSegments(runId)

    suspend fun persistFinalUtterance(utterance: RealtimeUtteranceEntity) = dao.insertUtterance(utterance)

    suspend fun getFinalUtterances(segmentId: String): List<RealtimeUtteranceEntity> =
        dao.getUtterances(segmentId)

    suspend fun appendTimelineMarker(marker: RealtimeTimelineMarkerEntity) = dao.insertTimelineMarker(marker)

    suspend fun getTimelineMarkers(runId: String): List<RealtimeTimelineMarkerEntity> =
        dao.getTimelineMarkers(runId)

    suspend fun deleteRun(runId: String): Boolean = dao.deleteRun(runId) == 1
}
