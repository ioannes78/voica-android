package io.github.ioannes78.voica.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.LocalRecordingDeleteCoordinator
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlaybackViewModel(
    private val controller: PlaybackController,
    private val deleteCoordinator: LocalRecordingDeleteCoordinator,
) : ViewModel() {
    val snapshot = controller.snapshot
    private val transcriptPlaybackCoordinator = TranscriptPlaybackCoordinator(controller)
    private var transcriptPlaybackJob: Job? = null

    fun loadAndPlay(recordingId: String) {
        viewModelScope.launch {
            val current = controller.snapshot.value
            if (
                current.recordingId != recordingId ||
                current.state == PlaybackState.ERROR
            ) {
                controller.load(recordingId)
            }
            controller.play()
        }
    }

    fun retryCurrent() {
        val recordingId = controller.snapshot.value.recordingId ?: return
        loadAndPlay(recordingId)
    }

    fun play() {
        viewModelScope.launch { controller.play() }
    }

    fun pause() {
        viewModelScope.launch { controller.pause() }
    }

    fun closePlayer() {
        transcriptPlaybackJob?.cancel()
        viewModelScope.launch { controller.unload() }
    }

    fun seekToSample(sampleIndex: Long) {
        viewModelScope.launch { controller.seekToSample(sampleIndex) }
    }

    fun seekAndPlay(
        recordingId: String,
        sampleIndex: Long,
    ) {
        transcriptPlaybackJob?.cancel()
        transcriptPlaybackJob =
            viewModelScope.launch {
                transcriptPlaybackCoordinator.playFrom(
                    recordingId = recordingId,
                    sampleIndex = sampleIndex,
                )
            }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { controller.setSpeed(speed) }
    }

    fun deleteRecording(recordingId: String) {
        viewModelScope.launch {
            deleteCoordinator.delete(recordingId)
        }
    }

    class Factory(
        private val controller: PlaybackController,
        private val deleteCoordinator: LocalRecordingDeleteCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaybackViewModel(controller, deleteCoordinator) as T
    }
}