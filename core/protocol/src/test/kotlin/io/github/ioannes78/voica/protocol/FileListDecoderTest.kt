package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListDecoderTest {
    @Test
    fun decodesTwentyByteFilenameEntry() {
        val body = buildBody(
            filenameFieldLength = 20,
            Entry(120, 4096, "note20260929-120000."),
        )

        val result = FileListDecoder.decode(body) as FileListDecodeResult.Success
        val entry = result.chunk.entries.single()

        assertEquals(1, result.chunk.declaredCount)
        assertEquals(20, result.chunk.filenameFieldLength)
        assertEquals(120L, entry.rawTimeValue)
        assertEquals(4096L, entry.sizeBytes)
        assertEquals("note20260929-120000.", entry.rawFilename)
    }

    @Test
    fun decodesTwentyFourByteFilenameEntries() {
        val body = buildBody(
            filenameFieldLength = 24,
            Entry(10, 100, "note20260929-120000.opus"),
            Entry(20, 200, "note20260929-120100.opus"),
        )

        val result = FileListDecoder.decode(body) as FileListDecodeResult.Success

        assertEquals(2, result.chunk.entries.size)
        assertEquals(24, result.chunk.filenameFieldLength)
        assertEquals("note20260929-120100.opus", result.chunk.entries[1].rawFilename)
    }

    @Test
    fun supportsOtherEvenlyDistributedFilenameLengths() {
        val body = buildBody(
            filenameFieldLength = 30,
            Entry(1, 2, "meeting-room-a.opus"),
            Entry(3, 4, "meeting-room-b.opus"),
        )

        val result = FileListDecoder.decode(body) as FileListDecodeResult.Success

        assertEquals(30, result.chunk.filenameFieldLength)
    }

    @Test
    fun acceptsExactZeroCountBody() {
        val body = ByteArray(4)
        val result = FileListDecoder.decode(body) as FileListDecodeResult.Success
        assertTrue(result.chunk.entries.isEmpty())
        assertEquals(null, result.chunk.filenameFieldLength)
    }

    @Test
    fun rejectsShortBodyAndTruncatedEntry() {
        assertTrue(FileListDecoder.decode(byteArrayOf()) is FileListDecodeResult.Malformed)

        val truncated = ByteArray(4 + 10)
        putU32Be(truncated, 0, 1)
        assertTrue(FileListDecoder.decode(truncated) is FileListDecodeResult.Malformed)
    }

    @Test
    fun rejectsDeclaredCountGreaterThanAvailableEntries() {
        val body = buildBody(
            filenameFieldLength = 20,
            Entry(10, 100, "a.opus"),
        )
        putU32Be(body, 0, 2)

        assertTrue(FileListDecoder.decode(body) is FileListDecodeResult.Malformed)
    }

    @Test
    fun rejectsExtraBytesThatCannotBeDistributedAcrossEntries() {
        val valid = buildBody(
            filenameFieldLength = 20,
            Entry(10, 100, "a.opus"),
            Entry(20, 200, "b.opus"),
        )
        val malformed = valid + byteArrayOf(1)

        assertTrue(FileListDecoder.decode(malformed) is FileListDecodeResult.Malformed)
    }

    @Test
    fun rejectsNonZeroBytesAfterNulPadding() {
        val body = buildBody(
            filenameFieldLength = 20,
            Entry(10, 100, "a.opus"),
        )
        val filenameStart = 4 + 8
        body[filenameStart + 6] = 0
        body[filenameStart + 7] = 1

        assertTrue(FileListDecoder.decode(body) is FileListDecodeResult.Malformed)
    }

    @Test
    fun rejectsInvalidUtf8() {
        val body = buildBody(
            filenameFieldLength = 20,
            Entry(10, 100, "a.opus"),
        )
        val filenameStart = 4 + 8
        body[filenameStart] = 0xC3.toByte()
        body[filenameStart + 1] = 0x28

        assertTrue(FileListDecoder.decode(body) is FileListDecodeResult.Malformed)
    }

    private data class Entry(
        val time: Long,
        val size: Long,
        val name: String,
    )

    private fun buildBody(
        filenameFieldLength: Int,
        vararg entries: Entry,
    ): ByteArray {
        val entryLength = 8 + filenameFieldLength
        val body = ByteArray(4 + entryLength * entries.size)
        putU32Be(body, 0, entries.size.toLong())
        entries.forEachIndexed { index, entry ->
            val offset = 4 + index * entryLength
            putU32Be(body, offset, entry.time)
            putU32Be(body, offset + 4, entry.size)
            val encoded = entry.name.encodeToByteArray()
            require(encoded.size <= filenameFieldLength)
            encoded.copyInto(body, destinationOffset = offset + 8)
        }
        return body
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
