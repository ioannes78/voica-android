package io.github.ioannes78.voica

import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRecordingNavigationTest {
    @Test
    fun transcriptRouteParsesRecordingAndDestination() {
        assertEquals(
            RecordingOpenTarget("recording-a", RecordingDetailDestination.TRANSCRIPT),
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = "recording-a",
                destinationName = RecordingDetailDestination.TRANSCRIPT.name,
            ),
        )
    }

    @Test
    fun summaryRouteParsesRecordingAndDestination() {
        assertEquals(
            RecordingOpenTarget("recording-b", RecordingDetailDestination.SUMMARY),
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = "recording-b",
                destinationName = RecordingDetailDestination.SUMMARY.name,
            ),
        )
    }

    @Test
    fun invalidOrIncompleteRouteIsIgnored() {
        assertNull(
            parseRecordingOpenTarget(
                action = "other.action",
                recordingId = "recording-a",
                destinationName = RecordingDetailDestination.PLAYBACK.name,
            ),
        )
        assertNull(
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = " ",
                destinationName = RecordingDetailDestination.PLAYBACK.name,
            ),
        )
        assertNull(
            parseRecordingOpenTarget(
                action = ACTION_OPEN_RECORDING,
                recordingId = "recording-a",
                destinationName = "UNKNOWN",
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
