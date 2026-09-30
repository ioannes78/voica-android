package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FileTransferDecodeResult
import io.github.ioannes78.voica.protocol.FileTransferProtocol

sealed interface RemoteDeleteCommandOutcome {
    data class Accepted(
        val statusCode: Int,
        val source: NotificationSource?,
        val latencyMs: Long?,
        val responseBody: ByteArray,
    ) : RemoteDeleteCommandOutcome

    data class Rejected(
        val statusCode: Int?,
        val source: NotificationSource?,
        val latencyMs: Long?,
        val responseBody: ByteArray?,
        val error: FileOperationError,
    ) : RemoteDeleteCommandOutcome

    data class WriteFailed(
        val error: FileOperationError = FileOperationError(
            FileOperationErrorCode.DELETE_WRITE_FAILED,
        ),
    ) : RemoteDeleteCommandOutcome

    data class OutcomeUnknown(
        val error: FileOperationError,
    ) : RemoteDeleteCommandOutcome
}

object RemoteDeletePolicy {
    fun classify(result: DeviceCommandResult): RemoteDeleteCommandOutcome =
        when (result) {
            DeviceCommandResult.WriteFailed ->
                RemoteDeleteCommandOutcome.WriteFailed()

            DeviceCommandResult.ResponseTimedOut ->
                RemoteDeleteCommandOutcome.OutcomeUnknown(
                    FileOperationError(
                        FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN,
                        "DELETE response timed out after request write",
                    ),
                )

            DeviceCommandResult.Cancelled ->
                RemoteDeleteCommandOutcome.OutcomeUnknown(
                    FileOperationError(
                        FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN,
                        "DELETE response cancelled after request write",
                    ),
                )

            is DeviceCommandResult.Success ->
                when (
                    val decoded = FileTransferProtocol.decodeDeleteRecordingResponse(
                        result.response.body,
                    )
                ) {
                    is FileTransferDecodeResult.Success ->
                        if (decoded.value.statusCode == SUCCESS_STATUS) {
                            RemoteDeleteCommandOutcome.Accepted(
                                statusCode = decoded.value.statusCode,
                                source = result.source,
                                latencyMs = result.latencyMs,
                                responseBody = result.response.body.copyOf(),
                            )
                        } else {
                            RemoteDeleteCommandOutcome.Rejected(
                                statusCode = decoded.value.statusCode,
                                source = result.source,
                                latencyMs = result.latencyMs,
                                responseBody = result.response.body.copyOf(),
                                error = FileOperationError(
                                    FileOperationErrorCode.DELETE_REJECTED,
                                    "remote status=" + decoded.value.statusCode,
                                    remoteStatusCode = decoded.value.statusCode,
                                ),
                            )
                        }

                    is FileTransferDecodeResult.Malformed ->
                        RemoteDeleteCommandOutcome.Rejected(
                            statusCode = null,
                            source = result.source,
                            latencyMs = result.latencyMs,
                            responseBody = result.response.body.copyOf(),
                            error = FileOperationError(
                                FileOperationErrorCode.DELETE_REJECTED,
                                decoded.reason,
                            ),
                        )
                }
        }

    private const val SUCCESS_STATUS = 0
}
