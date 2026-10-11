package io.github.ioannes78.voica.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCanonicalAudioSourceLifecycleTest {
    @Test
    fun transportCloseAppendsTerminalAfterAlreadyAcceptedPcm() = runBlocking {
        val source = RealtimeCanonicalAudioSource()
        source.offer(frame(sessionId = 1, start = 0))
        source.offer(frame(sessionId = 1, start = 320, reason = null))

        val result = source.endActiveSessionImmediately(
            RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
        )

        assertEquals(RealtimeCanonicalSessionEndResult.ENDED, result)
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.SessionStarted)
        assertTrue(source.receive() is RealtimeCanonicalStreamEvent.EpochStarted)
        assertEquals(0L, (source.receive() as RealtimeCanonicalStreamEvent.PcmFrame).frame.startSampleIndex)
        assertEquals(320L, (source.receive() as RealtimeCanonicalStreamEvent.PcmFrame).frame.startSampleIndex)
        val ended = source.receive() as RealtimeCanonicalStreamEvent.SessionEnded
        assertEquals(RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED, ended.reason)
        assertEquals(640L, ended.finalSampleIndexExclusive)
        assertFalse(ended.drainTimedOut)
        assertFalse(source.snapshot().sessionActive)
    }

    @Test
    fun runtimeCloseCanTerminateActiveSessionWithoutStopDrain() {
        val source = RealtimeCanonicalAudioSource()
        source.offer(frame(sessionId = 2, start = 0))

        assertEquals(
            RealtimeCanonicalSessionEndResult.ENDED,
            source.endActiveSessionImmediately(RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED),
        )
        assertEquals(RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED, source.snapshot().lastSessionEndReason)
        assertEquals(1L, source.snapshot().sessionEndedEvents)
        assertFalse(source.snapshot().sessionActive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun immediateStopIsRejectedBecauseStopRequiresQuietDrain() {
        RealtimeCanonicalAudioSource().endActiveSessionImmediately(
            RealtimeCanonicalSessionEndReason.STOP,
        )
    }

    private fun frame(
        sessionId: Long,
        start: Long,
        reason: RealtimeCanonicalEpochStartReason? = RealtimeCanonicalEpochStartReason.SESSION_START,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = 1,
        startSampleIndex = start,
        samples = ShortArray(320) { 5 },
        epochStartReason = reason,
    )
}
