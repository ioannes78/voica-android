package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferProtocolTest {
    @Test
    fun downloadRequestUsesExactlyProvidedFilenameBytes() {
        val filename = "note20260930-083059.opus".encodeToByteArray()
        val frame = FileTransferProtocol.buildDownloadRequest(
            sequence = 7,
            offset = 0,
            filenameBytes = filename,
        )
        val parsed = FrameParser("test").feed(frame).single()

        assertEquals(ProtocolConstants.Type.FILE, parsed.type)
        assertEquals(ProtocolConstants.File.IMPORT_REQUEST, parsed.command)
        assertEquals(4 + filename.size, parsed.body.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), parsed.body.copyOfRange(0, 4))
        assertArrayEquals(filename, parsed.body.copyOfRange(4, parsed.body.size))
    }

    @Test
    fun downloadRequestDoesNotPadShortFilenameTo24Bytes() {
        val filename = "a.opus".encodeToByteArray()
        val frame = FileTransferProtocol.buildDownloadRequest(1, 0, filename)
        val parsed = FrameParser("test").feed(frame).single()

        assertEquals(4 + filename.size, parsed.body.size)
        assertTrue(frame.size < 36)
    }

    @Test
    fun rangeRequestUsesLittleEndianOffsetsAndDynamicFilename() {
        val filename = "会议.opus".encodeToByteArray()
        val frame = FileTransferProtocol.buildRangeRequest(
            sequence = 2,
            start = 0x01020304,
            end = 0x11121314,
            filenameBytes = filename,
        )
        val parsed = FrameParser("test").feed(frame).single()

        assertEquals(ProtocolConstants.File.IMPORT_SEGMENT, parsed.command)
        assertArrayEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            parsed.body.copyOfRange(0, 4),
        )
        assertArrayEquals(
            byteArrayOf(0x14, 0x13, 0x12, 0x11),
            parsed.body.copyOfRange(4, 8),
        )
        assertArrayEquals(filename, parsed.body.copyOfRange(8, parsed.body.size))
    }

    @Test
    fun deleteRecordingUsesOpaqueTokenWithoutRebuildingFilename() {
        val token = ByteArray(28) { it.toByte() }
        val frame = FileTransferProtocol.buildDeleteRecordingRequest(
            sequence = 3,
            deleteToken = token,
        )
        val parsed = FrameParser("test").feed(frame).single()

        assertEquals(ProtocolConstants.File.DELETE_ONE, parsed.command)
        assertArrayEquals(token, parsed.body)
    }

    @Test
    fun decodesStartFilenameStrictly() {
        val body = "note20260930-083059.wav".encodeToByteArray() + byteArrayOf(0, 0)
        val result = FileTransferProtocol.decodeDownloadStart(body)
            as FileTransferDecodeResult.Success

        assertEquals("note20260930-083059.wav", result.value.actualFilename)
    }

    @Test
    fun emptyStartFilenameIsAllowedAsUnknown() {
        val result = FileTransferProtocol.decodeDownloadStart(byteArrayOf())
            as FileTransferDecodeResult.Success
        assertNull(result.value.actualFilename)
    }

    @Test
    fun rejectsNonZeroBytesAfterStartFilenameNul() {
        val result = FileTransferProtocol.decodeDownloadStart(
            byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()),
        )
        assertTrue(result is FileTransferDecodeResult.Malformed)
    }

    @Test
    fun decodesTerminalStatusBytes() {
        val end = FileTransferProtocol.decodeDownloadEnd(byteArrayOf(0))
            as FileTransferDecodeResult.Success
        val abort = FileTransferProtocol.decodeAbortResponse(byteArrayOf(0))
            as FileTransferDecodeResult.Success
        val delete = FileTransferProtocol.decodeDeleteRecordingResponse(byteArrayOf(0))
            as FileTransferDecodeResult.Success

        assertEquals(0, end.value.statusCode)
        assertEquals(0, abort.value.statusCode)
        assertEquals(0, delete.value.statusCode)
    }
}
