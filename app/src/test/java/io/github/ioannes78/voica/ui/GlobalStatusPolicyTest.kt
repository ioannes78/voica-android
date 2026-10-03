package io.github.ioannes78.voica.ui

import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.transcript.TranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalStatusPolicyTest {
    @Test
    fun completedPlaybackDoesNotKeepGlobalMiniPlayerVisible() {
        assertTrue(
            shouldShowGlobalPlayback(
                PlaybackSnapshot(
                    recordingId = "recording-a",
                    state = PlaybackState.PLAYING,
                ),
            ),
        )
        assertTrue(
            shouldShowGlobalPlayback(
                PlaybackSnapshot(
                    recordingId = "recording-a",
                    state = PlaybackState.PAUSED,
                ),
            ),
        )
        assertFalse(
            shouldShowGlobalPlayback(
                PlaybackSnapshot(
                    recordingId = "recording-a",
                    state = PlaybackState.COMPLETED,
                ),
            ),
        )
        assertFalse(
            shouldShowGlobalPlayback(
                PlaybackSnapshot(
                    recordingId = null,
                    state = PlaybackState.PLAYING,
                ),
            ),
        )
    }

    @Test
    fun completedAndFailedTranscriptionBecomeTerminalNotifications() {
        val completed =
            buildGlobalTaskItems(
                transcription =
                    TranscriptionRunState.Completed(
                        recordingId = "recording-a",
                        transcriptionId = "tx-1",
                        mode = TranscriptionMode.FAST,
                        segments = emptyList(),
                    ),
                diarization = io.github.ioannes78.voica.DiarizationRunState.Idle,
                aiSummary = AiSummaryRunState.Idle,
                recordings = emptyList(),
            ).single()

        assertTrue(completed.terminal)
        assertEquals("转写完成", completed.label)

        val failed =
            buildGlobalTaskItems(
                transcription =
                    TranscriptionRunState.Failed(
                        recordingId = "recording-a",
                        mode = TranscriptionMode.FAST,
                        message = "failed",
                    ),
                diarization = io.github.ioannes78.voica.DiarizationRunState.Idle,
                aiSummary = AiSummaryRunState.Idle,
                recordings = emptyList(),
            ).single()

        assertTrue(failed.terminal)
        assertEquals("转写失败", failed.label)
    }

    @Test
    fun completedAndFailedSummaryBecomeTerminalNotifications() {
        val completed =
            buildGlobalTaskItems(
                transcription = TranscriptionRunState.Idle,
                diarization = io.github.ioannes78.voica.DiarizationRunState.Idle,
                aiSummary =
                    AiSummaryRunState.Completed(
                        summaryId = "summary-1",
                        recordingId = "recording-a",
                        transcriptionId = "tx-1",
                    ),
                recordings = emptyList(),
            ).single()

        assertTrue(completed.terminal)
        assertEquals("AI 总结完成", completed.label)

        val failed =
            buildGlobalTaskItems(
                transcription = TranscriptionRunState.Idle,
                diarization = io.github.ioannes78.voica.DiarizationRunState.Idle,
                aiSummary =
                    AiSummaryRunState.Failed(
                        summaryId = "summary-1",
                        recordingId = "recording-a",
                        transcriptionId = "tx-1",
                        errorCode = "STRUCTURED_OUTPUT_INVALID",
                        message = "failed",
                    ),
                recordings = emptyList(),
            ).single()

        assertTrue(failed.terminal)
        assertEquals("AI 总结失败", failed.label)
    }
}
