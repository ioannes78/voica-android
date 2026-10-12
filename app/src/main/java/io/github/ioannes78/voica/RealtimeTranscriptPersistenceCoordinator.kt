package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.database.NewRealtimeFinalUtterance
import io.github.ioannes78.voica.database.NewRealtimeMediaSegment
import io.github.ioannes78.voica.database.NewRealtimeTimelineMarker
import io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity
import io.github.ioannes78.voica.database.RealtimeMediaSegmentFinalization
import io.github.ioannes78.voica.database.RealtimeMediaSegmentStateValue
import io.github.ioannes78.voica.database.RealtimeTimelineMarkerTypeValue
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioResult
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RealtimeTranscriptPersistenceSnapshot(
    val knownCanonicalSessions: Int,
    val pendingLocalAudioResults: Int,
    val reconnectMarkerPending: Boolean,
    val persistedFinalUtterances: Long,
    val persistedMarkers: Long,
    val finalizedMediaSegments: Long,
    val duplicateMediaFinalizations: Long,
)

/**
 * Durable Stage 16A lineage bridge between realtime text/media outputs and Room v13.
 *
 * A physical realtime run may span multiple canonical sessions after transport reconnect. Each
 * canonical session receives one media segment. FINAL transcript output is persisted independently
 * from file-ASR versions, while lifecycle boundaries become durable timeline markers. Local WAV
 * finalization may race ahead of the ASR consumer, so media results are buffered by canonical
 * session identity until the matching Room segment exists.
 */
