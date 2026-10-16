package io.github.ioannes78.voica.ui.library

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation-only formatter for user-visible recording timestamps. */
internal object RecordingDisplayTimeFormatter {
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(
        recordedAtLocalIso: String?,
        createdAtMs: Long = 0L,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val raw = recordedAtLocalIso?.trim()?.takeIf { it.isNotEmpty() }
        if (raw != null) {
            runCatching {
                OffsetDateTime.parse(raw)
                    .atZoneSameInstant(zoneId)
                    .format(displayFormatter)
            }.getOrNull()?.let { return it }

            runCatching {
                Instant.parse(raw)
                    .atZone(zoneId)
                    .format(displayFormatter)
            }.getOrNull()?.let { return it }

            runCatching {
                LocalDateTime.parse(raw).format(displayFormatter)
            }.getOrNull()?.let { return it }
        }

        return createdAtMs
            .takeIf { it > 0L }
            ?.let { epochMs ->
                Instant.ofEpochMilli(epochMs)
                    .atZone(zoneId)
                    .format(displayFormatter)
            }
            ?: "--"
    }
}
