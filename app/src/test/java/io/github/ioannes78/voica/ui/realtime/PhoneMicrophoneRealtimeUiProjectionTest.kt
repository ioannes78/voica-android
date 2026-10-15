package io.github.ioannes78.voica.ui.realtime

import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneRealtimeUiProjectionTest {
    @Test
    fun `healthy realtime state remains unchanged`() {
        val realtime =
            Stage16ARealtimeUiState(
                phase = Stage16ARealtimeUiPhase.ACTIVE,
                runId = "run-1",
                partialText = "你好",
            )

        val projected =
            projectPhoneRealtimeUiState(
                realtime = realtime,
                recording = recording(PhoneMicrophoneRecordingPhase.RECORDING),
                runnerFailure = null,
            )

        assertEquals(realtime, projected)
        assertFalse(projected.recordingOnlyMode)
    }

    @Test
    fun `runner failure while formal recording continues keeps pause stop controls state`() {
        val projected =
            projectPhoneRealtimeUiState(
                realtime =
                    Stage16ARealtimeUiState(
                        phase = Stage16ARealtimeUiPhase.FAILED,
                        errorCode = "REALTIME_PIPELINE_FAILED",
                        errorMessage = "native failed",
                    ),
                recording = recording(PhoneMicrophoneRecordingPhase.RECORDING),
                runnerFailure = "IllegalStateException:native failed",
            )

        assertEquals(Stage16ARealtimeUiPhase.ACTIVE, projected.phase)
        assertTrue(projected.recordingOnlyMode)
        assertEquals("录音仍在继续 · 实时转写已停止", projected.lifecycleMessage)
        assertEquals("录音中 · 转写已停止", realtimeStatusLabel(projected))
        assertNull(projected.errorMessage)
    }

    @Test
    fun `recording-only pause remains paused rather than failed`() {
        val projected =
            projectPhoneRealtimeUiState(
                realtime =
                    Stage16ARealtimeUiState(
                        phase = Stage16ARealtimeUiPhase.PAUSED,
                        errorCode = "PAUSE_NOT_RUNNING",
                    ),
                recording = recording(PhoneMicrophoneRecordingPhase.PAUSED),
                runnerFailure = "REALTIME_PIPELINE_ENDED",
            )

        assertEquals(Stage16ARealtimeUiPhase.PAUSED, projected.phase)
        assertTrue(projected.recordingOnlyMode)
        assertEquals("录音已暂停 · 转写已停止", realtimeStatusLabel(projected))
    }

    private fun recording(phase: PhoneMicrophoneRecordingPhase) =
        PhoneMicrophoneRecordingSnapshot(phase = phase)
}