class RealtimeTranscriptPersistenceCoordinator(
    private val repository: RealtimeTranscriptPersistenceRepository,
    val runId: String,
    private val transportGenerationProvider: () -> Long?,
    private val localPathResolver: (File) -> String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val segmentsBySession = linkedMapOf<Long, RealtimeMediaSegmentEntity>()
    private val pendingLocalAudio = mutableMapOf<Long, PendingLocalAudioCompletion>()
    private val reconnectedSessions = mutableSetOf<Long>()
    private var reconnectMarkerPending = false
    private var persistedFinalUtterances = 0L
    private var persistedMarkers = 0L
    private var finalizedMediaSegments = 0L
    private var duplicateMediaFinalizations = 0L

    suspend fun accept(output: RealtimeTranscriptionOutput) = mutex.withLock {
        when (output) {
            is RealtimeTranscriptionOutput.Hypothesis -> persistHypothesisLocked(output)
            is RealtimeTranscriptionOutput.Lifecycle -> persistLifecycleLocked(output.event)
        }
    }

    /**
     * Called only after the recorder PAUSE transaction has succeeded. The marker uses the canonical
     * media boundary captured at that moment; no wall-clock-derived sample position is fabricated.
     */
    suspend fun onPauseCommitted(
        canonicalSessionId: Long,
        canonicalEpochId: Long?,
        sampleIndex: Long?,
    ) = mutex.withLock {
        requireSegmentLocked(canonicalSessionId)
        appendMarkerLocked(
            sessionId = canonicalSessionId,
            epochId = canonicalEpochId,
            sampleIndex = sampleIndex,
            type = RealtimeTimelineMarkerTypeValue.PAUSE,
            detail = null,
        )
    }

    /** Mark the next canonical session as a same-recording reconnect before local-only attach. */
    suspend fun markNextSessionAsReconnected() = mutex.withLock {
        reconnectMarkerPending = true
    }

    /** Clears a pre-armed reconnect marker when local-only attach fails before a new session opens. */
    suspend fun cancelPendingReconnectMarker() = mutex.withLock {
        reconnectMarkerPending = false
    }

    /**
     * Completes local media independently from text processing. Unknown-yet session ids are retained
     * until their ordered SessionStarted event creates the matching Room segment.
     */
    suspend fun onLocalAudioResult(
        result: RealtimeLocalAudioResult,
        deviceFilename: String? = null,
        isCompleteDeviceRecording: Boolean = false,
    ) = mutex.withLock {
        val sessionId = result.canonicalSessionId ?: return@withLock
        val completion =
            PendingLocalAudioCompletion(
                result = result,
                deviceFilename = deviceFilename,
                isCompleteDeviceRecording = isCompleteDeviceRecording,
            )
        if (segmentsBySession[sessionId] == null) {
            pendingLocalAudio.putIfAbsent(sessionId, completion)
            return@withLock
        }
        applyLocalAudioCompletionLocked(completion)
    }

    suspend fun attachRecording(recordingId: String): Boolean = mutex.withLock {
        repository.attachRecording(runId, recordingId, clockMs())
    }

    suspend fun finalizeRun(
        state: String,
        endReason: String?,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = mutex.withLock {
        require(
            state == RealtimeTranscriptionStateValue.COMPLETED ||
                state == RealtimeTranscriptionStateValue.INTERRUPTED ||
                state == RealtimeTranscriptionStateValue.CANCELLED ||
                state == RealtimeTranscriptionStateValue.FAILED,
        ) { "realtime run requires a terminal state" }
        val run = checkNotNull(repository.getRun(runId)) { "realtime run does not exist" }
        val now = clockMs()
        repository.updateRun(
            run.copy(
                state = state,
                updatedAtMs = now,
                endedAtMs = now,
                endReason = endReason,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ),
        )
    }

    suspend fun snapshot(): RealtimeTranscriptPersistenceSnapshot = mutex.withLock {
        RealtimeTranscriptPersistenceSnapshot(
            knownCanonicalSessions = segmentsBySession.size,
            pendingLocalAudioResults = pendingLocalAudio.size,
            reconnectMarkerPending = reconnectMarkerPending,
            persistedFinalUtterances = persistedFinalUtterances,
            persistedMarkers = persistedMarkers,
            finalizedMediaSegments = finalizedMediaSegments,
            duplicateMediaFinalizations = duplicateMediaFinalizations,
        )
    }

    private suspend fun persistLifecycleLocked(event: RealtimeAsrPipelineEvent) {
        when (event) {
            is RealtimeAsrPipelineEvent.SessionStarted -> startSegmentLocked(event.sessionId)

            is RealtimeAsrPipelineEvent.ContinuityStarted -> {
                requireSegmentLocked(event.sessionId)
                if (event.epochReason == RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY) {
                    appendMarkerLocked(
                        sessionId = event.sessionId,
                        epochId = event.epochId,
                        sampleIndex = event.startSampleIndex ?: 0L,
                        type = RealtimeTimelineMarkerTypeValue.RESUME,
                        detail = null,
                    )
                }
                if (reconnectedSessions.remove(event.sessionId)) {
                    appendMarkerLocked(
                        sessionId = event.sessionId,
                        epochId = event.epochId,
                        sampleIndex = event.startSampleIndex ?: 0L,
                        type = RealtimeTimelineMarkerTypeValue.RECONNECTED,
                        detail = null,
                    )
                }
            }

            is RealtimeAsrPipelineEvent.Discontinuity -> {
                requireSegmentLocked(event.sessionId)
                appendMarkerLocked(
                    sessionId = event.sessionId,
                    epochId = event.epochId,
                    sampleIndex = event.resumeAtSampleIndex,
                    type = RealtimeTimelineMarkerTypeValue.DISCONTINUITY,
                    detail =
                        "reason=${event.reason};droppedFrames=${event.droppedFrames};" +
                            "droppedSamples=${event.droppedSamples}",
                )
            }

            is RealtimeAsrPipelineEvent.SessionEnded -> {
                requireSegmentLocked(event.sessionId)
                if (event.reason == RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED) {
                    appendMarkerLocked(
                        sessionId = event.sessionId,
                        epochId = null,
                        sampleIndex = null,
                        type = RealtimeTimelineMarkerTypeValue.TRANSPORT_CLOSED,
                        detail = if (event.drainTimedOut) "drainTimedOut=true" else null,
                    )
                }
            }

            is RealtimeAsrPipelineEvent.VadSegment -> Unit
            is RealtimeAsrPipelineEvent.Hypothesis ->
                error("hypothesis must arrive as RealtimeTranscriptionOutput.Hypothesis")
        }
    }

    private suspend fun startSegmentLocked(sessionId: Long) {
        if (segmentsBySession.containsKey(sessionId)) return
        val segment =
            repository.createNextSegment(
                NewRealtimeMediaSegment(
                    runId = runId,
                    transportGeneration = transportGenerationProvider(),
                    canonicalSessionId = sessionId,
                    startedAtMs = clockMs(),
                ),
            )
        segmentsBySession[sessionId] = segment
        if (reconnectMarkerPending) {
            reconnectMarkerPending = false
            reconnectedSessions += sessionId
        }
        pendingLocalAudio.remove(sessionId)?.let { pending ->
            applyLocalAudioCompletionLocked(pending)
        }
    }

    private suspend fun persistHypothesisLocked(output: RealtimeTranscriptionOutput.Hypothesis) {
        if (output.stability != AsrHypothesisStability.FINAL) return
        if (output.rawText.isBlank() && output.displayText.isBlank()) return
        val segment = requireSegmentLocked(output.sessionId)
        val source = output.source
        repository.persistFinalUtterance(
            NewRealtimeFinalUtterance(
                segmentId = segment.id,
                canonicalEpochId = source.epochId,
                startSampleIndex = source.recognizerBaseSampleIndex,
                endSampleIndexExclusive = source.acceptedEndSampleIndexExclusive,
                rawFinalText = output.rawText,
                finalText = output.displayText,
                detectedLanguage = output.displayHypothesis.detectedLanguage,
                createdAtMs = clockMs(),
            ),
        )
        persistedFinalUtterances += 1
    }

    private suspend fun applyLocalAudioCompletionLocked(completion: PendingLocalAudioCompletion) {
        val sessionId = checkNotNull(completion.result.canonicalSessionId)
        val segment = requireSegmentLocked(sessionId)
        val finalization =
            when (val result = completion.result) {
                is RealtimeLocalAudioResult.Finalized -> {
                    RealtimeMediaSegmentFinalization(
                        segmentId = segment.id,
                        terminalState = result.endReason.toMediaTerminalState(),
                        endedAtMs = clockMs(),
                        endReason = result.endReason.name,
                        canonicalSampleCount = result.media.sampleCount,
                        localRelativePath = localPathResolver(result.media.file),
                        localFilename = result.media.file.name,
                        deviceFilename = completion.deviceFilename,
                        sizeBytes = result.media.sizeBytes,
                        sha256 = result.media.sha256,
                        isCompleteDeviceRecording =
                            completion.isCompleteDeviceRecording && !result.media.hadDiscontinuity,
                    )
                }

                is RealtimeLocalAudioResult.Failed -> {
                    RealtimeMediaSegmentFinalization(
                        segmentId = segment.id,
                        terminalState = RealtimeMediaSegmentStateValue.FAILED,
                        endedAtMs = clockMs(),
                        endReason = "LOCAL_AUDIO_${result.code.name}",
                        canonicalSampleCount = result.salvagedMedia?.sampleCount ?: 0L,
                        localRelativePath = result.salvagedMedia?.file?.let(localPathResolver),
                        localFilename = result.salvagedMedia?.file?.name,
                        deviceFilename = completion.deviceFilename,
                        sizeBytes = result.salvagedMedia?.sizeBytes,
                        sha256 = result.salvagedMedia?.sha256,
                        isCompleteDeviceRecording = false,
                    )
                }
            }

        if (repository.finalizeActiveSegment(finalization)) {
            finalizedMediaSegments += 1
        } else {
            duplicateMediaFinalizations += 1
        }
    }

    private suspend fun appendMarkerLocked(
        sessionId: Long,
        epochId: Long?,
        sampleIndex: Long?,
        type: String,
        detail: String?,
    ) {
        val segment = requireSegmentLocked(sessionId)
        repository.appendTimelineMarker(
            NewRealtimeTimelineMarker(
                runId = runId,
                segmentId = segment.id,
                type = type,
                canonicalSessionId = sessionId,
                canonicalEpochId = epochId,
                sampleIndex = sampleIndex,
                createdAtMs = clockMs(),
                detail = detail,
            ),
        )
        persistedMarkers += 1
    }

    private fun requireSegmentLocked(sessionId: Long): RealtimeMediaSegmentEntity =
        checkNotNull(segmentsBySession[sessionId]) {
            "realtime canonical session has no durable media segment"
        }

    private fun RealtimeCanonicalSessionEndReason.toMediaTerminalState(): String =
        when (this) {
            RealtimeCanonicalSessionEndReason.STOP,
            RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED,
            -> RealtimeMediaSegmentStateValue.FINALIZED

            RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
            RealtimeCanonicalSessionEndReason.SUPERSEDED_BY_NEW_SESSION,
            RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED,
            -> RealtimeMediaSegmentStateValue.INTERRUPTED
        }

    private data class PendingLocalAudioCompletion(
        val result: RealtimeLocalAudioResult,
        val deviceFilename: String?,
        val isCompleteDeviceRecording: Boolean,
    )
}
