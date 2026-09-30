package io.github.ioannes78.voica.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DownloadStart(
    val actualFilename: String?,
    val rawBody: ByteArray,
)

data class DownloadEnd(
    val statusCode: Int,
    val rawBody: ByteArray,
)

data class TransferAbortResponse(
    val statusCode: Int?,
    val rawBody: ByteArray,
)

data class DeleteRecordingResponse(
    val statusCode: Int,
    val rawBody: ByteArray,
)

sealed interface FileTransferDecodeResult<out T> {
    data class Success<T>(val value: T) : FileTransferDecodeResult<T>
    data class Malformed(val reason: String) : FileTransferDecodeResult<Nothing>
}

object FileTransferProtocol {
    fun buildDownloadRequest(
        sequence: Int,
        offset: Long,
        filenameBytes: ByteArray,
    ): ByteArray {
        require(filenameBytes.isNotEmpty()) { "filenameBytes 不能为空" }
        val params = ByteArray(4 + filenameBytes.size)
        ByteCodec.writeU32Le(offset, params, 0)
        filenameBytes.copyInto(params, destinationOffset = 4)
        return ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.FILE,
            command = ProtocolConstants.File.IMPORT_REQUEST,
            params = params,
        )
    }

    fun buildRangeRequest(
        sequence: Int,
        start: Long,
        end: Long,
        filenameBytes: ByteArray,
    ): ByteArray {
        require(end >= start) { "end 必须大于或等于 start" }
        require(filenameBytes.isNotEmpty()) { "filenameBytes 不能为空" }
        val params = ByteArray(8 + filenameBytes.size)
        ByteCodec.writeU32Le(start, params, 0)
        ByteCodec.writeU32Le(end, params, 4)
        filenameBytes.copyInto(params, destinationOffset = 8)
        return ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.FILE,
            command = ProtocolConstants.File.IMPORT_SEGMENT,
            params = params,
        )
    }

    fun buildAbortRequest(sequence: Int): ByteArray =
        ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.FILE,
            command = ProtocolConstants.File.IMPORT_ABORT,
        )

    fun buildDeleteRecordingRequest(
        sequence: Int,
        deleteToken: ByteArray,
    ): ByteArray {
        require(deleteToken.isNotEmpty()) { "deleteToken 不能为空" }
        return ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.FILE,
            command = ProtocolConstants.File.DELETE_ONE,
            params = deleteToken,
        )
    }

    fun decodeDownloadStart(body: ByteArray): FileTransferDecodeResult<DownloadStart> {
        if (body.isEmpty()) {
            return FileTransferDecodeResult.Success(
                DownloadStart(actualFilename = null, rawBody = body.copyOf()),
            )
        }
        val decoded = decodeUtf8Field(body)
            ?: return FileTransferDecodeResult.Malformed(
                "download-start body contains invalid UTF-8 or non-zero bytes after NUL",
            )
        return FileTransferDecodeResult.Success(
            DownloadStart(
                actualFilename = decoded.ifEmpty { null },
                rawBody = body.copyOf(),
            ),
        )
    }

    fun decodeDownloadEnd(body: ByteArray): FileTransferDecodeResult<DownloadEnd> {
        if (body.isEmpty()) {
            return FileTransferDecodeResult.Malformed("download-end body is empty")
        }
        return FileTransferDecodeResult.Success(
            DownloadEnd(
                statusCode = body[0].toInt() and 0xFF,
                rawBody = body.copyOf(),
            ),
        )
    }

    fun decodeAbortResponse(body: ByteArray): FileTransferDecodeResult<TransferAbortResponse> =
        FileTransferDecodeResult.Success(
            TransferAbortResponse(
                statusCode = body.firstOrNull()?.toInt()?.and(0xFF),
                rawBody = body.copyOf(),
            ),
        )

    fun decodeDeleteRecordingResponse(
        body: ByteArray,
    ): FileTransferDecodeResult<DeleteRecordingResponse> {
        if (body.isEmpty()) {
            return FileTransferDecodeResult.Malformed("delete-response body is empty")
        }
        return FileTransferDecodeResult.Success(
            DeleteRecordingResponse(
                statusCode = body[0].toInt() and 0xFF,
                rawBody = body.copyOf(),
            ),
        )
    }

    private fun decodeUtf8Field(bytes: ByteArray): String? {
        val nulIndex = bytes.indexOf(0)
        val contentLength = if (nulIndex >= 0) nulIndex else bytes.size
        if (nulIndex >= 0 && bytes.drop(nulIndex + 1).any { it.toInt() != 0 }) {
            return null
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes, 0, contentLength)).toString()
        }.getOrNull()
    }
}
