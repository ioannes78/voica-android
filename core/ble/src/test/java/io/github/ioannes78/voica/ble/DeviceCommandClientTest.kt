package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FrameParser
import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandClientTest {
    @Test
    fun pendingIsRegisteredBeforeWriteSoImmediateResponseIsNotLost() = runTest {
        lateinit var client: DeviceCommandClient
        client = DeviceCommandClient(
            writer = { requestBytes ->
                val request = FrameParser("tx").feed(requestBytes).single()
                client.accept(
                    ProtocolFrame(
                        sequence = request.sequence,
                        data = byteArrayOf(
                            ProtocolConstants.Type.CONTROL.toByte(),
                            ProtocolConstants.Control.BATTERY_RESPONSE.toByte(),
                            50,
                        ),
                    ),
                )
                true
            },
            responseTimeoutMs = 1_000,
        )

        val result = client.request(
            expectedType = ProtocolConstants.Type.CONTROL,
            expectedCommand = ProtocolConstants.Control.BATTERY_RESPONSE,
            buildRequest = ProtocolCodec::buildGetBattery,
        )

        assertTrue(result is DeviceCommandResult.Success)
        result as DeviceCommandResult.Success
        assertEquals(50, result.response.body.single().toInt())
    }

    @Test
    fun unrelatedFrameDoesNotCompletePendingRequest() = runTest {
        lateinit var client: DeviceCommandClient
        var unrelatedAccepted = true
        client = DeviceCommandClient(
            writer = { requestBytes ->
                val request = FrameParser("tx").feed(requestBytes).single()
                unrelatedAccepted = client.accept(
                    ProtocolFrame(
                        sequence = request.sequence,
                        data = byteArrayOf(
                            ProtocolConstants.Type.CONTROL.toByte(),
                            ProtocolConstants.Control.VERSION_RESPONSE.toByte(),
                            1,
                        ),
                    ),
                )
                client.accept(
                    ProtocolFrame(
                        sequence = request.sequence,
                        data = byteArrayOf(
                            ProtocolConstants.Type.CONTROL.toByte(),
                            ProtocolConstants.Control.BATTERY_RESPONSE.toByte(),
                            88,
                        ),
                    ),
                )
                true
            },
            responseTimeoutMs = 1_000,
        )

        val result = client.request(
            expectedType = ProtocolConstants.Type.CONTROL,
            expectedCommand = ProtocolConstants.Control.BATTERY_RESPONSE,
            buildRequest = ProtocolCodec::buildGetBattery,
        )

        assertFalse(unrelatedAccepted)
        assertTrue(result is DeviceCommandResult.Success)
        assertEquals(
            88,
            (result as DeviceCommandResult.Success).response.body.single().toInt(),
        )
    }
}
