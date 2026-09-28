package io.github.ioannes78.voica.protocol

data class FileEntry(
    val durationSeconds: Long,
    val sizeBytes: Long,
    val name: String,
    val raw: ByteArray,
) {
    val candidateNames: List<String>
        get() {
            val normalized = name.trimEnd('.')
            if (normalized.isBlank()) return emptyList()

            val lower = normalized.lowercase()
            val base = when {
                lower.endsWith(".opus") -> normalized.dropLast(5)
                lower.endsWith(".wav") -> normalized.dropLast(4)
                lower.endsWith(".mp3") -> normalized.dropLast(4)
                else -> normalized
            }

            return listOf("$base.opus", "$base.wav", name)
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

    fun decodeFileList(body: ByteArray): List<FileEntry> {
        if (body.size < 4) return emptyList()

        val declaredCount = ByteCodec.readU32Be(body, 0)
        val availableCount = (body.size - 4) / ProtocolConstants.LIST_ENTRY_LENGTH
        val parseCount = minOf(declaredCount, availableCount.toLong()).toInt()

        val entries = ArrayList<FileEntry>(parseCount)
        var offset = 4

        repeat(parseCount) {
            val duration = ByteCodec.readU32Be(body, offset)
            val size = ByteCodec.readU32Be(body, offset + 4)
            val nameBytes = body.copyOfRange(
                offset + 8,
                offset + 8 + ProtocolConstants.LIST_NAME_LENGTH,
            )
            val nulIndex = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
            val name = nameBytes.copyOfRange(0, nulIndex).toString(Charsets.UTF_8)
            val raw = body.copyOfRange(
                offset,
                offset + ProtocolConstants.LIST_ENTRY_LENGTH,
            )
            entries += FileEntry(duration, size, name, raw)
            offset += ProtocolConstants.LIST_ENTRY_LENGTH
        }

        return entries
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

    fun decodeRecordTime(body: ByteArray): Pair<Int, Long> {
        if (body.size < 6) return 0 to 0L
        return ByteCodec.readU16Le(body, 0) to ByteCodec.readU32Le(body, 2)
    }

    private fun isPlausible(capacity: StorageCapacity): Boolean =
        capacity.total > 0 &&
            capacity.total <= MAX_PLAUSIBLE_CAPACITY_KB &&
            capacity.remaining <= capacity.total
}
