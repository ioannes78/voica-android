package io.github.ioannes78.voica.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedDeviceAutoConnectPolicyTest {
    @Test
    fun attemptsOnlyWhenForegroundRememberedAndNotUserDisconnected() {
        assertTrue(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = true,
                userDisconnectedThisProcess = false,
                rememberedAddress = "AA:BB:CC:DD:EE:FF",
                currentState = DeviceConnectionState.Idle,
            ),
        )
        assertFalse(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = false,
                userDisconnectedThisProcess = false,
                rememberedAddress = "AA:BB:CC:DD:EE:FF",
                currentState = DeviceConnectionState.Idle,
            ),
        )
        assertFalse(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = true,
                userDisconnectedThisProcess = true,
                rememberedAddress = "AA:BB:CC:DD:EE:FF",
                currentState = DeviceConnectionState.Idle,
            ),
        )
        assertFalse(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = true,
                userDisconnectedThisProcess = false,
                rememberedAddress = null,
                currentState = DeviceConnectionState.Idle,
            ),
        )
    }

    @Test
    fun doesNotStartSecondConnectionWhileSessionIsBusyOrReady() {
        assertFalse(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = true,
                userDisconnectedThisProcess = false,
                rememberedAddress = "AA:BB:CC:DD:EE:FF",
                currentState = DeviceConnectionState.Connecting("AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertFalse(
            RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = true,
                userDisconnectedThisProcess = false,
                rememberedAddress = "AA:BB:CC:DD:EE:FF",
                currentState = DeviceConnectionState.Ready(
                    address = "AA:BB:CC:DD:EE:FF",
                    negotiatedMtu = 517,
                    capability = MtuPolicy.evaluate(517),
                ),
            ),
        )
    }
}
