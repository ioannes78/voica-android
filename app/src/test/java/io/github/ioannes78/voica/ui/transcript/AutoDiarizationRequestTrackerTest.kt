package io.github.ioannes78.voica.ui.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDiarizationRequestTrackerTest {
    @Test
    fun directTranscriptionCompletionConsumesAutoDiarizationRequest() {
        val tracker = AutoDiarizationRequestTracker()

        tracker.markStarted("recording-a")

        assertTrue(tracker.consumeCompleted("recording-a"))
        assertFalse(tracker.consumeCompleted("recording-a"))
    }

    @Test
    fun viewingHistoricalTranscriptionDoesNotCreateAutoRequest() {
        val tracker = AutoDiarizationRequestTracker()

        assertFalse(tracker.consumeCompleted("recording-a"))
    }

    @Test
    fun terminalTranscriptionFailureClearsPendingRequest() {
        val tracker = AutoDiarizationRequestTracker()
        tracker.markStarted("recording-a")

        tracker.clearTerminal("recording-a")

        assertFalse(tracker.consumeCompleted("recording-a"))
    }

    @Test
    fun unrelatedRecordingCannotConsumePendingRequest() {
        val tracker = AutoDiarizationRequestTracker()
        tracker.markStarted("recording-a")

        assertFalse(tracker.consumeCompleted("recording-b"))
        assertTrue(tracker.consumeCompleted("recording-a"))
    }
}
