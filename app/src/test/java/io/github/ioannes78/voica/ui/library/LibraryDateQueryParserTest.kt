package io.github.ioannes78.voica.ui.library

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryDateQueryParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun parsesSupportedExactDatesIntoOneLocalDay() {
        val expected = LibraryDateQueryParser.parse("2026-10-03", zone)
        requireNotNull(expected)
        assertEquals("2026-10-03T00:00", expected.fromLocalIso)
        assertEquals("2026-10-04T00:00", expected.toLocalIsoExclusive)
        assertEquals(expected, LibraryDateQueryParser.parse("2026/10/03", zone))
        assertEquals(expected, LibraryDateQueryParser.parse("20261003", zone))
        assertEquals(expected, LibraryDateQueryParser.parse("2026年10月3日", zone))
    }

    @Test
    fun rejectsInvalidOrMixedSearchText() {
        assertNull(LibraryDateQueryParser.parse("2026-02-30", zone))
        assertNull(LibraryDateQueryParser.parse("2026-10-03 会议", zone))
        assertNull(LibraryDateQueryParser.parse("10-03", zone))
    }
}
