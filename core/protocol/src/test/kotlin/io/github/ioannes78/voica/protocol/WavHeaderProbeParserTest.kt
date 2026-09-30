package io.github.ioannes78.voica.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WavHeaderProbeParserTest {
    @Test
    fun parsesRiffTotalSizeFromHeader() {
        val header = ByteArray(44)
        "RIFF".encodeToByteArray().copyInto(header, 0)
        val total = 1_280_044L
        val chunkSize = total - 8L
        repeat(4) { shift ->
            header[4 + shift] = ((chunkSize ushr (shift * 8)) and 0xFF).toByte()
        }
        "WAVE".encodeToByteArray().copyInto(header, 8)

        val result = assertIs<WavHeaderProbeResult.Success>(
            WavHeaderProbeParser.parse(header),
        )

        assertEquals(total, result.value.totalSizeBytes)
    }

    @Test
    fun needsTwelveBytesBeforeParsing() {
        val result = assertIs<WavHeaderProbeResult.NeedMoreBytes>(
            WavHeaderProbeParser.parse(ByteArray(11)),
        )
        assertEquals(12, result.minimumBytes)
    }

    @Test
    fun rejectsNonRiffHeader() {
        val bytes = ByteArray(12)
        "NOPE".encodeToByteArray().copyInto(bytes, 0)
        "WAVE".encodeToByteArray().copyInto(bytes, 8)

        assertIs<WavHeaderProbeResult.Invalid>(
            WavHeaderProbeParser.parse(bytes),
        )
    }

    @Test
    fun rejectsNonWaveRiff() {
        val bytes = ByteArray(12)
        "RIFF".encodeToByteArray().copyInto(bytes, 0)
        bytes[4] = 36
        "AVI ".encodeToByteArray().copyInto(bytes, 8)

        assertIs<WavHeaderProbeResult.Invalid>(
            WavHeaderProbeParser.parse(bytes),
        )
    }
}
