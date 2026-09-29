package io.github.ioannes78.voica.protocol

@Deprecated("Stage 4 uses RawDeviceFileEntry/FileListDecoder for strict file-list decoding.")
data class FileEntry(
    val durationSeconds: Long,
    val sizeBytes: Long,
    val name: String,
    val raw: ByteArray,
) {
    val candidateNames: List<String>
        get() {
            val resolved = DeviceFilenameResolver.resolve(name)
            return listOfNotNull(resolved.resolvedFilename, name)
                .filter { it.isNotBlank() }
                .distinct()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileEntry) return false
        return durationSeconds == other.durationSeconds &&
            sizeBytes == other.sizeBytes &&
            name == other.name &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = durationSeconds.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

enum class DeviceByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
}

data class StorageCapacity(
    val remaining: Long,
    val total: Long,
    val byteOrder: DeviceByteOrder,
)

object DeviceDecoders {
    private const val MAX_PLAUSIBLE_CAPACITY_KB = 0x0400_0000L

    @Deprecated("Stage 4 uses FileListDecoder.decode and handles malformed payloads explicitly.")
    fun decodeFileList(body: ByteArray): List<FileEntry> =
        when (val result = FileListDecoder.decode(body)) {
            is FileListDecodeResult.Success -> result.chunk.entries.map { entry ->
                FileEntry(
                    durationSeconds = entry.rawTimeValue,
                    sizeBytes = entry.sizeBytes,
                    name = entry.rawFilename,
                    raw = entry.rawEntryBytes,
                )
            }
            is FileListDecodeResult.Malformed -> emptyList()
        }

    fun decodeCapacity(body: ByteArray): StorageCapacity {
        if (body.size < 8) {
            return StorageCapacity(0, 0, DeviceByteOrder.LITTLE_ENDIAN)
        }

        val le = StorageCapacity(
            ByteCodec.readU32Le(body, 0),
            ByteCodec.readU32Le(body, 4),
            DeviceByteOrder.LITTLE_ENDIAN,
        )
        val be = StorageCapacity(
            ByteCodec.readU32Be(body, 0),
            ByteCodec.readU32Be(body, 4),
            DeviceByteOrder.BIG_ENDIAN,
        )

        return when {
            isPlausible(le) -> le
            isPlausible(be) -> be
            else -> le
        }
    }

    fun decodeBattery(body: ByteArray): Int =
        body.firstOrNull()?.toInt()?.and(0xFF) ?: 0

    fun decodeBatteryState(body: ByteArray): BatteryState {
        val value = body.firstOrNull()?.toInt()?.and(0xFF)
        return when {
            value == null -> BatteryState.Unknown(null)
            value in 0..100 -> BatteryState.Level(value)
            value == ProtocolConstants.Control.BATTERY_VALUE_CHARGING -> BatteryState.Charging
            else -> BatteryState.Unknown(value)
        }
    }

    fun decodeFirmwareVersion(body: ByteArray): String? =
        decodeNullTerminatedText(body).ifBlank { null }

    fun decodeAuthCode(body: ByteArray): AuthCode {
        val text = decodeNullTerminatedText(body)
            .takeIf { value -> value.isNotBlank() && value.all { it.code in 0x20..0x7E } }
        val hex = body.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return AuthCode(text = text, hex = hex)
    }

    fun decodeRecordTime(body: ByteArray): Pair<Int, Long> {
        require(body.size >= 6) { "录音时间响应至少需要 6 字节" }
        return ByteCodec.readU16Le(body, 0) to ByteCodec.readU32Le(body, 2)
    }

    private fun decodeNullTerminatedText(body: ByteArray): String {
        val end = body.indexOf(0).let { if (it < 0) body.size else it }
        return body.copyOfRange(0, end).toString(Charsets.UTF_8).trim()
    }

    private fun isPlausible(capacity: StorageCapacity): Boolean =
        capacity.total > 0 &&
            capacity.total <= MAX_PLAUSIBLE_CAPACITY_KB &&
            capacity.remaining <= capacity.total
}
