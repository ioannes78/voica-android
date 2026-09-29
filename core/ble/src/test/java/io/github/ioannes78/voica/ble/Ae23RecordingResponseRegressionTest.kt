package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ae23RecordingResponseRegressionTest {
    @Test
    fun ae23StateResponseCompletesPendingRequestEvenWhenSequenceDiffers() = runTest {
        val client = DeviceCommandClient(
            writer = { true },
            responseTimeoutMs = 1_000,
        )
        val router = NotificationRouter()

        val pending = async {
            client.request(
                expectedType = ProtocolConstants.Type.KEY,
                expectedCommand = ProtocolConstants.Key.STATE_RESPONSE,
                buildRequest = ProtocolCodec::buildGetRecordState,
            )
        }
        runCurrent()

        val response = router.accept(
            BleUuids.AE23_NOTIFY,
            ProtocolCodec.buildCommand(
                sequence = 44,
                type = ProtocolConstants.Type.KEY,
                command = ProtocolConstants.Key.STATE_RESPONSE,
                params = byteArrayOf(2),
            ),
        ).single()

        assertTrue(client.accept(response))
        val result = pending.await() as DeviceCommandResult.Success
        assertEquals(NotificationSource.AE23, result.source)
        assertEquals(44, result.response.sequence)
        assertEquals(2, result.response.body.single().toInt())
    }

    @Test
    fun hardwareStartDoesNotCompletePendingStateQuery() = runTest {
        val client = DeviceCommandClient(
            writer = { true },
            responseTimeoutMs = 1_000,
        )
        val router = NotificationRouter()

        val pending = async {
            client.request(
                expectedType = ProtocolConstants.Type.KEY,
                expectedCommand = ProtocolConstants.Key.STATE_RESPONSE,
                buildRequest = ProtocolCodec::buildGetRecordState,
            )
        }
        runCurrent()

        val hardwareEvent = router.accept(
            BleUuids.AE23_NOTIFY,
            ProtocolCodec.buildCommand(
                sequence = 80,
                type = ProtocolConstants.Type.KEY,
                command = ProtocolConstants.Key.HARDWARE_RECORD_START,
            ),
        ).single()
        assertFalse(client.accept(hardwareEvent))

        val response = router.accept(
            BleUuids.AE22_NOTIFY,
            ProtocolCodec.buildCommand(
                sequence = 81,
                type = ProtocolConstants.Type.KEY,
                command = ProtocolConstants.Key.STATE_RESPONSE,
                params = byteArrayOf(1),
            ),
        ).single()
        assertTrue(client.accept(response))

        val result = pending.await() as DeviceCommandResult.Success
        assertEquals(ProtocolConstants.Key.STATE_RESPONSE, result.response.command)
    }
}
