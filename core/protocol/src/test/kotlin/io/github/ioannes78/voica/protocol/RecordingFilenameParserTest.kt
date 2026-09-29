package io.github.ioannes78.voica.protocol

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingFilenameParserTest {
    @Test
    fun parsesValidOpusAndWavNames() {
        val expected = LocalDateTime.of(2026, 9, 29, 12, 34, 56)
        assertEquals(expected, RecordingFilenameParser.parse("note20260929-123456.opus"))
        assertEquals(expected, RecordingFilenameParser.parse("note20260929-123456.wav"))
    }

    @Test
    fun acceptsValidLeapDay() {
        assertEquals(
            LocalDateTime.of(2028, 2, 29, 1, 2, 3),
            RecordingFilenameParser.parse("note20280229-010203.opus"),
        )
    }

    @Test
    fun rejectsInvalidDateTimeAndUnknownFormats() {
        assertNull(RecordingFilenameParser.parse("note20260230-120000.opus"))
        assertNull(RecordingFilenameParser.parse("note20260929-250000.opus"))
        assertNull(RecordingFilenameParser.parse("20260929-120000.opus"))
        assertNull(RecordingFilenameParser.parse("note20260929-120000."))
        assertNull(RecordingFilenameParser.parse("note20260929-120000.mp3"))
    }
}
