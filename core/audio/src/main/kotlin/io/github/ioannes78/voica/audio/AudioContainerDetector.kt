package io.github.ioannes78.voica.audio

object AudioContainerDetector {
    fun detect(prefix: ByteArray): AudioContainerKind {
        if (prefix.size >= 12 &&
            prefix.matchesAscii(0, "RIFF") &&
            prefix.matchesAscii(8, "WAVE")
        ) {
            return AudioContainerKind.WAV
        }

        if (prefix.size >= 4 && prefix.matchesAscii(0, "fLaC")) {
            return AudioContainerKind.FLAC
        }

        if (prefix.size >= 8 && prefix.matchesAscii(4, "ftyp")) {
            return AudioContainerKind.MP4
        }

        if (
            prefix.size >= 3 &&
            prefix[0] == 'I'.code.toByte() &&
            prefix[1] == 'D'.code.toByte() &&
            prefix[2] == '3'.code.toByte()
        ) {
            return AudioContainerKind.MP3
        }

        if (prefix.size >= 2) {
            val b0 = prefix[0].toInt() and 0xFF
            val b1 = prefix[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF6) == 0xF0) {
                return AudioContainerKind.AAC_ADTS
            }
            if (
                b0 == 0xFF &&
                (b1 and 0xE0) == 0xE0 &&
                (b1 and 0x18) != 0x08
            ) {
                return AudioContainerKind.MP3
            }
        }

        if (prefix.size >= 8 && prefix.matchesAscii(0, "OggS")) {
            val searchEnd = minOf(prefix.size - OPUS_HEAD.length, MAX_OGG_PREFIX_SEARCH)
            if (searchEnd >= 0) {
                for (offset in 0..searchEnd) {
                    if (prefix.matchesAscii(offset, OPUS_HEAD)) {
                        return AudioContainerKind.OGG_OPUS
                    }
                }
            }
        }

        return AudioContainerKind.UNKNOWN
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        if (offset < 0 || offset + bytes.size > size) return false
        return bytes.indices.all { index -> this[offset + index] == bytes[index] }
    }

    private const val OPUS_HEAD = "OpusHead"
    private const val MAX_OGG_PREFIX_SEARCH = 4 * 1024
}
