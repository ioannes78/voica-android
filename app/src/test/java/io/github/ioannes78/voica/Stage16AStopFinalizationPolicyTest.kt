package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage16AStopFinalizationPolicyTest {
    @Test
    fun `stop finalization timeout is durable completed media not failed recording`() {
        assertEquals(
            RealtimeTranscriptionStateValue.COMPLETED,
            Stage16AStopFinalizationPolicy.durableTerminalState(
                requestedState = RealtimeTranscriptionStateValue.FAILED,
                endReason = Stage16AStopFinalizationPolicy.ERROR_CODE,
                errorCode = Stage16AStopFinalizationPolicy.ERROR_CODE,
            ),
        )
    }

    @Test
    fun `unrelated failure remains failed`() {
        assertEquals(
            RealtimeTranscriptionStateValue.FAILED,
            Stage16AStopFinalizationPolicy.durableTerminalState(
                requestedState = RealtimeTranscriptionStateValue.FAILED,
                endReason = "RUNNER_FAILED",
                errorCode = "REALTIME_RUNNER_FAILED",
            ),
        )
    }

    @Test
    fun `live state projects stop timeout as degraded completed`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing(
            runId = "run-a",
            modelId = "small",
            modelLabel = "Small",
        )

        store.failed(
            Stage16AStopFinalizationPolicy.ERROR_CODE,
            "设备已停止，但实时转写最终收口超时",
        )

        val state = store.state.value
        assertEquals(Stage16ARealtimeUiPhase.COMPLETED, state.phase)
        assertEquals(Stage16AStopFinalizationPolicy.ERROR_CODE, state.errorCode)
        assertEquals(Stage16AStopFinalizationPolicy.DEGRADED_MESSAGE, state.lifecycleMessage)
        assertNull(state.errorMessage)
    }
}
