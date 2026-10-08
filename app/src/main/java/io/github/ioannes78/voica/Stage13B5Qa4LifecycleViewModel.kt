package io.github.ioannes78.voica

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.RecordingCandidateAttentionEntity
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.Stage13B5Qa4Repository
import io.github.ioannes78.voica.database.TranscriptionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class Stage13B5Qa4LifecycleViewModel(
    private val qa4Repository: Stage13B5Qa4Repository,
    private val aiSummaryRepository: AiSummaryRepository,
    private val contentRepository: Stage12CContentRepository,
) : ViewModel() {
    private val mutableCandidateAttention =
        MutableStateFlow<RecordingCandidateAttentionEntity?>(null)
    val candidateAttention: StateFlow<RecordingCandidateAttentionEntity?> =
        mutableCandidateAttention.asStateFlow()

    private val mutableTranscriptionAttention = MutableStateFlow<TranscriptionEntity?>(null)
    val transcriptionAttention: StateFlow<TranscriptionEntity?> =
        mutableTranscriptionAttention.asStateFlow()

    private val mutableAiSummaryAttention = MutableStateFlow<AiSummaryEntity?>(null)
    val aiSummaryAttention: StateFlow<AiSummaryEntity?> =
        mutableAiSummaryAttention.asStateFlow()

    private val mutableCurrentTranscriptionId = MutableStateFlow<String?>(null)
    val currentTranscriptionId: StateFlow<String?> = mutableCurrentTranscriptionId.asStateFlow()

    private val mutableCurrentAiSummaryId = MutableStateFlow<String?>(null)
    val currentAiSummaryId: StateFlow<String?> = mutableCurrentAiSummaryId.asStateFlow()

    private var boundRecordingId: String? = null
    private var candidateJob: Job? = null
    private var transcriptionAttentionJob: Job? = null
    private var selectionJob: Job? = null
    private var aiAttentionJob: Job? = null

    fun bind(recordingId: String) {
        require(recordingId.isNotBlank())
        if (boundRecordingId == recordingId) return
        boundRecordingId = recordingId
        candidateJob?.cancel()
        transcriptionAttentionJob?.cancel()
        selectionJob?.cancel()
        aiAttentionJob?.cancel()
        mutableCandidateAttention.value = null
        mutableTranscriptionAttention.value = null
        mutableAiSummaryAttention.value = null
        mutableCurrentTranscriptionId.value = null
        mutableCurrentAiSummaryId.value = null

        candidateJob =
            viewModelScope.launch {
                qa4Repository.observeCandidateAttention(recordingId).collectLatest {
                    if (boundRecordingId == recordingId) {
                        mutableCandidateAttention.value = it
                    }
                }
            }
        transcriptionAttentionJob =
            viewModelScope.launch {
                qa4Repository.observeTranscriptionAttentionForRecording(recordingId).collectLatest {
                    if (boundRecordingId == recordingId) {
                        mutableTranscriptionAttention.value = it
                    }
                }
            }
        selectionJob =
            viewModelScope.launch {
                contentRepository.observeContentSelection(recordingId).collectLatest { selection ->
                    if (boundRecordingId == recordingId) {
                        mutableCurrentTranscriptionId.value = selection?.currentTranscriptionId
                        mutableCurrentAiSummaryId.value = selection?.currentAiSummaryId
                    }
                }
            }
        aiAttentionJob =
            viewModelScope.launch {
                combine(
                    aiSummaryRepository.observeForRecording(recordingId),
                    contentRepository.observeContentSelection(recordingId),
                ) { summaries, selection ->
                    resolveQa4AiSummaryAttention(
                        summaries = summaries,
                        persistedCurrentSummaryId = selection?.currentAiSummaryId,
                    )
                }.collectLatest { attention ->
                    if (boundRecordingId == recordingId) {
                        mutableAiSummaryAttention.value = attention
                    }
                }
            }
    }

    fun dismissTranscriptionCandidate(candidateId: String) {
        val recordingId = boundRecordingId ?: return
        if (candidateId.isBlank()) return
        viewModelScope.launch {
            qa4Repository.dismissTranscriptionCandidate(recordingId, candidateId)
        }
    }

    fun dismissAiSummaryCandidate(candidateId: String) {
        val recordingId = boundRecordingId ?: return
        if (candidateId.isBlank()) return
        viewModelScope.launch {
            qa4Repository.dismissAiSummaryCandidate(recordingId, candidateId)
        }
    }

    fun dismissStaleSummary(fingerprint: String) {
        val recordingId = boundRecordingId ?: return
        if (fingerprint.isBlank()) return
        viewModelScope.launch {
            qa4Repository.dismissStaleSummary(recordingId, fingerprint)
        }
    }

    fun ignoreTranscriptionAttention(transcriptionId: String) {
        if (transcriptionId.isBlank()) return
        viewModelScope.launch {
            qa4Repository.acknowledgeTranscriptionAttention(transcriptionId)
        }
    }

    fun ignoreAiSummaryAttention(summaryId: String) {
        if (summaryId.isBlank()) return
        viewModelScope.launch {
            aiSummaryRepository.acknowledgeTerminal(summaryId)
        }
    }

    class Factory(
        private val qa4Repository: Stage13B5Qa4Repository,
        private val aiSummaryRepository: AiSummaryRepository,
        private val contentRepository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            Stage13B5Qa4LifecycleViewModel(
                qa4Repository = qa4Repository,
                aiSummaryRepository = aiSummaryRepository,
                contentRepository = contentRepository,
            ) as T
    }
}

internal fun resolveQa4AiSummaryAttention(
    summaries: List<AiSummaryEntity>,
    persistedCurrentSummaryId: String?,
): AiSummaryEntity? {
    val retryParents = summaries.mapNotNullTo(mutableSetOf()) { it.retryOfSummaryId }
    val explicitCurrent =
        persistedCurrentSummaryId?.let { currentId ->
            summaries.firstOrNull {
                it.id == currentId && it.status == AiSummaryStateValue.COMPLETED
            }
        }
    return summaries.firstOrNull { summary ->
        if (
            summary.status !in QA4_AI_ATTENTION_STATES ||
            summary.executionGeneration <= 0L ||
            summary.terminalAcknowledgedAtMs != null ||
            summary.id in retryParents
        ) {
            return@firstOrNull false
        }
        if (explicitCurrent != null && explicitCurrent.createdAtMs > summary.createdAtMs) {
            return@firstOrNull false
        }
        if (
            summary.status in QA4_REPLACEABLE_AI_ATTENTION_STATES &&
            summaries.any { newer -> newer.createdAtMs > summary.createdAtMs }
        ) {
            return@firstOrNull false
        }
        true
    }
}

private val QA4_AI_ATTENTION_STATES =
    setOf(
        AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
        AiSummaryStateValue.FAILED,
        AiSummaryStateValue.INTERRUPTED,
    )

private val QA4_REPLACEABLE_AI_ATTENTION_STATES =
    setOf(
        AiSummaryStateValue.FAILED,
        AiSummaryStateValue.INTERRUPTED,
    )
