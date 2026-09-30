package io.github.ioannes78.voica.ble

import java.io.ByteArrayOutputStream

class RangeProbeDataSink(
    private val maximumBytes: Int = DEFAULT_MAXIMUM_BYTES,
) : FileTransferDataSink {
    private val output = ByteArrayOutputStream()
    private var aborted = false

    override suspend fun write(bytes: ByteArray) {
        if (aborted) {
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.LOCAL_WRITE_FAILED,
                    "range probe sink is aborted",
                ),
            )
        }
        if (output.size() + bytes.size > maximumBytes) {
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.DATA_PIPELINE_OVERFLOW,
                    "range probe exceeded $maximumBytes bytes",
                ),
            )
        }
        output.write(bytes)
    }

    override suspend fun abort() {
        aborted = true
        output.reset()
    }

    fun bytes(): ByteArray = output.toByteArray()

    private companion object {
        const val DEFAULT_MAXIMUM_BYTES = 4096
    }
}
