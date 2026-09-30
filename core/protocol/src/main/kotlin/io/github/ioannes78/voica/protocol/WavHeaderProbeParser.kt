package io.github.ioannes78.voica.protocol

data class WavHeaderProbe(
    val totalSizeBytes: Long,
)

sealed interface WavHeaderProbeResult {
    data class Success(val value: WavHeaderProbe) : WavHeaderProbeResult
    data class NeedMoreBytes(val minimumBytes: Int) : WavHeaderProbeResult
    data class Invalid(val reason: String) : WavHeaderProbeResult
}

object WavHeaderProbeParser {
    const val MINIMUM_HEADER_BYTES = 12

    fun parse(prefix: ByteArray): WavHeaderProbeResult {
        if (prefix.size < MINIMUM_HEADER_BYTES) {
            return WavHeaderProbeResult.NeedMoreBytes(MINIMUM_HEADER_BYTES)
        }
        if (!prefix.matchesAscii(0, "RIFF")) {
            return WavHeaderProbeResult.Invalid("missing RIFF")
        }
        if (!prefix.matchesAscii(8, "WAVE")) {
            return WavHeaderProbeResult.Invalid("missing WAVE")
        }

        val chunkSize =
            (prefix[4].toLong() and 0xFFL) or
                ((prefix[5].toLong() and 0xFFL) shl 8) or
                ((prefix[6].toLong() and 0xFFL) shl 16) or
                ((prefix[7].toLong() and 0xFFL) shl 24)
        val totalSize = chunkSize + 8L
        if (totalSize < MINIMUM_HEADER_BYTES) {
            return WavHeaderProbeResult.Invalid(
                "RIFF total size is too small: $totalSize",
            )
        }

        return WavHeaderProbeResult.Success(
            WavHeaderProbe(totalSizeBytes = totalSize),
        )
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        if (offset < 0 || offset + bytes.size > size) return false
        return bytes.indices.all { index -> this[offset + index] == bytes[index] }
    }
}
