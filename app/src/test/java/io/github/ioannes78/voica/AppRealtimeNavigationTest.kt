package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRealtimeNavigationTest {
    @Test
    fun `only realtime action is accepted`() {
        assertTrue(isRealtimeOpenAction(ACTION_OPEN_REALTIME))
        assertFalse(isRealtimeOpenAction(null))
        assertFalse(isRealtimeOpenAction("io.github.ioannes78.voica.OTHER"))
    }

    @Test
    fun `completed realtime overlay dismisses only for a newer recording request`() {
        val realtimeRequest = AppRealtimeOpenRequest(token = 8L)

        assertFalse(
            shouldDismissRealtimeOverlayForRecordingHandoff(
                realtimeRequest = realtimeRequest,
                realtimePhase = Stage16ARealtimeUiPhase.ACTIVE,
                recordingTokenAtOpen = 20L,
                currentRecordingToken = 21L,
            ),
        )
        assertFalse(
            shouldDismissRealtimeOverlayForRecordingHandoff(
                realtimeRequest = realtimeRequest,
                realtimePhase = Stage16ARealtimeUiPhase.COMPLETED,
                recordingTokenAtOpen = 20L,
                currentRecordingToken = 20L,
            ),
        )
        assertTrue(
            shouldDismissRealtimeOverlayForRecordingHandoff(
                realtimeRequest = realtimeRequest,
                realtimePhase = Stage16ARealtimeUiPhase.COMPLETED,
                recordingTokenAtOpen = 20L,
                currentRecordingToken = 21L,
            ),
        )
    }

    @Test
    fun `completed phase without realtime overlay never triggers handoff`() {
        assertFalse(
            shouldDismissRealtimeOverlayForRecordingHandoff(
                realtimeRequest = null,
                realtimePhase = Stage16ARealtimeUiPhase.COMPLETED,
                recordingTokenAtOpen = null,
                currentRecordingToken = 1L,
            ),
        )
    }
}
