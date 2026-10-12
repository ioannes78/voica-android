package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCommandFenceConfirmedAttachTest {
    @Test
    fun confirmedAttachCreatesBoundaryBeforeOpeningGateWithoutCommandFence() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        val events = mutableListOf<String>()

        assertEquals(
            RealtimeConfirmedExistingRecordingAttachResult.ATTACHED,
            fence.attachConfirmedExistingRecording(
                transportGeneration = 12,
                isGenerationCurrent = { true },
                beforeOpen = { events += "boundary" },
            ),
        )
        assertEquals(listOf("boundary"), events)
        assertTrue(fence.snapshot().audioGateOpen)
        assertEquals(0L, fence.snapshot().preparedCount)
        assertEquals(0L, fence.snapshot().committedCount)
        assertEquals(1L, fence.snapshot().confirmedExistingRecordingAttachSuccessCount)

        assertEquals(
            RealtimeAudioOfferResult.ACCEPTED,
            fence.offer(audioNotification(sequence = 1), 12, 100),
        )
        assertEquals(listOf(1), downstream.frames.map { it.notification.frame.sequence })
    }

    @Test
    fun staleGenerationNeverCreatesBoundaryOrOpensGate() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)
        var boundaryCreated = false

        assertEquals(
            RealtimeConfirmedExistingRecordingAttachResult.STALE_GENERATION,
            fence.attachConfirmedExistingRecording(
                transportGeneration = 12,
                isGenerationCurrent = { false },
                beforeOpen = { boundaryCreated = true },
            ),
        )
        assertFalse(boundaryCreated)
        assertFalse(fence.snapshot().audioGateOpen)
        assertEquals(1L, fence.snapshot().confirmedExistingRecordingAttachFailureCount)
    }

    @Test
    fun boundaryFailureKeepsGateClosedAndQuarantinesGeneration() {
        val downstream = CapturingConsumer()
        val fence = RealtimeCommandFence(downstream)

        assertEquals(
            RealtimeConfirmedExistingRecordingAttachResult.COMMIT_ACTION_FAILED,
            fence.attachConfirmedExistingRecording(
                transportGeneration = 7,
                isGenerationCurrent = { true },
                beforeOpen = { error("boundary failure") },
            ),
        )
        assertFalse(fence.snapshot().audioGateOpen)
        assertEquals(7L, fence.snapshot().quarantinedGeneration)
        assertEquals(1L, fence.snapshot().commitActionFailureCount)
        assertEquals(1L, fence.snapshot().confirmedExistingRecordingAttachFailureCount)
    }

    private fun audioNotification(sequence: Int): RoutedNotification =
        RoutedNotification(
            source = NotificationSource.AE22,
            frame = ProtocolFrame(
                sequence = sequence,
                data = byteArrayOf(
                    ProtocolConstants.Type.REALTIME.toByte(),
                    ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
                ) + ByteArray(RealtimeAudioIngress.REALTIME_BLOCK_BYTES) { index -> index.toByte() },
            ),
        )

    private class CapturingConsumer : RealtimeAudioConsumer {
        val frames = mutableListOf<CapturedFrame>()

        override fun offer(
            notification: RoutedNotification,
            sessionGeneration: Long,
            receivedAtNanos: Long,
        ): RealtimeAudioOfferResult {
            frames += CapturedFrame(notification, sessionGeneration, receivedAtNanos)
            return RealtimeAudioOfferResult.ACCEPTED
        }

        override fun onSessionClosed(sessionGeneration: Long) = Unit
    }

    private data class CapturedFrame(
        val notification: RoutedNotification,
        val generation: Long,
        val receivedAtNanos: Long,
    )
}
