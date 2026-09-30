package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FrameParser
import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeletePolicyTest {
    @Test
    fun statusZeroIsAccepted() {
        val result = DeviceCommandResult.Success(
            requestSequence = 1,
            response = response(byteArrayOf(0)),
            source = NotificationSource.AE23,
            latencyMs = 42,
        )

        val outcome = RemoteDeletePolicy.classify(result)

        assertTrue(outcome is RemoteDeleteCommandOutcome.Accepted)
        outcome as RemoteDeleteCommandOutcome.Accepted
        assertEquals(0, outcome.statusCode)
        assertEquals(NotificationSource.AE23, outcome.source)
        assertEquals(42L, outcome.latencyMs)
    }

    @Test
    fun nonZeroStatusIsRejected() {
        val outcome = RemoteDeletePolicy.classify(
            DeviceCommandResult.Success(
                requestSequence = 1,
                response = response(byteArrayOf(2)),
            ),
        )

        assertTrue(outcome is RemoteDeleteCommandOutcome.Rejected)
        outcome as RemoteDeleteCommandOutcome.Rejected
        assertEquals(FileOperationErrorCode.DELETE_REJECTED, outcome.error.code)
        assertEquals(2, outcome.error.remoteStatusCode)
    }

    @Test
    fun timeoutAfterWriteIsOutcomeUnknown() {
        val outcome = RemoteDeletePolicy.classify(DeviceCommandResult.ResponseTimedOut)

        assertTrue(outcome is RemoteDeleteCommandOutcome.OutcomeUnknown)
        outcome as RemoteDeleteCommandOutcome.OutcomeUnknown
        assertEquals(FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN, outcome.error.code)
    }

    @Test
    fun writeFailureIsNotOutcomeUnknown() {
        val outcome = RemoteDeletePolicy.classify(DeviceCommandResult.WriteFailed)

        assertTrue(outcome is RemoteDeleteCommandOutcome.WriteFailed)
        outcome as RemoteDeleteCommandOutcome.WriteFailed
        assertEquals(FileOperationErrorCode.DELETE_WRITE_FAILED, outcome.error.code)
    }

    private fun response(body: ByteArray) =
        FrameParser("delete-test").feed(
            ProtocolCodec.buildCommand(
                sequence = 9,
                type = ProtocolConstants.Type.FILE,
                command = ProtocolConstants.File.DELETE_ONE_RESPONSE,
                params = body,
            ),
        ).single()
}
