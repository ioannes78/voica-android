package io.github.ioannes78.voica.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import kotlinx.coroutines.launch

class PlaybackViewModel(
    private val controller: PlaybackController,
    private val recordingLibraryRepository: RecordingLibraryRepository,
) : ViewModel() {
    val snapshot = controller.snapshot

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

    fun seekToSample(sampleIndex: Long) {
        viewModelScope.launch { controller.seekToSample(sampleIndex) }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { controller.setSpeed(speed) }
    }

    fun deleteRecording(recordingId: String) {
        viewModelScope.launch {
            if (controller.snapshot.value.recordingId == recordingId) {
                controller.unload()
            }
            recordingLibraryRepository.deleteLocalRecording(recordingId)
        }
    }

    class Factory(
        private val controller: PlaybackController,
        private val recordingLibraryRepository: RecordingLibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaybackViewModel(
                controller,
                recordingLibraryRepository,
            ) as T
    }
}
