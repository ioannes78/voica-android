package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.OfflineSummaryCoordinator
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.OfflineSummaryStartOutcome
import io.github.ioannes78.voica.Stage172OfflineSummaryModelContract
import io.github.ioannes78.voica.resolveManagedLocalLlmModel
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.model.ModelManager
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
    val notice: String? = null,
)

/**
 * UI-only observer/controller for Stage 17.2.
 *
 * It never owns LiteRT-LM resources. The application-scoped [OfflineSummaryCoordinator] remains the
 * generation owner when this ViewModel or the composable leaves the screen.
 */
class OfflineSummaryProductViewModel internal constructor(
    private val coordinator: OfflineSummaryCoordinator,
    private val modelManager: ModelManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineSummaryProductUiState())
    val state: StateFlow<OfflineSummaryProductUiState> = mutableState.asStateFlow()

    private var coordinatorJob: Job? = null

    fun bind(recordingId: String) {
        val normalized = recordingId.trim()
        require(normalized.isNotEmpty()) { "recordingId must not be blank" }
        if (mutableState.value.recordingId == normalized) {
            mutableState.value =
                mutableState.value.copy(run = coordinator.state.value[normalized])
            refreshModelStatus()
            return
        }

        coordinatorJob?.cancel()
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
                        resolveManagedLocalLlmModel(
                            active = active,
                            policy = LocalLlmBackendPolicy.AUTO,
                        ).identity
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
                is OfflineSummaryStartOutcome.Started -> {
                    mutableState.value =
                        mutableState.value.copy(
                            run = coordinator.state.value[recordingId],
                            notice = null,
                        )
                }

                is OfflineSummaryStartOutcome.Rejected -> {
                    mutableState.value =
                        mutableState.value.copy(notice = outcome.message)
                }
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OfflineSummaryProductViewModel(
                coordinator = coordinator,
                modelManager = modelManager,
            ) as T
    }
}
