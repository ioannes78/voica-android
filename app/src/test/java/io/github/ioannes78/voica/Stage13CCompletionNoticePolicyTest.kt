package io.github.ioannes78.voica

import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage13CCompletionNoticePolicyTest {
    @Test
    fun ordinaryCompletionItemsKeepTaskIdentityAndOpenCorrectDestination() {
        val items =
            buildCompletionAttentionItems(
                notices =
                    listOf(
                        TaskCompletionNotice(
                            kind = TaskCompletionKind.TRANSCRIPTION,
                            taskId = "tx-1",
                            recordingId = "recording-a",
                            publishedAtMs = 3L,
                        ),
                        TaskCompletionNotice(
                            kind = TaskCompletionKind.DIARIZATION,
                            taskId = "dia-1",
                            recordingId = "recording-a",
                            publishedAtMs = 2L,
                        ),
                        TaskCompletionNotice(
                            kind = TaskCompletionKind.AI_SUMMARY,
                            taskId = "sum-1",
                            recordingId = "recording-a",
                            publishedAtMs = 1L,
                        ),
                    ),
                recordings = emptyList(),
            )

        assertEquals(
            listOf("转写完成", "说话人分离完成", "AI 总结完成"),
            items.map { it.label },
        )
        assertEquals(
            listOf(
                RecordingDetailDestination.TRANSCRIPT,
                RecordingDetailDestination.TRANSCRIPT,
                RecordingDetailDestination.SUMMARY,
            ),
            items.map { it.destination },
        )
        assertEquals(
            listOf("tx-1", "dia-1", "sum-1"),
            items.map { it.completionTaskId },
        )
    }

    @Test
    fun recordingOpenTargetCarriesCompletionAcknowledgementOnlyWhenPairIsComplete() {
        val complete =
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = "recording-a",
                destinationName = RecordingDetailDestination.TRANSCRIPT.name,
                completionKindName = TaskCompletionKind.TRANSCRIPTION.name,
                completionTaskId = "tx-1",
            )!!
        assertEquals(TaskCompletionKind.TRANSCRIPTION, complete.completionKind)
        assertEquals("tx-1", complete.completionTaskId)

        val incomplete =
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = "recording-a",
                destinationName = RecordingDetailDestination.TRANSCRIPT.name,
                completionKindName = TaskCompletionKind.TRANSCRIPTION.name,
                completionTaskId = null,
            )!!
        assertEquals(null, incomplete.completionKind)
        assertEquals(null, incomplete.completionTaskId)
    }
}
