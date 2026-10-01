package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingHardwareEventKind
import io.github.ioannes78.voica.protocol.RecordingStatus

internal object RecordingPlaybackInterlockPolicy {
    fun shouldPause(
        recordingState: RecordingDeviceState,
        playbackState: PlaybackState,
    ): Boolean {
        if (playbackState != PlaybackState.PLAYING) return false

        if (recordingState.status == RecordingStatus.Recording) {
            return true
        }

        val hardwareEvent = recordingState.lastHardwareEvent ?: return false
        val isFreshHardwareEdge =
            recordingState.lastUpdatedTimeMs == hardwareEvent.timestampMs

        return isFreshHardwareEdge &&
            hardwareEvent.kind in
                setOf(
                    RecordingHardwareEventKind.START,
                    RecordingHardwareEventKind.RESUME,
                )
    }
}
