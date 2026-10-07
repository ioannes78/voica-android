package io.github.ioannes78.voica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskNotificationActionReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun summaryCancelIntentCarriesGenerationAndUsesGenerationBoundIdentity() {
        val oldIntent = aiSummaryCancelIntent(context, SUMMARY_ID, 1L)
        val newIntent = aiSummaryCancelIntent(context, SUMMARY_ID, 2L)

        assertEquals(TaskNotificationActions.CANCEL_AI_SUMMARY, oldIntent.action)
        assertEquals(
            AiSummaryCancelTarget(SUMMARY_ID, 1L),
            parseAiSummaryCancelTarget(oldIntent),
        )
        assertEquals(
            AiSummaryCancelTarget(SUMMARY_ID, 2L),
            parseAiSummaryCancelTarget(newIntent),
        )
        assertNotEquals(oldIntent.data, newIntent.data)
    }

    @Test
    fun invalidOrLegacySummaryCancelIntentIsRejected() {
        val legacy =
            android.content.Intent(context, TaskNotificationActionReceiver::class.java)
                .setAction(TaskNotificationActions.CANCEL_AI_SUMMARY)

        assertNull(parseAiSummaryCancelTarget(legacy))
    }

    private companion object {
        const val SUMMARY_ID = "summary-generation-bound"
    }
}
