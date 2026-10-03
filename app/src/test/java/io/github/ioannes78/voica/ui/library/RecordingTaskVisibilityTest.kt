package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgress
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTaskVisibilityTest {
    @Test
    fun transcriptionStatusOnlyShowsActiveOrFailedStateForSameRecording() {
        val running =
            TranscriptionRunState.Running(
                recordingId = "recording-a",
                transcriptionId = null,
                mode = TranscriptionMode.FAST,
                progress = TranscriptionProgress(TranscriptionPhase.PREPARING),
            )
        val failed =
            TranscriptionRunState.Failed(
                recordingId = "recording-a",
                mode = TranscriptionMode.FAST,
                message = "failed",
            )
        val completed =
            TranscriptionRunState.Completed(
                recordingId = "recording-a",
                transcriptionId = "tx-a",
                mode = TranscriptionMode.FAST,
                segments = emptyList(),
            )

        assertTrue(shouldShowTranscriptionStatus(running, "recording-a"))
        assertFalse(shouldShowTranscriptionStatus(running, "recording-b"))
        assertTrue(shouldShowTranscriptionStatus(failed, "recording-a"))
        assertFalse(shouldShowTranscriptionStatus(failed, "recording-b"))
        assertFalse(shouldShowTranscriptionStatus(completed, "recording-a"))
        assertFalse(
            shouldShowTranscriptionStatus(
                TranscriptionRunState.Cancelled(
                    recordingId = "recording-a",
                    mode = TranscriptionMode.FAST,
                ),
                "recording-a",
            ),
        )
        assertFalse(
            shouldShowTranscriptionStatus(
                TranscriptionRunState.Idle,
                "recording-a",
            ),
        )
    }

    @Test
    fun diarizationStatusOnlyShowsActiveOrFailedStateForSameRecording() {
        val running =
            DiarizationRunState.Running(
                recordingId = "recording-a",
                runId = null,
                progress = DiarizationProgress(DiarizationPhase.PREPARING),
            )
        val failed =
            DiarizationRunState.Failed(
                recordingId = "recording-a",
                message = "failed",
            )
        val completed =
            DiarizationRunState.Completed(
                recordingId = "recording-a",
                runId = "run-a",
                speakerCount = 0,
                turns = emptyList(),
            )

        assertTrue(shouldShowDiarizationStatus(running, "recording-a"))
        assertFalse(shouldShowDiarizationStatus(running, "recording-b"))
        assertTrue(shouldShowDiarizationStatus(failed, "recording-a"))
        assertFalse(shouldShowDiarizationStatus(failed, "recording-b"))
        assertFalse(shouldShowDiarizationStatus(completed, "recording-a"))
        assertFalse(
            shouldShowDiarizationStatus(
                DiarizationRunState.Cancelled("recording-a"),
                "recording-a",
            ),
        )
        assertFalse(
            shouldShowDiarizationStatus(
                DiarizationRunState.Idle,
                "recording-a",
            ),
        )
    }
}
