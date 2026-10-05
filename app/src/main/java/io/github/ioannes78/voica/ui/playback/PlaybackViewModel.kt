package io.github.ioannes78.voica.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.LocalRecordingDeleteCoordinator
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackViewModel(
    private val controller: PlaybackController,
    private val deleteCoordinator: LocalRecordingDeleteCoordinator,
    private val waveformRepository: WaveformOverviewRepository,
) : ViewModel() {
    val snapshot = controller.snapshot
    private val transcriptPlaybackCoordinator = TranscriptPlaybackCoordinator(controller)
    private var transcriptPlaybackJob: Job? = null
    private var waveformJob: Job? = null
    private var waveformRecordingId: String? = null

    private val mutableWaveform =
        MutableStateFlow<WaveformOverviewResult>(WaveformOverviewResult.Unavailable)
    val waveform: StateFlow<WaveformOverviewResult> = mutableWaveform.asStateFlow()

    fun loadAndPlay(recordingId: String) {
        loadWaveform(recordingId)
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

    fun loadWaveform(recordingId: String) {
        if (recordingId == waveformRecordingId && mutableWaveform.value is WaveformOverviewResult.Ready) {
            return
        }
        waveformRecordingId = recordingId
        waveformJob?.cancel()
        mutableWaveform.value = WaveformOverviewResult.Unavailable
        waveformJob =
            viewModelScope.launch {
                val result = waveformRepository.load(recordingId)
                if (waveformRecordingId == recordingId) {
                    mutableWaveform.value = result
                }
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
        loadWaveform(recordingId)
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
        private val waveformRepository: WaveformOverviewRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaybackViewModel(
                controller,
                deleteCoordinator,
                waveformRepository,
            ) as T
    }
}