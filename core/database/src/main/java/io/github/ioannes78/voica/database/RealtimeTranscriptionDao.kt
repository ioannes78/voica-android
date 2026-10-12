package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import java.util.UUID

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

    @Query(
        """
        SELECT * FROM realtime_media_segments
        WHERE id = :segmentId AND runId = :runId
        LIMIT 1
        """,
    )
    suspend fun getSegmentForRun(runId: String, segmentId: String): RealtimeMediaSegmentEntity?

    @Query(
        """
        UPDATE realtime_media_segments
        SET state = :terminalState,
            endedAtMs = :endedAtMs,
            endReason = :endReason,
            canonicalSampleCount = :canonicalSampleCount,
            localRelativePath = :localRelativePath,
            localFilename = :localFilename,
            deviceFilename = :deviceFilename,
            sizeBytes = :sizeBytes,
            sha256 = :sha256,
            isCompleteDeviceRecording = :isCompleteDeviceRecording
        WHERE id = :segmentId AND state = :expectedActiveState
        """,
    )
    suspend fun finalizeSegmentIfActive(
        segmentId: String,
        expectedActiveState: String,
        terminalState: String,
        endedAtMs: Long,
        endReason: String?,
        canonicalSampleCount: Long,
        localRelativePath: String?,
        localFilename: String?,
        deviceFilename: String?,
        sizeBytes: Long?,
        sha256: String?,
        isCompleteDeviceRecording: Boolean,
    ): Int

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
        SELECT COALESCE(MAX(markerIndex), -1) + 1
        FROM realtime_timeline_markers
        WHERE runId = :runId
        """,
    )
    suspend fun nextTimelineMarkerIndex(runId: String): Int

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

data class NewRealtimeTimelineMarker(
    val runId: String,
    val segmentId: String?,
    val type: String,
    val canonicalSessionId: Long?,
    val canonicalEpochId: Long?,
    val sampleIndex: Long?,
    val createdAtMs: Long,
    val detail: String? = null,
)

data class RealtimeMediaSegmentFinalization(
    val segmentId: String,
    val terminalState: String,
    val endedAtMs: Long,
    val endReason: String?,
    val canonicalSampleCount: Long,
    val localRelativePath: String?,
    val localFilename: String?,
    val deviceFilename: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val isCompleteDeviceRecording: Boolean,
) {
    init {
        require(terminalState != RealtimeMediaSegmentStateValue.ACTIVE) {
            "segment finalization requires a terminal state"
        }
        require(canonicalSampleCount >= 0L) {
            "canonicalSampleCount must be non-negative"
        }
    }
}

class RealtimeTranscriptPersistenceRepository(
    private val database: VoicaDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
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

    /**
     * Completes an ACTIVE local realtime media segment exactly once.
     *
     * Repeated terminal callbacks (for example STOP followed by device reconciliation) return false
     * and cannot overwrite the first accepted terminal lineage.
     */
    suspend fun finalizeActiveSegment(finalization: RealtimeMediaSegmentFinalization): Boolean =
        dao.finalizeSegmentIfActive(
            segmentId = finalization.segmentId,
            expectedActiveState = RealtimeMediaSegmentStateValue.ACTIVE,
            terminalState = finalization.terminalState,
            endedAtMs = finalization.endedAtMs,
            endReason = finalization.endReason,
            canonicalSampleCount = finalization.canonicalSampleCount,
            localRelativePath = finalization.localRelativePath,
            localFilename = finalization.localFilename,
            deviceFilename = finalization.deviceFilename,
            sizeBytes = finalization.sizeBytes,
            sha256 = finalization.sha256,
            isCompleteDeviceRecording = finalization.isCompleteDeviceRecording,
        ) == 1

    suspend fun persistFinalUtterance(utterance: RealtimeUtteranceEntity) = dao.insertUtterance(utterance)

    suspend fun getFinalUtterances(segmentId: String): List<RealtimeUtteranceEntity> =
        dao.getUtterances(segmentId)

    /** Raw insert retained for migration/exact-index tests. Product code should prefer the overload below. */
    suspend fun appendTimelineMarker(marker: RealtimeTimelineMarkerEntity) = dao.insertTimelineMarker(marker)

    /**
     * Atomically allocates the next marker index inside the run and inserts the durable boundary.
     * A supplied segment must belong to the same run; cross-run marker references are rejected.
     */
    suspend fun appendTimelineMarker(marker: NewRealtimeTimelineMarker): RealtimeTimelineMarkerEntity =
        database.withTransaction {
            marker.segmentId?.let { segmentId ->
                require(dao.getSegmentForRun(marker.runId, segmentId) != null) {
                    "timeline marker segment must belong to run"
                }
            }
            val entity =
                RealtimeTimelineMarkerEntity(
                    id = idFactory(),
                    runId = marker.runId,
                    segmentId = marker.segmentId,
                    markerIndex = dao.nextTimelineMarkerIndex(marker.runId),
                    type = marker.type,
                    canonicalSessionId = marker.canonicalSessionId,
                    canonicalEpochId = marker.canonicalEpochId,
                    sampleIndex = marker.sampleIndex,
                    createdAtMs = marker.createdAtMs,
                    detail = marker.detail,
                )
            dao.insertTimelineMarker(entity)
            entity
        }

    suspend fun getTimelineMarkers(runId: String): List<RealtimeTimelineMarkerEntity> =
        dao.getTimelineMarkers(runId)

    suspend fun deleteRun(runId: String): Boolean = dao.deleteRun(runId) == 1
}
