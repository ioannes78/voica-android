package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.OfflineSummaryCoordinator
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.OfflineSummaryStartOutcome
import io.github.ioannes78.voica.Stage172OfflineSummaryModelContract
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.resolveManagedLocalLlmModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class OfflineSummaryProductUiState(
    val recordingId: String? = null,
    val run: OfflineSummaryRunState? = null,
    val modelReady: Boolean? = null,
    val resumableSummaryId: String? = null,
    val notice: String? = null,
)

/** UI-only observer/controller. The application-scoped coordinator remains the sole LLM owner. */
class OfflineSummaryProductViewModel internal constructor(
    private val coordinator: OfflineSummaryCoordinator,
    private val modelManager: ModelManager,
    private val aiSummaryRepository: AiSummaryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineSummaryProductUiState())
    val state: StateFlow<OfflineSummaryProductUiState> = mutableState.asStateFlow()

    private var coordinatorJob: Job? = null
    private var summaryJob: Job? = null

    fun bind(recordingId: String) {
        val normalized = recordingId.trim()
        require(normalized.isNotEmpty()) { "recordingId must not be blank" }
        if (mutableState.value.recordingId == normalized) {
            mutableState.value = mutableState.value.copy(run = coordinator.state.value[normalized])
            refreshModelStatus()
            return
        }

        coordinatorJob?.cancel()
        summaryJob?.cancel()
        mutableState.value =
            OfflineSummaryProductUiState(
                recordingId = normalized,
                run = coordinator.state.value[normalized],
                modelReady = null,
            )
        coordinatorJob =
            viewModelScope.launch {
                coordinator.state.collectLatest { runs ->
                    val current = mutableState.value
                    if (current.recordingId == normalized) {
                        mutableState.value = current.copy(run = runs[normalized])
                    }
                }
            }
        summaryJob =
            viewModelScope.launch {
                aiSummaryRepository.observeForRecording(normalized).collectLatest { summaries ->
                    val candidates =
                        summaries
                            .asSequence()
                            .filter {
                                it.status == AiSummaryStateValue.FAILED ||
                                    it.status == AiSummaryStateValue.INTERRUPTED
                            }
                            .sortedByDescending { it.updatedAtMs }
                            .toList()
                    var resumable: String? = null
                    for (summary in candidates) {
                        if (aiSummaryRepository.isResumableOfflineSummary(summary.id)) {
                            resumable = summary.id
                            break
                        }
                    }
                    val current = mutableState.value
                    if (current.recordingId == normalized) {
                        mutableState.value = current.copy(resumableSummaryId = resumable)
                    }
                }
            }
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            val ready =
                runCatching {
                    val active =
                        modelManager.activeModel(Stage172OfflineSummaryModelContract.MODEL_ID)
                            ?: return@runCatching false
                    val identity =
                        resolveManagedLocalLlmModel(active, LocalLlmBackendPolicy.AUTO).identity
                    Stage172OfflineSummaryModelContract.requireMatches(identity)
                    true
                }.getOrDefault(false)
            mutableState.value = mutableState.value.copy(modelReady = ready)
        }
    }

    fun generate() {
        val recordingId = mutableState.value.recordingId ?: return
        viewModelScope.launch {
            when (val outcome = coordinator.start(recordingId)) {
                is OfflineSummaryStartOutcome.Started ->
                    mutableState.value = mutableState.value.copy(
                        run = coordinator.state.value[recordingId],
                        notice = null,
                    )
                is OfflineSummaryStartOutcome.Rejected ->
                    mutableState.value = mutableState.value.copy(notice = outcome.message)
            }
        }
    }

    fun resume() {
        val recordingId = mutableState.value.recordingId ?: return
        val summaryId = mutableState.value.resumableSummaryId ?: return
        viewModelScope.launch {
            when (val outcome = coordinator.resume(recordingId, summaryId)) {
                is OfflineSummaryStartOutcome.Started ->
                    mutableState.value = mutableState.value.copy(
                        run = coordinator.state.value[recordingId],
                        resumableSummaryId = null,
                        notice = null,
                    )
                is OfflineSummaryStartOutcome.Rejected ->
                    mutableState.value = mutableState.value.copy(
                        resumableSummaryId = null,
                        notice = outcome.message,
                    )
            }
        }
    }

    fun cancel() {
        val recordingId = mutableState.value.recordingId ?: return
        viewModelScope.launch {
            if (!coordinator.cancel(recordingId)) {
                mutableState.value = mutableState.value.copy(notice = "当前没有可取消的离线总结任务")
            }
        }
    }

    fun clearNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    class Factory(
        private val coordinator: OfflineSummaryCoordinator,
        private val modelManager: ModelManager,
        private val aiSummaryRepository: AiSummaryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OfflineSummaryProductViewModel(coordinator, modelManager, aiSummaryRepository) as T
    }
}
