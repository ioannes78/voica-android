package io.github.ioannes78.voica.audio

object AudioContainerDetector {
    fun detect(prefix: ByteArray): AudioContainerKind {
        if (prefix.size >= 12 &&
            prefix.matchesAscii(0, "RIFF") &&
            prefix.matchesAscii(8, "WAVE")
        ) {
            return AudioContainerKind.WAV
        }

        if (prefix.size >= 8 && prefix.matchesAscii(0, "OggS")) {
            val searchEnd = minOf(prefix.size - OPUS_HEAD.length, MAX_OGG_PREFIX_SEARCH)
            for (offset in 0..searchEnd.coerceAtLeast(-1)) {
                if (offset >= 0 && prefix.matchesAscii(offset, OPUS_HEAD)) {
                    return AudioContainerKind.OGG_OPUS
                }
            }
        }

        return if (prefix.isNotEmpty()) {
            AudioContainerKind.DEVICE_RAW_CANDIDATE
        } else {
            AudioContainerKind.UNKNOWN
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        if (offset < 0 || offset + bytes.size > size) return false
        return bytes.indices.all { index -> this[offset + index] == bytes[index] }
    }

    private const val OPUS_HEAD = "OpusHead"
    private const val MAX_OGG_PREFIX_SEARCH = 4 * 1024
}
