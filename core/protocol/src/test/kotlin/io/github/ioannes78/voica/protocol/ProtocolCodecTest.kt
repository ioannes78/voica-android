package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    @Test
    fun importRequestIs36BytesAndMatchesGoldenFrame() {
        val actual = ProtocolCodec.buildImportRequest(
            sequence = 0,
            filename = "a.opus",
            offset = 0,
        )
        val expected = hex(
            "5A 00 DC F2 1E 00 02 02 00 00 00 00 " +
                "61 2E 6F 70 75 73 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00"
        )

        assertEquals(36, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun segmentRequestIs40Bytes() {
        val frame = ProtocolCodec.buildSegmentRequest(
            sequence = 8,
            filename = "meeting.opus",
            start = 0,
            end = 4096,
        )

        assertEquals(40, frame.size)
        val parsed = FrameParser("test").feed(frame).single()
        assertEquals(ProtocolConstants.Type.FILE, parsed.type)
        assertEquals(ProtocolConstants.File.IMPORT_SEGMENT, parsed.command)
        assertEquals(32, parsed.body.size)
    }

    @Test
    fun deleteOneUsesZeroOffsetAnd24ByteFilename() {
        val frame = ProtocolCodec.buildDeleteOneRequest(
            sequence = 9,
            filename = "note.opus",
        )

        assertEquals(36, frame.size)
        val parsed = FrameParser("test").feed(frame).single()
        assertEquals(ProtocolConstants.File.DELETE_ONE, parsed.command)
        assertEquals(28, parsed.body.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), parsed.body.copyOfRange(0, 4))
        assertEquals("note.opus", decodeZeroTerminated(parsed.body.copyOfRange(4, 28)))
    }

    @Test
    fun filenameEncodingDoesNotSplitUtf8CodePoint() {
        val source = "会议记录测试😀ABC"
        val encoded = ProtocolCodec.encodeFilename24(source)
        val used = encoded.indexOf(0).let { if (it < 0) encoded.size else it }
        val decoded = encoded.copyOfRange(0, used).toString(Charsets.UTF_8)

        assertTrue(used <= 24)
        assertFalse(decoded.contains('�'))
        assertTrue(source.startsWith(decoded))
    }

    private fun decodeZeroTerminated(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.copyOfRange(0, end).toString(Charsets.UTF_8)
    }

    private fun hex(value: String): ByteArray =
        value.trim()
            .split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
