package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSourceSnapshot
import io.github.ioannes78.voica.ble.RealtimeAudioIngressSnapshot
import io.github.ioannes78.voica.ble.RealtimeCommandFenceSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePauseDrainBarrierTest {
    @Test
    fun `pause barrier waits for every accepted block and packet before exposing canonical boundary`() =
        runBlocking {
            val snapshots =
                ArrayDeque(
                    listOf(
                        snapshot(
                            acceptedBlocks = 2,
                            queueBlocks = 1,
                            receivedBlocks = 1,
                            packetizedPackets = 4,
                            decodeSuccess = 4,
                        ),
                        snapshot(
                            acceptedBlocks = 2,
                            queueBlocks = 0,
                            receivedBlocks = 2,
                            packetizedPackets = 8,
                            decodeSuccess = 6,
                        ),
                        snapshot(
                            acceptedBlocks = 2,
                            queueBlocks = 0,
                            receivedBlocks = 2,
                            packetizedPackets = 8,
                            decodeSuccess = 8,
                            sourceActive = true,
                            sessionId = 9,
                            epochId = 3,
                            nextSampleIndex = 1_280,
                        ),
                    ),
                )
            var last = snapshots.first()
            var now = 0L
            val barrier =
                RealtimePauseDrainBarrier(
                    snapshotProvider = {
                        if (snapshots.isNotEmpty()) last = snapshots.removeFirst()
                        last
                    },
                    sleeper = { now += it * 1_000_000L },
                    nowNanos = { now },
                )

            val outcome = barrier.await(maxWaitMs = 100, pollMs = 1)

            assertEquals(RealtimePauseDrainStatus.QUIESCENT, outcome.status)
            assertEquals(2L, outcome.acceptedBlocksAtPause)
            assertEquals(8L, outcome.settledPackets)
            assertEquals(
                RealtimePausedCanonicalBoundary(
                    sessionId = 9,
                    epochId = 3,
                    endSampleIndexExclusive = 1_280,
                    transportGeneration = 7,
                ),
                outcome.boundary,
            )
        }

    @Test
    fun `pause barrier rejects any audio accepted after pause snapshot`() = runBlocking {
        val states =
            ArrayDeque(
                listOf(
                    snapshot(acceptedBlocks = 4, queueBlocks = 1),
                    snapshot(acceptedBlocks = 5, queueBlocks = 1),
                ),
            )
        var last = states.first()
        val barrier =
            RealtimePauseDrainBarrier(
                snapshotProvider = {
                    if (states.isNotEmpty()) last = states.removeFirst()
                    last
                },
                sleeper = {},
            )

        val outcome = barrier.await(maxWaitMs = 100)

        assertEquals(RealtimePauseDrainStatus.AUDIO_ACCEPTED_AFTER_PAUSE, outcome.status)
        assertEquals(4L, outcome.acceptedBlocksAtPause)
    }

    @Test
    fun `pause with no canonical media returns explicit no-media result`() = runBlocking {
        val state =
            snapshot(
                acceptedBlocks = 0,
                queueBlocks = 0,
                receivedBlocks = 0,
                packetizedPackets = 0,
                decodeSuccess = 0,
                sourceActive = false,
            )
        val barrier = RealtimePauseDrainBarrier({ state }, sleeper = {})

        val outcome = barrier.await(maxWaitMs = 100)

        assertEquals(RealtimePauseDrainStatus.NO_CANONICAL_MEDIA, outcome.status)
        assertNull(outcome.boundary)
    }

    @Test
    fun `pause barrier never accepts reopened gate or generation change`() = runBlocking {
        val reopened = snapshot(acceptedBlocks = 1, gateOpen = true)
        val reopenedOutcome = RealtimePauseDrainBarrier({ reopened }, sleeper = {}).await()
        assertEquals(RealtimePauseDrainStatus.AUDIO_GATE_REOPENED, reopenedOutcome.status)

        val states =
            ArrayDeque(
                listOf(
                    snapshot(acceptedBlocks = 1, queueBlocks = 1, generation = 7),
                    snapshot(acceptedBlocks = 1, queueBlocks = 1, generation = 8),
                ),
            )
        var last = states.first()
        val changed =
            RealtimePauseDrainBarrier(
                snapshotProvider = {
                    if (states.isNotEmpty()) last = states.removeFirst()
                    last
                },
                sleeper = {},
            ).await(maxWaitMs = 100)
        assertEquals(RealtimePauseDrainStatus.TRANSPORT_CHANGED, changed.status)
    }

    @Test
    fun `unsettled native packet work times out instead of fabricating pause boundary`() = runBlocking {
        val state =
            snapshot(
                acceptedBlocks = 1,
                queueBlocks = 0,
                receivedBlocks = 1,
                packetizedPackets = 4,
                decodeSuccess = 3,
                sourceActive = true,
                sessionId = 1,
                epochId = 1,
                nextSampleIndex = 960,
            )
        var now = 0L
        val barrier =
            RealtimePauseDrainBarrier(
                snapshotProvider = { state },
                sleeper = { now += it * 1_000_000L },
                nowNanos = { now },
            )

        val outcome = barrier.await(maxWaitMs = 3, pollMs = 1)

        assertEquals(RealtimePauseDrainStatus.TIMED_OUT, outcome.status)
        assertNull(outcome.boundary)
        assertEquals(3L, outcome.settledPackets)
        assertTrue(outcome.packetizedPackets > outcome.settledPackets)
    }

    private fun snapshot(
        acceptedBlocks: Long,
        queueBlocks: Int = 0,
        receivedBlocks: Long = 0,
        packetizedPackets: Long = 0,
        decodeSuccess: Long = 0,
        sourceActive: Boolean = false,
        sessionId: Long? = null,
        epochId: Long? = null,
        nextSampleIndex: Long = 0,
        generation: Long? = 7,
        gateOpen: Boolean = false,
    ) = RealtimeAudioRuntimeSnapshot(
        transportGeneration = generation,
        phase = RealtimeRuntimePhase.PAUSED,
        fence = RealtimeCommandFenceSnapshot(audioGateOpen = gateOpen),
        ingress =
            RealtimeAudioIngressSnapshot(
                acceptedBlocks = acceptedBlocks,
                queueBlocks = queueBlocks,
            ),
        pipeline =
            RealtimeCanonicalAudioPipelineSnapshot(
                receivedBlocks = receivedBlocks,
                packetizedPackets = packetizedPackets,
                decodeSuccess = decodeSuccess,
                activeEpochId = epochId,
                nextSampleIndex = nextSampleIndex,
            ),
        source =
            RealtimeCanonicalAudioSourceSnapshot(
                sessionActive = sourceActive,
                activeSessionId = sessionId,
                activeEpochId = epochId,
            ),
    )
}
