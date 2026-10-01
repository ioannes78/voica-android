package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingHardwareEvent
import io.github.ioannes78.voica.ble.RecordingHardwareEventKind
import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackInterlockPolicyTest {
    @Test
    fun activeRecordingPausesPlayingAudio() {
        assertTrue(
            RecordingPlaybackInterlockPolicy.shouldPause(
                recordingState =
                    RecordingDeviceState(status = RecordingStatus.Recording),
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }

    @Test
    fun activeRecordingDoesNotRePauseAlreadyPausedAudio() {
        assertFalse(
            RecordingPlaybackInterlockPolicy.shouldPause(
                recordingState =
                    RecordingDeviceState(status = RecordingStatus.Recording),
                playbackState = PlaybackState.PAUSED,
            ),
        )
    }

    @Test
    fun freshHardwareStartPausesBeforeStateReconciliationCompletes() {
        val timestamp = 1234L
        assertTrue(
            RecordingPlaybackInterlockPolicy.shouldPause(
                recordingState =
                    RecordingDeviceState(
                        status = RecordingStatus.Idle,
                        lastHardwareEvent =
                            RecordingHardwareEvent(
                                kind = RecordingHardwareEventKind.START,
                                source = NotificationSource.AE23,
                                command = 0,
                                sequence = 1,
                                timestampMs = timestamp,
                            ),
                        lastUpdatedTimeMs = timestamp,
                    ),
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }

    @Test
    fun staleHardwareStartDoesNotBlockPlaybackAfterLaterStateUpdate() {
        assertFalse(
            RecordingPlaybackInterlockPolicy.shouldPause(
                recordingState =
                    RecordingDeviceState(
                        status = RecordingStatus.Paused,
                        lastHardwareEvent =
                            RecordingHardwareEvent(
                                kind = RecordingHardwareEventKind.START,
                                source = NotificationSource.AE23,
                                command = 0,
                                sequence = 1,
                                timestampMs = 1000L,
                            ),
                        lastUpdatedTimeMs = 1200L,
                    ),
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }

    @Test
    fun idleRecordingAllowsPlayback() {
        assertFalse(
            RecordingPlaybackInterlockPolicy.shouldPause(
                recordingState =
                    RecordingDeviceState(status = RecordingStatus.Idle),
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }
}
