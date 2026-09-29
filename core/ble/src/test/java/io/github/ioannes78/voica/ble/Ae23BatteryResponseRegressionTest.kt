package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ae23BatteryResponseRegressionTest {
    @Test
    fun ae23BatteryResponseCanCompletePendingBatteryRequest() = runTest {
        val client = DeviceCommandClient(
            writer = { true },
            responseTimeoutMs = 1_000,
        )
        val router = NotificationRouter()

        val pending = async {
            client.request(
                expectedType = ProtocolConstants.Type.CONTROL,
                expectedCommand = ProtocolConstants.Control.BATTERY_RESPONSE,
                buildRequest = ProtocolCodec::buildGetBattery,
            )
        }
        runCurrent()

        val responseBytes = ProtocolCodec.buildCommand(
            sequence = 20,
            type = ProtocolConstants.Type.CONTROL,
            command = ProtocolConstants.Control.BATTERY_RESPONSE,
            params = byteArrayOf(73),
        )
        val routed = router.accept(BleUuids.AE23_NOTIFY, responseBytes).single()

        assertEquals(NotificationSource.AE23, routed.source)
        assertTrue(client.accept(routed.frame))

        val result = pending.await() as DeviceCommandResult.Success
        assertEquals(73, result.response.body.single().toInt() and 0xFF)
        assertEquals(20, result.response.sequence)
    }

    @Test
    fun ae23ChargingValue110DecodesAsCharging() {
        val router = NotificationRouter()
        val responseBytes = ProtocolCodec.buildCommand(
            sequence = 21,
            type = ProtocolConstants.Type.CONTROL,
            command = ProtocolConstants.Control.BATTERY_RESPONSE,
            params = byteArrayOf(110),
        )

        val routed = router.accept(BleUuids.AE23_NOTIFY, responseBytes).single()
        assertEquals(
            io.github.ioannes78.voica.protocol.BatteryState.Charging,
            io.github.ioannes78.voica.protocol.DeviceDecoders.decodeBatteryState(
                routed.frame.body,
            ),
        )
    }
}
