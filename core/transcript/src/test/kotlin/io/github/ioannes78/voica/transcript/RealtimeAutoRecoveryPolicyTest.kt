package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeAutoRecoveryPolicyTest {
    @Test
    fun `resume requires same device same recording active intent and new generation`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.RESUME,
            RealtimeAutoRecoveryPolicy.evaluate(validContext()),
        )
    }

    @Test
    fun `unknown recording identity never guesses continuity`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_UNCONFIRMED,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(currentRecordingIdentity = null),
            ),
        )
    }

    @Test
    fun `different physical recording blocks automatic resume`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_MISMATCH,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(currentRecordingIdentity = "recording-b"),
            ),
        )
    }

    @Test
    fun `different device blocks automatic resume`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.DEVICE_MISMATCH,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(currentDeviceIdentity = "device-b"),
            ),
        )
    }

    @Test
    fun `device recording already stopped blocks automatic resume`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.RECORDING_NOT_ACTIVE,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(currentDeviceRecordingActive = false),
            ),
        )
    }

    @Test
    fun `user intent is authoritative`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.NO_ACTIVE_REALTIME_INTENT,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(realtimeIntentActiveBeforeDisconnect = false),
            ),
        )
        assertEquals(
            RealtimeAutoRecoveryDecision.USER_DISABLED_REALTIME,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(userDisabledRealtimeSinceDisconnect = true),
            ),
        )
    }

    @Test
    fun `same transport generation cannot masquerade as reconnect`() {
        assertEquals(
            RealtimeAutoRecoveryDecision.TRANSPORT_GENERATION_NOT_ADVANCED,
            RealtimeAutoRecoveryPolicy.evaluate(
                validContext().copy(currentTransportGeneration = 10),
            ),
        )
    }

    private fun validContext() =
        RealtimeAutoRecoveryContext(
            realtimeIntentActiveBeforeDisconnect = true,
            userDisabledRealtimeSinceDisconnect = false,
            previousDeviceIdentity = "device-a",
            currentDeviceIdentity = "device-a",
            previousRecordingIdentity = "recording-a",
            currentRecordingIdentity = "recording-a",
            currentDeviceRecordingActive = true,
            previousTransportGeneration = 10,
            currentTransportGeneration = 11,
        )
}
