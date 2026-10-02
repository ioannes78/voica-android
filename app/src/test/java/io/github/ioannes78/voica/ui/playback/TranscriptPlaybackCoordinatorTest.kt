package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPlaybackCoordinatorTest {
    @Test
    fun differentRecordingLoadsThenSeeksThenPlays() = runBlocking {
        val controller = FakeController()
        val coordinator = TranscriptPlaybackCoordinator(controller)

        coordinator.playFrom("recording-b", 12_345L)

        assertEquals(
            listOf("load:recording-b", "seek:12345", "play"),
            controller.events,
        )
    }

    @Test
    fun alreadyLoadedRecordingOnlySeeksThenPlays() = runBlocking {
        val controller = FakeController("recording-a")
        val coordinator = TranscriptPlaybackCoordinator(controller)

        coordinator.playFrom("recording-a", 8_000L)

        assertEquals(listOf("seek:8000", "play"), controller.events)
    }

    private class FakeController(
        recordingId: String? = null,
    ) : PlaybackController {
        private val mutable =
            MutableStateFlow(
                PlaybackSnapshot(
                    recordingId = recordingId,
                    state =
                        if (recordingId == null) {
                            PlaybackState.IDLE
                        } else {
                            PlaybackState.READY
                        },
                ),
            )

        val events = mutableListOf<String>()

        override val snapshot: StateFlow<PlaybackSnapshot> = mutable
        override val stateFlow: StateFlow<PlaybackState> =
            MutableStateFlow(mutable.value.state)
        override val positionFlow: StateFlow<Long> = MutableStateFlow(0L)

        override suspend fun load(recordingId: String) {
            events += "load:$recordingId"
            mutable.value =
                PlaybackSnapshot(
                    recordingId = recordingId,
                    state = PlaybackState.READY,
                    durationSampleCount = 32_000L,
                )
        }

        override suspend fun play() {
            events += "play"
        }

        override suspend fun pause() {
            events += "pause"
        }

        override suspend fun seekToSample(sampleIndex: Long) {
            events += "seek:$sampleIndex"
        }

        override suspend fun setSpeed(speed: Float) = Unit

        override suspend fun unload() = Unit

        override suspend fun release() = Unit
    }
}
