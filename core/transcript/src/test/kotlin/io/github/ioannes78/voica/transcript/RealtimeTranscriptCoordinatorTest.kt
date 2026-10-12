package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalConsumerDiscontinuityReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import io.github.ioannes78.voica.audio.StreamingAudioBuffer
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTranscriptCoordinatorTest {
    @Test
    fun `coordinator is sole pump and preserves canonical event order`() = runBlocking {
        val source = FakeSource(
            listOf(
                RealtimeCanonicalStreamEvent.SessionStarted(1),
                RealtimeCanonicalStreamEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
                RealtimeCanonicalStreamEvent.PcmFrame(frame()),
                RealtimeCanonicalStreamEvent.SessionEnded(
                    sessionId = 1,
                    finalEpochId = 1,
                    finalSampleIndexExclusive = 320,
                    reason = RealtimeCanonicalSessionEndReason.STOP,
                    drainTimedOut = false,
                ),
            ),
        )
        val coordinator = RealtimeTranscriptCoordinator(
            source = source,
            outputBuffer = StreamingAudioBuffer(maxQueuedPcmFrames = 4, maxQueuedPcmSamples = 1_280),
        )

        coordinator.run()

        assertTrue(coordinator.receive() is StreamingAudioEvent.SessionStarted)
        assertTrue(coordinator.receive() is StreamingAudioEvent.EpochStarted)
        assertTrue(coordinator.receive() is StreamingAudioEvent.PcmFrame)
        val ended = coordinator.receive() as StreamingAudioEvent.SessionEnded
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, ended.reason)
        assertNull(coordinator.receive())

        val snapshot = coordinator.snapshot()
        assertTrue(snapshot.started)
        assertTrue(snapshot.completed)
        assertEquals(4L, snapshot.consumedSourceEvents)
        assertEquals(1L, snapshot.consumedPcmFrames)
        assertEquals(320L, snapshot.consumedPcmSamples)
    }

    @Test
    fun `upstream consumer discontinuity is normalized without hiding loss`() = runBlocking {
        val source = FakeSource(
            listOf(
                RealtimeCanonicalStreamEvent.ConsumerDiscontinuity(
                    sessionId = 3,
                    epochId = 4,
                    resumeAtSampleIndex = 2_560,
                    reason = RealtimeCanonicalConsumerDiscontinuityReason.BACKPRESSURE,
                    droppedFrames = 5,
                    droppedSamples = 1_600,
                ),
            ),
        )
        val coordinator = RealtimeTranscriptCoordinator(
            source,
            StreamingAudioBuffer(maxQueuedPcmFrames = 4, maxQueuedPcmSamples = 1_280),
        )

        coordinator.run()
        val gap = coordinator.receive() as StreamingAudioEvent.Discontinuity
        assertEquals(StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE, gap.reason)
        assertEquals(2_560L, gap.resumeAtSampleIndex)
        assertEquals(1_600L, gap.droppedSamples)
        assertEquals(1L, coordinator.snapshot().consumedUpstreamDiscontinuities)
    }

    @Test
    fun `coordinator cannot start twice`() = runBlocking {
        val coordinator = RealtimeTranscriptCoordinator(
            FakeSource(emptyList()),
            StreamingAudioBuffer(maxQueuedPcmFrames = 2, maxQueuedPcmSamples = 640),
        )
        coordinator.run()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.run() }
        }
    }

    private fun frame() = RealtimeCanonicalPcmFrame(
        sessionId = 1,
        epochId = 1,
        startSampleIndex = 0,
        samples = ShortArray(320) { 4 },
        epochStartReason = RealtimeCanonicalEpochStartReason.SESSION_START,
    )

    private class FakeSource(
        events: List<RealtimeCanonicalStreamEvent>,
    ) : CanonicalRealtimeEventSource {
        private val queue = ArrayDeque(events)

        override suspend fun receive(): RealtimeCanonicalStreamEvent? =
            if (queue.isEmpty()) null else queue.removeFirst()
    }
}
