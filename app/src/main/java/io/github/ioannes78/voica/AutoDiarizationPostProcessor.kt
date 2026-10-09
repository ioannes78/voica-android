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
    private var activeDecision: PendingDecision? = null

    init {
        scope.launch {
            transcriptionCoordinator.state.collect { state ->
                when (state) {
                    is TranscriptionRunState.Running -> onTranscriptionRunning(state)
                    is TranscriptionRunState.Completed -> onTranscriptionCompleted(state)
                    is TranscriptionRunState.Failed -> {
                        clearPending(state.recordingId)
                        clearPrepared(state.recordingId)
                        clearActiveDecision(state.recordingId)
                    }
                    is TranscriptionRunState.Cancelled -> {
                        clearPending(state.recordingId)
                        clearPrepared(state.recordingId)
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

    /**
     * Snapshot the per-file speaker mode before the first transcription starts.
     * Later global-setting changes must not change the diarization that follows this ASR run.
     */
    fun prepareInitialTranscription(
        recordingId: String,
        speakerCount: SpeakerCountChoice,
    ) {
        require(recordingId.isNotBlank())
        writePrepared(
            recordingId = recordingId,
            decision =
                PendingDecision(
                    mode = PendingMode.AUTO_START,
                    speakerCount = speakerCount,
                ),
        )
    }

    /**
     * A re-transcription may reuse an already valid speaker timeline, but it must never create
     * a new diarization task implicitly. The new transcript will only be realigned when a
     * compatible completed diarization already exists (or an explicitly started one completes).
     */
    fun prepareRetranscription(recordingId: String) {
        require(recordingId.isNotBlank())
        writePrepared(
            recordingId = recordingId,
            decision =
                PendingDecision(
                    mode = PendingMode.REUSE_ONLY,
                    speakerCount = null,
                ),
        )
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
            val prepared = consumePrepared(state.recordingId)
            val decision =
                prepared ?: PendingDecision(
                    mode = PendingMode.AUTO_START,
                    speakerCount = localSpeechSettings().speakerCount,
                )
            val enabled =
                decision.mode == PendingMode.REUSE_ONLY ||
                    localSpeechSettings().diarization.autoAfterTranscription
            activeDecision = decision.takeIf { enabled }
            activeDecision?.let { active ->
                markPending(
                    recordingId = state.recordingId,
                    transcriptionId = state.transcriptionId,
                    decision = active,
                )
            }
        } else if (state.transcriptionId != null) {
            activeDecision?.let { active ->
                markPending(
                    recordingId = state.recordingId,
                    transcriptionId = state.transcriptionId,
                    decision = active,
                )
            }
        }
    }

    private fun onTranscriptionCompleted(state: TranscriptionRunState.Completed) {
        if (isPending(state.recordingId)) {
            val decision = pendingDecision(state.recordingId) ?: activeDecision
            if (decision != null) {
                markPending(
                    recordingId = state.recordingId,
                    transcriptionId = state.transcriptionId,
                    decision = decision,
                )
            }
            scope.launch {
                processPending(state.recordingId)
            }
        }
        clearActiveDecision(state.recordingId)
    }

    private fun clearActiveDecision(recordingId: String) {
        if (activeDecisionRecordingId == recordingId) {
            activeDecisionRecordingId = null
            activeDecision = null
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

            val decision =
                pendingDecision(recordingId) ?: PendingDecision(
                    mode = PendingMode.AUTO_START,
                    speakerCount = localSpeechSettings().speakerCount,
                )
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

            if (decision.mode == PendingMode.REUSE_ONLY) {
                clearPending(recordingId)
                return@withLock
            }

            // start() is already globally idempotent with respect to the coordinator slot.
            // If another recording owns the slot, pending remains durable and is retried
            // when that operation reaches a terminal state.
            diarizationCoordinator.start(
                recordingId = recordingId,
                speakerCountChoice = decision.speakerCount ?: localSpeechSettings().speakerCount,
            )
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
        decision: PendingDecision,
    ) {
        val updated = pendingRecordingIds().toMutableSet().apply { add(recordingId) }
        preferences.edit()
            .putStringSet(KEY_PENDING_RECORDINGS, updated)
            .putString(KEY_MODE_PREFIX + recordingId, decision.mode.name)
            .apply {
                if (transcriptionId != null) {
                    putString(KEY_TRANSCRIPTION_PREFIX + recordingId, transcriptionId)
                }
                if (decision.speakerCount != null) {
                    putString(KEY_SPEAKER_COUNT_PREFIX + recordingId, decision.speakerCount.name)
                } else {
                    remove(KEY_SPEAKER_COUNT_PREFIX + recordingId)
                }
            }
            .commit()
    }

    private fun clearPending(recordingId: String) {
        val updated = pendingRecordingIds().toMutableSet().apply { remove(recordingId) }
        preferences.edit()
            .putStringSet(KEY_PENDING_RECORDINGS, updated)
            .remove(KEY_TRANSCRIPTION_PREFIX + recordingId)
            .remove(KEY_MODE_PREFIX + recordingId)
            .remove(KEY_SPEAKER_COUNT_PREFIX + recordingId)
            .commit()
    }

    private fun writePrepared(
        recordingId: String,
        decision: PendingDecision,
    ) {
        preferences.edit()
            .putString(KEY_PREPARED_MODE_PREFIX + recordingId, decision.mode.name)
            .apply {
                if (decision.speakerCount != null) {
                    putString(
                        KEY_PREPARED_SPEAKER_COUNT_PREFIX + recordingId,
                        decision.speakerCount.name,
                    )
                } else {
                    remove(KEY_PREPARED_SPEAKER_COUNT_PREFIX + recordingId)
                }
            }
            .commit()
    }

    private fun consumePrepared(recordingId: String): PendingDecision? {
        val mode =
            preferences.getString(KEY_PREPARED_MODE_PREFIX + recordingId, null)
                ?.let { raw -> runCatching { PendingMode.valueOf(raw) }.getOrNull() }
                ?: return null
        val speakerCount =
            preferences.getString(KEY_PREPARED_SPEAKER_COUNT_PREFIX + recordingId, null)
                ?.let { raw -> runCatching { SpeakerCountChoice.valueOf(raw) }.getOrNull() }
        clearPrepared(recordingId)
        return PendingDecision(mode = mode, speakerCount = speakerCount)
    }

    private fun clearPrepared(recordingId: String) {
        preferences.edit()
            .remove(KEY_PREPARED_MODE_PREFIX + recordingId)
            .remove(KEY_PREPARED_SPEAKER_COUNT_PREFIX + recordingId)
            .commit()
    }

    private fun pendingDecision(recordingId: String): PendingDecision? {
        val mode =
            preferences.getString(KEY_MODE_PREFIX + recordingId, null)
                ?.let { raw -> runCatching { PendingMode.valueOf(raw) }.getOrNull() }
                ?: return null
        val speakerCount =
            preferences.getString(KEY_SPEAKER_COUNT_PREFIX + recordingId, null)
                ?.let { raw -> runCatching { SpeakerCountChoice.valueOf(raw) }.getOrNull() }
        return PendingDecision(mode = mode, speakerCount = speakerCount)
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

    private data class PendingDecision(
        val mode: PendingMode,
        val speakerCount: SpeakerCountChoice?,
    )

    private enum class PendingMode {
        AUTO_START,
        REUSE_ONLY,
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-auto-diarization"
        const val KEY_PENDING_RECORDINGS = "pending-recordings"
        const val KEY_TRANSCRIPTION_PREFIX = "transcription."
        const val KEY_MODE_PREFIX = "mode."
        const val KEY_SPEAKER_COUNT_PREFIX = "speaker-count."
        const val KEY_PREPARED_MODE_PREFIX = "prepared-mode."
        const val KEY_PREPARED_SPEAKER_COUNT_PREFIX = "prepared-speaker-count."
    }
}
