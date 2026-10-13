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

    @Query(
        """
        SELECT * FROM realtime_transcription_runs
        WHERE recordingId IS NOT NULL AND state = 'COMPLETED'
        ORDER BY startedAtMs ASC
        """,
    )
    suspend fun getCompletedAttachedRuns(): List<RealtimeTranscriptionRunEntity>

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

    @Query("SELECT * FROM realtime_media_segments WHERE id = :segmentId LIMIT 1")
    suspend fun getSegment(segmentId: String): RealtimeMediaSegmentEntity?

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
        SELECT COALESCE(MAX(segmentIndex), -1) + 1
        FROM realtime_media_segments
        WHERE runId = :runId
        """,
    )
    suspend fun nextSegmentIndex(runId: String): Int

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
        SELECT COALESCE(MAX(utteranceIndex), -1) + 1
        FROM realtime_utterances
        WHERE segmentId = :segmentId
        """,
    )
    suspend fun nextUtteranceIndex(segmentId: String): Int

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

data class NewRealtimeMediaSegment(
    val runId: String,
    val transportGeneration: Long?,
    val canonicalSessionId: Long,
    val startedAtMs: Long,
    val localRelativePath: String? = null,
    val localFilename: String? = null,
)

data class NewRealtimeFinalUtterance(
    val segmentId: String,
    val canonicalEpochId: Long,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val rawFinalText: String,
    val finalText: String,
    val detectedLanguage: String?,
    val createdAtMs: Long,
) {
    init {
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive >= startSampleIndex)
    }
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
        require(
            terminalState == RealtimeMediaSegmentStateValue.FINALIZED ||
                terminalState == RealtimeMediaSegmentStateValue.INTERRUPTED ||
                terminalState == RealtimeMediaSegmentStateValue.FAILED,
        ) { "segment finalization requires a known terminal state" }
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

    suspend fun getCompletedAttachedRuns(): List<RealtimeTranscriptionRunEntity> =
        dao.getCompletedAttachedRuns()

    /** Raw insert retained for migration/exact-index tests. Product code should prefer [createNextSegment]. */
    suspend fun createSegment(segment: RealtimeMediaSegmentEntity) = dao.insertSegment(segment)

    suspend fun createNextSegment(segment: NewRealtimeMediaSegment): RealtimeMediaSegmentEntity =
        database.withTransaction {
            require(dao.getRun(segment.runId) != null) { "realtime run does not exist" }
            val entity =
                RealtimeMediaSegmentEntity(
                    id = idFactory(),
                    runId = segment.runId,
                    segmentIndex = dao.nextSegmentIndex(segment.runId),
                    transportGeneration = segment.transportGeneration,
                    canonicalSessionId = segment.canonicalSessionId,
                    state = RealtimeMediaSegmentStateValue.ACTIVE,
                    startedAtMs = segment.startedAtMs,
                    endedAtMs = null,
                    endReason = null,
                    canonicalSampleCount = 0,
                    localRelativePath = segment.localRelativePath,
                    localFilename = segment.localFilename,
                    deviceFilename = null,
                    sizeBytes = null,
                    sha256 = null,
                    isCompleteDeviceRecording = false,
                )
            dao.insertSegment(entity)
            entity
        }

    suspend fun updateSegment(segment: RealtimeMediaSegmentEntity) = dao.updateSegment(segment)

    suspend fun getSegments(runId: String): List<RealtimeMediaSegmentEntity> = dao.getSegments(runId)

    suspend fun getSegment(segmentId: String): RealtimeMediaSegmentEntity? = dao.getSegment(segmentId)

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

    /** Raw insert retained for migration/exact-index tests. Product code should prefer the overload below. */
    suspend fun persistFinalUtterance(utterance: RealtimeUtteranceEntity) = dao.insertUtterance(utterance)

    suspend fun persistFinalUtterance(utterance: NewRealtimeFinalUtterance): RealtimeUtteranceEntity =
        database.withTransaction {
            require(dao.getSegment(utterance.segmentId) != null) { "realtime media segment does not exist" }
            val entity =
                RealtimeUtteranceEntity(
                    id = idFactory(),
                    segmentId = utterance.segmentId,
                    utteranceIndex = dao.nextUtteranceIndex(utterance.segmentId),
                    canonicalEpochId = utterance.canonicalEpochId,
                    startSampleIndex = utterance.startSampleIndex,
                    endSampleIndexExclusive = utterance.endSampleIndexExclusive,
                    rawFinalText = utterance.rawFinalText,
                    finalText = utterance.finalText,
                    detectedLanguage = utterance.detectedLanguage,
                    createdAtMs = utterance.createdAtMs,
                )
            dao.insertUtterance(entity)
            entity
        }

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