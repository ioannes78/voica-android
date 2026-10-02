package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackState

class TranscriptPlaybackCoordinator(
    private val controller: PlaybackController,
) {
    suspend fun playFrom(
        recordingId: String,
        sampleIndex: Long,
    ) {
        require(recordingId.isNotBlank())
        require(sampleIndex >= 0L)

        val before = controller.snapshot.value
        if (
            before.recordingId != recordingId ||
            before.state == PlaybackState.ERROR
        ) {
            controller.load(recordingId)
        }

        val loaded = controller.snapshot.value
        if (
            loaded.recordingId != recordingId ||
            loaded.state == PlaybackState.ERROR ||
            loaded.state == PlaybackState.RELEASED
        ) {
            return
        }

        controller.seekToSample(sampleIndex)
        controller.play()
    }
}
