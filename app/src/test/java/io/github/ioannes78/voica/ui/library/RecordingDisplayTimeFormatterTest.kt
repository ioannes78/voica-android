package io.github.ioannes78.voica.ui.library

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingDisplayTimeFormatterTest {
    @Test
    fun `local wall clock stays local and loses fractions`() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(
            "2026-10-16 15:09:57",
            RecordingDisplayTimeFormatter.format("2026-10-16T15:09:57", zoneId = zone),
        )
        assertEquals(
            "2026-10-16 15:09:57",
            RecordingDisplayTimeFormatter.format("2026-10-16T15:09:57.987654", zoneId = zone),
        )
    }

    @Test
    fun `offset and UTC timestamps convert to requested local zone`() {
        val utc = ZoneId.of("UTC")
        assertEquals(
            "2026-10-16 07:12:14",
            RecordingDisplayTimeFormatter.format(
                "2026-10-16T15:12:14.132178+08:00",
                zoneId = utc,
            ),
        )
        assertEquals(
            "2026-10-16 15:12:14",
            RecordingDisplayTimeFormatter.format("2026-10-16T15:12:14Z", zoneId = utc),
        )
    }

    @Test
    fun `epoch fallback uses requested zone and invalid input never leaks raw text`() {
        val instant = Instant.parse("2026-10-16T15:12:14Z")
        assertEquals(
            "2026-10-16 15:12:14",
            RecordingDisplayTimeFormatter.format(
                recordedAtLocalIso = "not-a-time",
                createdAtMs = instant.toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ),
        )
        assertEquals(
            "--",
            RecordingDisplayTimeFormatter.format("not-a-time", createdAtMs = 0L, zoneId = ZoneId.of("UTC")),
        )
    }
}
