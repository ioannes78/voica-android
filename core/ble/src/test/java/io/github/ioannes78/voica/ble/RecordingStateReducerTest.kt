package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.protocol.RecordingTimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingStateReducerTest {
    @Test
    fun stateConvergesThroughIdleRecordingPauseResumeIdle() {
        var state = RecordingDeviceState()

        state = RecordingStateReducer.reduce(state, RecordingStateEvent.SyncStarted(1))
        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.StateReceived(RecordingStatus.Idle, 2),
        )
        state = RecordingStateReducer.reduce(state, RecordingStateEvent.SyncCompleted(3))
        assertEquals(RecordingFreshness.FRESH, state.freshness)
        assertEquals(RecordingStatus.Idle, state.status)

        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.StateReceived(RecordingStatus.Recording, 4),
        )
        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.TimeReceived(RecordingTimeInfo(12, 3456), 5),
        )
        assertEquals(12, state.durationSeconds)

        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.StateReceived(RecordingStatus.Paused, 6),
        )
        assertEquals(RecordingStatus.Paused, state.status)

        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.StateReceived(RecordingStatus.Recording, 7),
        )
        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.StateReceived(RecordingStatus.Idle, 8),
        )
        assertEquals(RecordingStatus.Idle, state.status)
    }

    @Test
    fun hardwareEventMarksStateStaleUntilDeviceQueryConfirmsTruth() {
        val event = RecordingHardwareEvent(
            kind = RecordingHardwareEventKind.START,
            source = NotificationSource.AE23,
            command = 1,
            sequence = 9,
            timestampMs = 100,
        )
        val initial = RecordingDeviceState(
            status = RecordingStatus.Idle,
            freshness = RecordingFreshness.FRESH,
        )

        val afterEvent = RecordingStateReducer.reduce(
            initial,
            RecordingStateEvent.HardwareReceived(event),
        )

        assertEquals(RecordingStatus.Idle, afterEvent.status)
        assertEquals(RecordingFreshness.STALE, afterEvent.freshness)
        assertEquals(event, afterEvent.lastHardwareEvent)

        val confirmed = RecordingStateReducer.reduce(
            afterEvent,
            RecordingStateEvent.StateReceived(RecordingStatus.Recording, 101),
        )
        assertEquals(RecordingStatus.Recording, confirmed.status)
    }

    @Test
    fun gainReconciliationReturnsFreshAfterConfirmedReadback() {
        var state = RecordingDeviceState(
            status = RecordingStatus.Idle,
            gain = RecordingGain.Medium,
            freshness = RecordingFreshness.FRESH,
        )

        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.CommandStarted(
                RecordingCommandState.SETTING_GAIN,
                10,
            ),
        )
        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.CommandFinished(
                error = null,
                timestampMs = 11,
            ),
        )
        assertEquals(RecordingFreshness.STALE, state.freshness)

        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.GainReceived(
                gain = RecordingGain.High,
                timestampMs = 12,
            ),
        )
        state = RecordingStateReducer.reduce(
            state,
            RecordingStateEvent.SyncCompleted(13),
        )

        assertEquals(RecordingGain.High, state.gain)
        assertEquals(RecordingFreshness.FRESH, state.freshness)
        assertEquals(RecordingCommandState.IDLE, state.commandState)
    }

    @Test
    fun disconnectKeepsLastKnownValuesButMarksThemStale() {
        val initial = RecordingDeviceState(
            status = RecordingStatus.Recording,
            durationSeconds = 42,
            filename = "REC.opus",
            gain = RecordingGain.High,
            freshness = RecordingFreshness.FRESH,
        )
        val disconnected = RecordingStateReducer.reduce(
            initial,
            RecordingStateEvent.Disconnected(200),
        )

        assertEquals(RecordingFreshness.STALE, disconnected.freshness)
        assertEquals(RecordingStatus.Recording, disconnected.status)
        assertEquals(42, disconnected.durationSeconds)
        assertEquals("REC.opus", disconnected.filename)
        assertEquals(RecordingGain.High, disconnected.gain)
        assertNull(disconnected.lastError)
    }
}
