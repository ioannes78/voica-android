package io.github.ioannes78.voica.protocol

data class ProtocolFrame(
    val sequence: Int,
    val data: ByteArray,
) {
    init {
        require(sequence in 0..255) { "sequence 必须位于 0..255" }
    }

    val type: Int
        get() = data.firstOrNull()?.toInt()?.and(0xFF) ?: -1

    val command: Int?
        get() = data.getOrNull(1)?.toInt()?.and(0xFF)

    val body: ByteArray
        get() = if (data.size > 2) data.copyOfRange(2, data.size) else byteArrayOf()

    val isAck: Boolean
        get() = data.size == 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolFrame) return false
        return sequence == other.sequence && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * sequence + data.contentHashCode()
}
