package io.github.ioannes78.voica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskCompletionNoticeStoreTest {
    private lateinit var context: Context
    private var clock = 100L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
    }

    @Test
    fun pendingCompletionSurvivesStoreRecreationUntilAcknowledged() {
        val first = TaskCompletionNoticeStore(context, nowMs = { clock++ })
        first.publish(TaskCompletionKind.TRANSCRIPTION, "tx-1", "recording-a")

        val recreated = TaskCompletionNoticeStore(context, nowMs = { clock++ })
        assertEquals(
            listOf("tx-1"),
            recreated.pending.value.map { it.taskId },
        )

        recreated.acknowledge(TaskCompletionKind.TRANSCRIPTION, "tx-1")
        assertTrue(recreated.pending.value.isEmpty())

        recreated.publish(TaskCompletionKind.TRANSCRIPTION, "tx-1", "recording-a")
        assertTrue(recreated.pending.value.isEmpty())
    }

    @Test
    fun visibleTranscriptAcknowledgesTranscriptionAndDiarizationOnly() {
        val store = TaskCompletionNoticeStore(context, nowMs = { clock++ })
        store.publish(TaskCompletionKind.TRANSCRIPTION, "tx-1", "recording-a")
        store.publish(TaskCompletionKind.DIARIZATION, "dia-1", "recording-a")
        store.publish(TaskCompletionKind.AI_SUMMARY, "sum-1", "recording-a")
        store.publish(TaskCompletionKind.TRANSCRIPTION, "tx-other", "recording-b")

        store.acknowledgeVisible(
            recordingId = "recording-a",
            includeTranscriptResults = true,
            includeSummaryResults = false,
        )

        assertEquals(
            setOf("sum-1", "tx-other"),
            store.pending.value.map { it.taskId }.toSet(),
        )
    }

    @Test
    fun visibleSummaryAcknowledgesOnlySummaryForSameRecording() {
        val store = TaskCompletionNoticeStore(context, nowMs = { clock++ })
        store.publish(TaskCompletionKind.TRANSCRIPTION, "tx-1", "recording-a")
        store.publish(TaskCompletionKind.AI_SUMMARY, "sum-1", "recording-a")

        store.acknowledgeVisible(
            recordingId = "recording-a",
            includeTranscriptResults = false,
            includeSummaryResults = true,
        )

        assertEquals(
            listOf("tx-1"),
            store.pending.value.map { it.taskId },
        )
    }

    private fun clearStore() {
        context.getSharedPreferences("voica-task-completion-notices", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
