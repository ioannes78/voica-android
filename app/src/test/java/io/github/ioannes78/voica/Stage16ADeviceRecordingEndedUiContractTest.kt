package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage16ADeviceRecordingEndedUiContractTest {
    @Test
    fun `device recording ended finalizes then completes instead of remaining active`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing("run-a", "small", "Small")
        store.offer(
            RealtimeTranscriptionOutput.Lifecycle(
                RealtimeAsrPipelineEvent.SessionStarted(sessionId = 1),
            ),
        )

        store.offer(
            RealtimeTranscriptionOutput.Lifecycle(
                RealtimeAsrPipelineEvent.SessionEnded(
                    sessionId = 1,
                    reason = RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED,
                    drainTimedOut = false,
                ),
            ),
        )

        assertEquals(Stage16ARealtimeUiPhase.FINALIZING, store.state.value.phase)
        assertEquals("正在完成最后内容…", store.state.value.lifecycleMessage)

        store.completed()

        assertEquals(Stage16ARealtimeUiPhase.COMPLETED, store.state.value.phase)
        assertNull(store.state.value.lifecycleMessage)
    }
}
