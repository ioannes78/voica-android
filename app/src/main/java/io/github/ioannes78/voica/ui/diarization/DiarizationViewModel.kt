package io.github.ioannes78.voica.ui.diarization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.ioannes78.voica.DiarizationCoordinator
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.SpeakerCountChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DiarizationViewModel(
    private val coordinator: DiarizationCoordinator,
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
        start(recordingId)
    }

    fun cancel() {
        coordinator.cancel()
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    class Factory(
        private val coordinator: DiarizationCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DiarizationViewModel(coordinator) as T
    }
}
