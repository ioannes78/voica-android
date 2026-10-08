package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalMiniPlayerPolicyTest {
    @Test
    fun visibleOnlyForPlayingOrPausedLoadedRecording() {
        assertTrue(
            shouldRenderGlobalMiniPlayer(
                PlaybackSnapshot(recordingId = "a", state = PlaybackState.PLAYING),
            ),
        )
        assertTrue(
            shouldRenderGlobalMiniPlayer(
                PlaybackSnapshot(recordingId = "a", state = PlaybackState.PAUSED),
            ),
        )
        assertFalse(
            shouldRenderGlobalMiniPlayer(
                PlaybackSnapshot(recordingId = "a", state = PlaybackState.READY),
            ),
        )
        assertFalse(
            shouldRenderGlobalMiniPlayer(
                PlaybackSnapshot(recordingId = null, state = PlaybackState.PLAYING),
            ),
        )
    }

    @Test
    fun progressUsesCanonicalSamplePositionAndClamps() {
        assertEquals(
            0.25f,
            globalMiniPlayerProgress(
                PlaybackSnapshot(
                    recordingId = "a",
                    state = PlaybackState.PLAYING,
                    positionSampleIndex = 4_000L,
                    durationSampleCount = 16_000L,
                ),
            ),
        )
        assertEquals(
            1f,
            globalMiniPlayerProgress(
                PlaybackSnapshot(
                    recordingId = "a",
                    state = PlaybackState.PLAYING,
                    positionSampleIndex = 20_000L,
                    durationSampleCount = 16_000L,
                ),
            ),
        )
        assertEquals(
            0f,
            globalMiniPlayerProgress(
                PlaybackSnapshot(
                    recordingId = "a",
                    state = PlaybackState.PLAYING,
                    durationSampleCount = 0L,
                ),
            ),
        )
    }
}
