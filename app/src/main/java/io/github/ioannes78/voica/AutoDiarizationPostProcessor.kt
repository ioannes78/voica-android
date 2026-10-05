package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the durable "transcription completed -> diarization -> speaker alignment" handoff.
 *
 * The pending intent is stored outside any ViewModel, so leaving the transcript page or
 * recreating UI state cannot suppress automatic diarization.
 */
class AutoDiarizationPostProcessor(
    application: Application,
    private val scope: CoroutineScope,
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val transcriptionRepository: TranscriptionRepository,
    private val diarizationCoordinator: DiarizationCoordinator,
    private val diarizationRepository: DiarizationRepository,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val preferences =
        application.getSharedPreferences(
            PREFERENCES_NAME,
            Application.MODE_PRIVATE,
        )
    private val processMutex = Mutex()
    private var activeDecisionRecordingId: String? = null
    private var activeDecisionEnabled = false

    init {
        scope.launch {
            transcriptionCoordinator.state.collect { state ->
                when (state) {
                    is TranscriptionRunState.Running -> onTranscriptionRunning(state)
                    is TranscriptionRunState.Completed -> onTranscriptionCompleted(state)
                    is TranscriptionRunState.Failed -> {
                        clearPending(state.recordingId)
                        clearActiveDecision(state.recordingId)
                    }
                    is TranscriptionRunState.Cancelled -> {
                        clearPending(state.recordingId)
                        clearActiveDecision(state.recordingId)
                    }
                    TranscriptionRunState.Idle -> Unit
                }
            }
        }

        scope.launch {
            diarizationCoordinator.state.collect { state ->
                when (state) {
                    is DiarizationRunState.Completed -> {
                        processPending(state.recordingId)
                        retryAllPending(exceptRecordingId = state.recordingId)
                    }
                    is DiarizationRunState.Failed -> {
                        clearPending(state.recordingId)
                        retryAllPending(exceptRecordingId = state.recordingId)
                    }
                    is DiarizationRunState.Cancelled -> {
                        clearPending(state.recordingId)
                        retryAllPending(exceptRecordingId = state.recordingId)
                    }
                    DiarizationRunState.Idle,
                    is DiarizationRunState.Running,
                    -> Unit
                }
            }
        }

        scope.launch {
            diarizationCoordinator.alignmentState.collect { state ->
                when (state) {
                    is SpeakerAlignmentRunState.Completed -> {
                        transcriptionRepository.find(state.transcriptionId)
                            ?.recordingId
                            ?.let(::clearPending)
                        retryAllPending()
                    }
                    is SpeakerAlignmentRunState.Failed -> {
                        transcriptionRepository.find(state.transcriptionId)
                            ?.recordingId
                            ?.let(::clearPending)
                        retryAllPending()
                    }
                    is SpeakerAlignmentRunState.Cancelled -> {
                        transcriptionRepository.find(state.transcriptionId)
                            ?.recordingId
                            ?.let(::clearPending)
                        retryAllPending()
                    }
                    SpeakerAlignmentRunState.Idle,
                    is SpeakerAlignmentRunState.Running,
                    -> Unit
                }
            }
        }
    }

    suspend fun recoverPendingOnStartup() {
        pendingRecordingIds().forEach { recordingId ->
            val transcriptionId = pendingTranscriptionId(recordingId)
            if (transcriptionId == null) {
                // A process death happened before a durable transcription id existed.
                clearPending(recordingId)
                return@forEach
            }
            val transcription = transcriptionRepository.find(transcriptionId)
            if (transcription?.state != TranscriptionStateValue.COMPLETED) {
                clearPending(recordingId)
                return@forEach
            }
            processPending(recordingId)
        }
    }

    private fun onTranscriptionRunning(state: TranscriptionRunState.Running) {
        if (activeDecisionRecordingId != state.recordingId) {
            activeDecisionRecordingId = state.recordingId
            activeDecisionEnabled =
                localSpeechSettings().diarization.autoAfterTranscription
            if (activeDecisionEnabled) {
                markPending(state.recordingId, state.transcriptionId)
            }
        } else if (activeDecisionEnabled && state.transcriptionId != null) {
            markPending(state.recordingId, state.transcriptionId)
        }
    }

    private fun onTranscriptionCompleted(state: TranscriptionRunState.Completed) {
        if (isPending(state.recordingId)) {
            markPending(state.recordingId, state.transcriptionId)
            scope.launch {
                processPending(state.recordingId)
            }
        }
        clearActiveDecision(state.recordingId)
    }

    private fun clearActiveDecision(recordingId: String) {
        if (activeDecisionRecordingId == recordingId) {
            activeDecisionRecordingId = null
            activeDecisionEnabled = false
        }
    }

    private suspend fun processPending(recordingId: String) {
        processMutex.withLock {
            if (!isPending(recordingId)) return@withLock
            val transcriptionId =
                pendingTranscriptionId(recordingId) ?: return@withLock
            val transcription =
                transcriptionRepository.find(transcriptionId)
                    ?: run {
                        clearPending(recordingId)
                        return@withLock
                    }
            if (transcription.state != TranscriptionStateValue.COMPLETED) {
                if (transcription.state !in TranscriptionStateValue.ACTIVE) {
                    clearPending(recordingId)
                }
                return@withLock
            }

            val runs = diarizationRepository.observeRuns(recordingId).first()
            val compatibleCompleted =
                runs.firstOrNull { run ->
                    run.state == DiarizationStateValue.COMPLETED &&
                        run.sourceCanonicalSha256 == transcription.sourceCanonicalSha256 &&
                        run.canonicalProfileId == transcription.canonicalProfileId &&
                        run.totalSampleCount == transcription.totalSampleCount
                }

            if (compatibleCompleted != null) {
                val alignments =
                    diarizationRepository.observeAlignments(transcriptionId).first()
                val completedAlignment =
                    alignments.firstOrNull { alignment ->
                        alignment.diarizationRunId == compatibleCompleted.id &&
                            alignment.state == TranscriptSpeakerAlignmentStateValue.COMPLETED
                    }
                if (completedAlignment != null) {
                    clearPending(recordingId)
                    return@withLock
                }

                val activeAlignment =
                    alignments.any { alignment ->
                        alignment.diarizationRunId == compatibleCompleted.id &&
                            alignment.state in TranscriptSpeakerAlignmentStateValue.ACTIVE
                    }
                if (activeAlignment) return@withLock

                diarizationCoordinator.alignTranscription(
                    transcriptionId = transcriptionId,
                    diarizationRunId = compatibleCompleted.id,
                )
                return@withLock
            }

            val activeCompatibleRun =
                runs.any { run ->
                    run.state in DiarizationStateValue.ACTIVE &&
                        run.sourceCanonicalSha256 == transcription.sourceCanonicalSha256 &&
                        run.canonicalProfileId == transcription.canonicalProfileId &&
                        run.totalSampleCount == transcription.totalSampleCount
                }
            if (activeCompatibleRun) return@withLock

            // start() is already globally idempotent with respect to the coordinator slot.
            // If another recording owns the slot, pending remains durable and is retried
            // when that operation reaches a terminal state.
            diarizationCoordinator.start(recordingId)
        }
    }

    private suspend fun retryAllPending(exceptRecordingId: String? = null) {
        pendingRecordingIds()
            .filterNot { it == exceptRecordingId }
            .forEach { processPending(it) }
    }

    private fun markPending(
        recordingId: String,
        transcriptionId: String?,
    ) {
        val updated = pendingRecordingIds().toMutableSet().apply { add(recordingId) }
        preferences.edit()
            .putStringSet(KEY_PENDING_RECORDINGS, updated)
            .apply {
                if (transcriptionId != null) {
                    putString(KEY_TRANSCRIPTION_PREFIX + recordingId, transcriptionId)
                }
            }
            .commit()
    }

    private fun clearPending(recordingId: String) {
        val updated = pendingRecordingIds().toMutableSet().apply { remove(recordingId) }
        preferences.edit()
            .putStringSet(KEY_PENDING_RECORDINGS, updated)
            .remove(KEY_TRANSCRIPTION_PREFIX + recordingId)
            .commit()
    }

    private fun isPending(recordingId: String): Boolean =
        recordingId in pendingRecordingIds()

    private fun pendingRecordingIds(): Set<String> =
        preferences.getStringSet(KEY_PENDING_RECORDINGS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun pendingTranscriptionId(recordingId: String): String? =
        preferences
            .getString(KEY_TRANSCRIPTION_PREFIX + recordingId, null)
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREFERENCES_NAME = "voica-auto-diarization"
        const val KEY_PENDING_RECORDINGS = "pending-recordings"
        const val KEY_TRANSCRIPTION_PREFIX = "transcription."
    }
}
