package io.github.ioannes78.voica.ui.diarization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.DiarizationCoordinator
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.RecordingSpeakerModeStore
import io.github.ioannes78.voica.SpeakerCountChoice
import io.github.ioannes78.voica.Stage13CDiarizationAttentionRuntime
import io.github.ioannes78.voica.speakerCountChoiceFromConfigSnapshot
import io.github.ioannes78.voica.database.DiarizationAttentionItem
import io.github.ioannes78.voica.database.DiarizationAttentionKind
import io.github.ioannes78.voica.database.DiarizationAttentionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiarizationViewModel(
    private val coordinator: DiarizationCoordinator,
    private val attentionRepository: DiarizationAttentionRepository,
) : ViewModel() {
    val runState: StateFlow<DiarizationRunState> = coordinator.state

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    fun start(recordingId: String) {
        mutableNotice.value = null
        if (!coordinator.start(recordingId)) {
            mutableNotice.value = "已有说话人分离或对齐任务正在运行，请先完成或取消当前任务"
        }
    }

    fun start(
        recordingId: String,
        speakerCountChoice: SpeakerCountChoice,
    ) {
        mutableNotice.value = null
        if (!coordinator.start(recordingId, speakerCountChoice)) {
            mutableNotice.value = "已有说话人分离或对齐任务正在运行，请先完成或取消当前任务"
        }
    }

    fun retry(recordingId: String) {
        val fileChoice = RecordingSpeakerModeStore.cached(recordingId)
        if (fileChoice != null) {
            start(recordingId, fileChoice)
        } else {
            start(recordingId)
        }
    }

    fun retryAttention(
        attention: DiarizationAttentionItem,
        fallbackSpeakerCount: SpeakerCountChoice,
    ) {
        mutableNotice.value = null
        val started =
            when (attention.kind) {
                DiarizationAttentionKind.DIARIZATION -> {
                    val exactChoice =
                        speakerCountChoiceFromConfigSnapshot(attention.configSnapshot)
                            ?: RecordingSpeakerModeStore.cached(attention.recordingId)
                            ?: fallbackSpeakerCount
                    coordinator.start(attention.recordingId, exactChoice)
                }
                DiarizationAttentionKind.ALIGNMENT -> {
                    val transcriptionId = attention.transcriptionId
                    val diarizationRunId = attention.diarizationRunId
                    transcriptionId != null &&
                        diarizationRunId != null &&
                        coordinator.alignTranscription(transcriptionId, diarizationRunId)
                }
            }
        if (!started) {
            mutableNotice.value = "已有说话人分离或对齐任务正在运行，请先完成或取消当前任务"
        }
    }

    fun ignoreAttention(attention: DiarizationAttentionItem) {
        viewModelScope.launch {
            attentionRepository.acknowledge(attention)
        }
    }

    fun cancel() {
        coordinator.cancel()
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    class Factory(
        private val coordinator: DiarizationCoordinator,
        private val attentionRepository: DiarizationAttentionRepository,
    ) : ViewModelProvider.Factory {
        constructor(coordinator: DiarizationCoordinator) :
            this(coordinator, Stage13CDiarizationAttentionRuntime.repository)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DiarizationViewModel(coordinator, attentionRepository) as T
    }
}
