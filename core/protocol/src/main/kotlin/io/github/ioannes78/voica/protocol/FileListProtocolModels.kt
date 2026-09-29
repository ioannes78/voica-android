package io.github.ioannes78.voica.protocol

data class RawDeviceFileEntry(
    val rawTimeValue: Long,
    val sizeBytes: Long,
    val rawFilename: String,
    val rawFilenameBytes: ByteArray,
    val filenameFieldLength: Int,
    val rawEntryBytes: ByteArray,
)

data class FileListChunk(
    val declaredCount: Int,
    val filenameFieldLength: Int?,
    val entries: List<RawDeviceFileEntry>,
    val bodySize: Int,
)

sealed interface FileListDecodeResult {
    data class Success(val chunk: FileListChunk) : FileListDecodeResult
    data class Malformed(val reason: String) : FileListDecodeResult
}
