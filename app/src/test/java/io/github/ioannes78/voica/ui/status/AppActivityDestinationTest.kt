package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.PhoneMicrophoneProductTarget
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class AppActivityDestinationTest {
    @Test
    fun `recording detail destination preserves exact recording and section`() {
        val destination =
            AppActivityDestination.RecordingDetail(
                recordingId = "recording-a",
                destination = RecordingDetailDestination.TRANSCRIPT,
            )

        assertEquals("recording-a", destination.recordingId)
        assertEquals(RecordingDetailDestination.TRANSCRIPT, destination.destination)
    }

    @Test
    fun `phone destination preserves existing product target`() {
        assertEquals(
            PhoneMicrophoneProductTarget.REALTIME,
            AppActivityDestination.PhoneMicrophone(PhoneMicrophoneProductTarget.REALTIME).target,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recording destination rejects blank recording id`() {
        AppActivityDestination.RecordingDetail(
            recordingId = " ",
            destination = RecordingDetailDestination.PLAYBACK,
        )
    }
}
