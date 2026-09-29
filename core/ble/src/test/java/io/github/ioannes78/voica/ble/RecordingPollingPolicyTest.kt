package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPollingPolicyTest {
    @Test
    fun convergenceRetriesStalePauseStateButStopsOnPaused() {
        assertTrue(
            RecordingStateConvergencePolicy.shouldRetry(
                attempt = 1,
                actual = RecordingStatus.Recording,
                expected = RecordingStatus.Paused,
            ),
        )
        assertFalse(
            RecordingStateConvergencePolicy.shouldRetry(
                attempt = 2,
                actual = RecordingStatus.Paused,
                expected = RecordingStatus.Paused,
            ),
        )
        assertFalse(
            RecordingStateConvergencePolicy.shouldRetry(
                attempt = RecordingStateConvergencePolicy.MAX_ATTEMPTS,
                actual = RecordingStatus.Recording,
                expected = RecordingStatus.Paused,
            ),
        )
    }

    @Test
    fun hardwareEventsMapToExpectedDeviceTruth() {
        assertTrue(
            RecordingStateConvergencePolicy.isSatisfied(
                RecordingStateConvergencePolicy.expectedForHardwareEvent(
                    RecordingHardwareEventKind.PAUSE,
                ),
                RecordingStatus.Paused,
            ),
        )
        assertTrue(
            RecordingStateConvergencePolicy.isSatisfied(
                RecordingStateConvergencePolicy.expectedForHardwareEvent(
                    RecordingHardwareEventKind.SAVE,
                ),
                RecordingStatus.Idle,
            ),
        )
    }

    @Test
    fun filenameIsNotQueriedWhileIdle() {
        assertFalse(
            RecordingSupplementaryReadPolicy.shouldReadFilename(RecordingStatus.Idle),
        )
        assertTrue(
            RecordingSupplementaryReadPolicy.shouldReadFilename(RecordingStatus.Recording),
        )
        assertTrue(
            RecordingSupplementaryReadPolicy.shouldReadFilename(RecordingStatus.Paused),
        )
    }

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
