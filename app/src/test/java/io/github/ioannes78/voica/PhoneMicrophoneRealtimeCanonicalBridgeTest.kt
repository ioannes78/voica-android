package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneRealtimeCanonicalBridgeTest {
    @Test
    fun `bounded realtime overflow is explicit and cannot backpressure post-write producer`() {
        val bridge =
            PhoneMicrophoneRealtimeCanonicalBridge(
                maxQueuedPcmFrames = 1,
                maxQueuedPcmSamples = 1_280,
            )

        repeat(8) { index ->
            bridge.offer(
                RealtimeCanonicalPcmFrame(
                    sessionId = 11L,
                    epochId = 1L,
                    startSampleIndex = index * 1_280L,
                    samples = ShortArray(1_280) { index.toShort() },
                    epochStartReason =
                        if (index == 0) RealtimeCanonicalEpochStartReason.SESSION_START else null,
                ),
            )
        }

        val snapshot = bridge.snapshot()
        assertEquals(8L, snapshot.offeredFrames)
        assertEquals(10_240L, snapshot.offeredSamples)
        assertEquals(11L, snapshot.lastOfferedSessionId)
        assertEquals(1L, snapshot.lastOfferedEpochId)
        assertEquals(10_240L, snapshot.lastOfferedEndSampleIndexExclusive)
        assertTrue(snapshot.source.overflowEvents > 0L || snapshot.rejectedFrames > 0L)
        assertTrue(snapshot.source.droppedSamples > 0L || snapshot.rejectedSamples > 0L)
        bridge.close()
    }

    @Test
    fun `bridge exposes exact epoch-local pause boundary after resume epoch`() {
        val bridge = PhoneMicrophoneRealtimeCanonicalBridge()
        bridge.offer(
            RealtimeCanonicalPcmFrame(
                sessionId = 7L,
                epochId = 1L,
                startSampleIndex = 0L,
                samples = ShortArray(1_280),
                epochStartReason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        bridge.offer(
            RealtimeCanonicalPcmFrame(
                sessionId = 7L,
                epochId = 2L,
                startSampleIndex = 0L,
                samples = ShortArray(640),
                epochStartReason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY,
            ),
        )

        val snapshot = bridge.snapshot()
        assertEquals(7L, snapshot.lastOfferedSessionId)
        assertEquals(2L, snapshot.lastOfferedEpochId)
        assertEquals(640L, snapshot.lastOfferedEndSampleIndexExclusive)

        runBlocking { bridge.endAfterStop() }
        bridge.close()
    }
}
