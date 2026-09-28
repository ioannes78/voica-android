package io.github.ioannes78.voica.protocol

internal object ByteCodec {
    fun readU16Le(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= data.size)
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU32Le(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size)
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun readU32Be(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size)
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    fun writeU32Le(value: Long, target: ByteArray, offset: Int) {
        require(value in 0..0xFFFF_FFFFL)
        require(offset >= 0 && offset + 4 <= target.size)
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
