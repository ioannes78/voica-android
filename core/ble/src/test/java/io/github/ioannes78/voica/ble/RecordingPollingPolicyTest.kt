package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPollingPolicyTest {
    @Test
    fun pollsOnlyWhileReadyForegroundAndRecording() {
        assertTrue(
            RecordingPollingPolicy.shouldPoll(
                ready = true,
                foreground = true,
                status = RecordingStatus.Recording,
            ),
        )
        assertFalse(
            RecordingPollingPolicy.shouldPoll(
                ready = false,
                foreground = true,
                status = RecordingStatus.Recording,
            ),
        )
        assertFalse(
            RecordingPollingPolicy.shouldPoll(
                ready = true,
                foreground = false,
                status = RecordingStatus.Recording,
            ),
        )
        assertFalse(
            RecordingPollingPolicy.shouldPoll(
                ready = true,
                foreground = true,
                status = RecordingStatus.Paused,
            ),
        )
        assertFalse(
            RecordingPollingPolicy.shouldPoll(
                ready = true,
                foreground = true,
                status = null,
            ),
        )
    }
}
