package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryEvidenceEntity
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.Stage12CContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AiSummaryDocument(
    val entity: AiSummaryEntity,
    val result: AiSummaryResult?,
    val evidence: List<AiSummaryEvidenceEntity>,
) {
    val evidenceByRef: Map<String, AiSummaryEvidenceEntity> = evidence.associateBy { it.sourceRef }
}

/** Provider-neutral summary reader/editor state. */
class AiSummaryViewModel(
    private val repository: AiSummaryRepository,
    private val contentRepository: Stage12CContentRepository,
) : ViewModel() {
    private val mutableHistory = MutableStateFlow<List<AiSummaryEntity>>(emptyList())
    val history: StateFlow<List<AiSummaryEntity>> =
        mutableHistory
            .map { summaries -> summaries.filter { it.status == AiSummaryStateValue.COMPLETED } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    private val mutableSelected = MutableStateFlow<AiSummaryDocument?>(null)
    val selected: StateFlow<AiSummaryDocument?> = mutableSelected.asStateFlow()

    private val mutableCandidateId = MutableStateFlow<String?>(null)
    val candidateId: StateFlow<String?> = mutableCandidateId.asStateFlow()

    private val mutableStale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = mutableStale.asStateFlow()

    private val mutableStaleFingerprint = MutableStateFlow<String?>(null)
    val staleFingerprint: StateFlow<String?> = mutableStaleFingerprint.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private var boundTranscriptionId: String? = null
    private var boundRecordingId: String? = null
    private var historyJob: Job? = null
    private var revisionJob: Job? = null

    fun bind(transcriptionId: String?) {
        if (boundTranscriptionId == transcriptionId) {
            if (transcriptionId != null) viewModelScope.launch { refreshCurrentSelection() }
            return
        }
        boundTranscriptionId = transcriptionId
        boundRecordingId = null
        historyJob?.cancel()
        revisionJob?.cancel()
        mutableHistory.value = emptyList()
        mutableSelected.value = null
        mutableCandidateId.value = null
        mutableStale.value = false
        mutableStaleFingerprint.value = null
        mutableNotice.value = null
        if (transcriptionId == null) return

        viewModelScope.launch {
            val recordingId = repository.recordingIdForTranscription(transcriptionId) ?: return@launch
            if (boundTranscriptionId != transcriptionId) return@launch
            boundRecordingId = recordingId
            historyJob =
                viewModelScope.launch {
                    repository.observeForRecording(recordingId).collectLatest { summaries ->
                        mutableHistory.value = summaries
                        mutableCandidateId.value = contentRepository.resolveAiSummaryCandidateId(recordingId)
                        refreshCurrentSelection()
                    }
                }
            revisionJob =
                viewModelScope.launch {
                    contentRepository.observeTranscriptionMetadata(transcriptionId)
                        .collectLatest { refreshStale() }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshCurrentSelection() }
    }

    fun selectSummary(summaryId: String) {
        viewModelScope.launch {
            val entity =
                mutableHistory.value.firstOrNull { it.id == summaryId }
                    ?: repository.find(summaryId)
                    ?: return@launch
            if (entity.status != AiSummaryStateValue.COMPLETED) return@launch
            loadDocument(entity)
            refreshStale()
        }
    }

    fun adoptSummaryResult(summaryId: String) {
        viewModelScope.launch {
            val entity = repository.find(summaryId) ?: return@launch
            if (entity.status != AiSummaryStateValue.COMPLETED) return@launch
            contentRepository.setCurrentAiSummaryVersion(entity.recordingId, entity.id)
            mutableCandidateId.value = contentRepository.resolveAiSummaryCandidateId(entity.recordingId)
            loadDocument(entity)
            refreshStale()
            mutableNotice.value = "已使用该总结版本。"
        }
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private suspend fun refreshCurrentSelection() {
        val recordingId = boundRecordingId ?: return
        val currentId = contentRepository.resolveCurrentAiSummaryId(recordingId)
        mutableCandidateId.value = contentRepository.resolveAiSummaryCandidateId(recordingId)
        val target =
            mutableHistory.value.firstOrNull {
                it.id == currentId && it.status == AiSummaryStateValue.COMPLETED
            }
        if (target == null) {
            mutableSelected.value = null
            mutableStale.value = false
            mutableStaleFingerprint.value = null
        } else if (mutableSelected.value?.entity?.id != target.id) {
            loadDocument(target)
            refreshStale()
        } else {
            refreshStale()
        }
    }

    private suspend fun refreshStale() {
        val recordingId = boundRecordingId ?: return
        val summary = mutableSelected.value?.entity
        if (summary == null || summary.status != AiSummaryStateValue.COMPLETED) {
            mutableStale.value = false
            mutableStaleFingerprint.value = null
            return
        }
        val effective = contentRepository.resolveEffectiveTranscription(recordingId)
        val lineage = parseSummaryLineage(summary.sourceLineageSnapshot)
        val stale =
            isSummaryStale(
                lineageTranscriptionId = lineage.transcriptionId,
                lineageRevisionId = lineage.revisionId,
                effective = effective,
            )
        mutableStale.value = stale
        mutableStaleFingerprint.value = if (stale) summaryStaleFingerprint(summary.id, effective) else null
    }

    private fun parseSummaryLineage(raw: String): SummaryLineageRef =
        runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            SummaryLineageRef(
                transcriptionId = root["transcriptionId"]?.jsonPrimitive?.contentOrNull,
                revisionId =
                    root["transcriptionRevisionId"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.contentOrNull,
            )
        }.getOrDefault(SummaryLineageRef(null, null))

    private suspend fun loadDocument(entity: AiSummaryEntity) {
        val evidence = repository.loadEvidence(entity.id)
        val result =
            entity.structuredPayloadJson?.let { raw ->
                runCatching {
                    SummaryResultCodec.decode(
                        raw = raw,
                        allowedEvidenceRefs = evidence.mapTo(LinkedHashSet()) { it.sourceRef },
                    )
                }.getOrNull()
            }
        val productEntity =
            if (entity.promptVersion == OfflineSummaryPromptContract.PROMPT_VERSION) {
                entity.copy(model = LOCAL_OFFLINE_MODEL_LABEL)
            } else {
                entity
            }
        mutableSelected.value =
            AiSummaryDocument(
                entity = productEntity,
                result = result,
                evidence = evidence,
            )
    }

    private data class SummaryLineageRef(
        val transcriptionId: String?,
        val revisionId: String?,
    )

    class Factory(
        private val repository: AiSummaryRepository,
        private val contentRepository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiSummaryViewModel(repository = repository, contentRepository = contentRepository) as T
    }

    private companion object {
        const val LOCAL_OFFLINE_MODEL_LABEL = "Gemma 4 E2B · 本地"
    }
}
