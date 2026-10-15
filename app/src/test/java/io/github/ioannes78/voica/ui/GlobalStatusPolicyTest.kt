package io.github.ioannes78.voica.ui

import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.Qa4GlobalAttentionItem
import io.github.ioannes78.voica.TaskCompletionKind
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalStatusPolicyTest {
    @Test
    fun globalMiniPlayerIsVisibleOnlyWhilePlayingOrPaused() {
        listOf(PlaybackState.PLAYING, PlaybackState.PAUSED).forEach { state ->
            assertTrue(
                shouldShowGlobalPlayback(
                    PlaybackSnapshot(
                        recordingId = "recording-a",
                        state = state,
                    ),
                ),
            )
        }

        listOf(
            PlaybackState.PREPARING,
            PlaybackState.READY,
            PlaybackState.SEEKING,
            PlaybackState.ERROR,
            PlaybackState.COMPLETED,
        ).forEach { state ->
            assertFalse(
                shouldShowGlobalPlayback(
                    PlaybackSnapshot(
                        recordingId = "recording-a",
                        state = state,
                    ),
                ),
            )
        }
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
    fun consolidatedGlobalTasksCombineRunningAndDurableAttention() {
        val running =
            GlobalTaskItem(
                key = "transcription-running-recording-a",
                recordingId = "recording-a",
                recordingName = "A",
                label = "转写中",
                progress = "43%",
                destination = RecordingDetailDestination.TRANSCRIPT,
                terminal = false,
            )
        val completion =
            Qa4GlobalAttentionItem(
                key = "completion:TRANSCRIPTION:tx-b",
                recordingId = "recording-b",
                recordingName = "B",
                label = "转写完成",
                destination = RecordingDetailDestination.TRANSCRIPT,
                completionKind = TaskCompletionKind.TRANSCRIPTION,
                completionTaskId = "tx-b",
            )

        val result =
            buildConsolidatedGlobalTaskItems(
                runningTasks = listOf(running),
                attentionItems = listOf(completion),
            )

        assertEquals(2, result.size)
        assertFalse(result.first().terminal)
        assertTrue(result.last().terminal)
        assertEquals("完成", result.last().progress)
    }

    @Test
    fun consolidatedGlobalTasksSuppressOnlyExactVisibleDestination() {
        val runningTranscript =
            GlobalTaskItem(
                key = "running-a",
                recordingId = "recording-a",
                recordingName = "A",
                label = "转写中",
                progress = null,
                destination = RecordingDetailDestination.TRANSCRIPT,
                terminal = false,
            )
        val transcriptAttention =
            Qa4GlobalAttentionItem(
                key = "attention-a-transcript",
                recordingId = "recording-a",
                recordingName = "A",
                label = "转写失败",
                destination = RecordingDetailDestination.TRANSCRIPT,
            )
        val summaryAttention =
            Qa4GlobalAttentionItem(
                key = "attention-a-summary",
                recordingId = "recording-a",
                recordingName = "A",
                label = "总结失败",
                destination = RecordingDetailDestination.SUMMARY,
            )

        val result =
            buildConsolidatedGlobalTaskItems(
                runningTasks = listOf(runningTranscript),
                attentionItems = listOf(transcriptAttention, summaryAttention),
                visibleRecordingId = "recording-a",
                visibleDestination = RecordingDetailDestination.TRANSCRIPT,
            )

        assertEquals(listOf("attention-a-summary"), result.map { it.key })
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
        assertEquals("总结完成", completed.label)

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
        assertEquals("总结失败", failed.label)
    }
}
