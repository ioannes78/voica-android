package io.github.ioannes78.voica

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRecordingNavigationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun transcriptIntentRoundTripsRecordingAndDestination() {
        val intent =
            recordingOpenIntent(
                context = context,
                recordingId = "recording-a",
                destination = RecordingDetailDestination.TRANSCRIPT,
            )

        assertEquals(
            RecordingOpenTarget("recording-a", RecordingDetailDestination.TRANSCRIPT),
            parseRecordingOpenIntent(intent),
        )
    }

    @Test
    fun summaryIntentRoundTripsRecordingAndDestination() {
        val intent =
            recordingOpenIntent(
                context = context,
                recordingId = "recording-b",
                destination = RecordingDetailDestination.SUMMARY,
            )

        assertEquals(
            RecordingOpenTarget("recording-b", RecordingDetailDestination.SUMMARY),
            parseRecordingOpenIntent(intent),
        )
    }

    @Test
    fun invalidOrIncompleteIntentIsIgnored() {
        assertNull(parseRecordingOpenIntent(Intent("other.action")))
        assertNull(
            parseRecordingOpenIntent(
                recordingOpenIntent(
                    context = context,
                    recordingId = "recording-a",
                    destination = RecordingDetailDestination.PLAYBACK,
                ).apply {
                    action = "other.action"
                },
            ),
        )
    }

    @Test
    fun repeatedSameTargetPublishesDistinctCommandTokens() {
        val first =
            requireNotNull(
                AppRecordingNavigation.publish(
                    "recording-a",
                    RecordingDetailDestination.PLAYBACK,
                ),
            )
        val second =
            requireNotNull(
                AppRecordingNavigation.publish(
                    "recording-a",
                    RecordingDetailDestination.PLAYBACK,
                ),
            )

        assertTrue(second.token > first.token)
        assertEquals(first.recordingId, second.recordingId)
        assertEquals(first.destination, second.destination)
    }
}
