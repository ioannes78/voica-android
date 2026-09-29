package io.github.ioannes78.voica.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object FileListDecoder {
    private const val MAX_REASONABLE_ENTRY_COUNT = 10_000
    private const val MAX_REASONABLE_FILENAME_FIELD_LENGTH = 512

    fun decode(body: ByteArray): FileListDecodeResult {
        if (body.size < 4) {
            return FileListDecodeResult.Malformed("file-list body shorter than 4-byte count")
        }

        val declaredLong = ByteCodec.readU32Be(body, 0)
        if (declaredLong > MAX_REASONABLE_ENTRY_COUNT.toLong()) {
            return FileListDecodeResult.Malformed("declared count is unreasonable: $declaredLong")
        }
        val declaredCount = declaredLong.toInt()

        if (declaredCount == 0) {
            return if (body.size == 4) {
                FileListDecodeResult.Success(
                    FileListChunk(
                        declaredCount = 0,
                        filenameFieldLength = null,
                        entries = emptyList(),
                        bodySize = body.size,
                    ),
                )
            } else {
                FileListDecodeResult.Malformed(
                    "zero-count file-list body has unexplained trailing bytes=${body.size - 4}",
                )
            }
        }

        val payloadLength = body.size - 4
        val minimumLength = declaredCount.toLong() * ProtocolConstants.LIST_BASE_ENTRY_LENGTH
        if (payloadLength.toLong() < minimumLength) {
            return FileListDecodeResult.Malformed(
                "declared count=$declaredCount requires at least $minimumLength payload bytes, got $payloadLength",
            )
        }

        val extra = payloadLength.toLong() - minimumLength
        if (extra % declaredCount != 0L) {
            return FileListDecodeResult.Malformed(
                "extra payload bytes=$extra cannot be distributed evenly across count=$declaredCount",
            )
        }

        val filenameFieldLengthLong =
            ProtocolConstants.LIST_BASE_NAME_LENGTH.toLong() + extra / declaredCount
        if (
            filenameFieldLengthLong < ProtocolConstants.LIST_BASE_NAME_LENGTH ||
            filenameFieldLengthLong > MAX_REASONABLE_FILENAME_FIELD_LENGTH
        ) {
            return FileListDecodeResult.Malformed(
                "filename field length is unreasonable: $filenameFieldLengthLong",
            )
        }

        val filenameFieldLength = filenameFieldLengthLong.toInt()
        val entryLength = 8 + filenameFieldLength
        val expectedBodySize = 4L + declaredCount.toLong() * entryLength
        if (expectedBodySize != body.size.toLong()) {
            return FileListDecodeResult.Malformed(
                "body size mismatch expected=$expectedBodySize actual=${body.size}",
            )
        }

        val entries = ArrayList<RawDeviceFileEntry>(declaredCount)
        var offset = 4
        repeat(declaredCount) { index ->
            val entryEnd = offset + entryLength
            if (entryEnd > body.size) {
                return FileListDecodeResult.Malformed(
                    "entry index=$index exceeds body boundary",
                )
            }

            val filenameField = body.copyOfRange(offset + 8, entryEnd)
            val decoded = decodeFilenameField(filenameField)
                ?: return FileListDecodeResult.Malformed(
                    "entry index=$index contains invalid UTF-8 or non-zero bytes after NUL",
                )

            entries += RawDeviceFileEntry(
                rawTimeValue = ByteCodec.readU32Be(body, offset),
                sizeBytes = ByteCodec.readU32Be(body, offset + 4),
                rawFilename = decoded,
                rawFilenameBytes = filenameField,
                filenameFieldLength = filenameFieldLength,
                rawEntryBytes = body.copyOfRange(offset, entryEnd),
            )
            offset = entryEnd
        }

        if (offset != body.size) {
            return FileListDecodeResult.Malformed(
                "unexplained trailing bytes=${body.size - offset}",
            )
        }

        return FileListDecodeResult.Success(
            FileListChunk(
                declaredCount = declaredCount,
                filenameFieldLength = filenameFieldLength,
                entries = entries,
                bodySize = body.size,
            ),
        )
    }

    private fun decodeFilenameField(field: ByteArray): String? {
        val nulIndex = field.indexOf(0)
        val contentLength = if (nulIndex >= 0) nulIndex else field.size

        if (nulIndex >= 0 && field.drop(nulIndex + 1).any { it.toInt() != 0 }) {
            return null
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return runCatching {
            decoder.decode(ByteBuffer.wrap(field, 0, contentLength)).toString()
        }.getOrNull()
    }
}
