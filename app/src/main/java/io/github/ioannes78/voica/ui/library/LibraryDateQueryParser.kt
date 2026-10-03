package io.github.ioannes78.voica.ui.library

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal data class ParsedLibraryDateQuery(
    val fromLocalIso: String,
    val toLocalIsoExclusive: String,
    val fromEpochMs: Long,
    val toEpochMsExclusive: Long,
)

internal object LibraryDateQueryParser {
    private val formatters =
        listOf(
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu.MM.dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu年M月d日").withResolverStyle(ResolverStyle.STRICT),
        )

    fun parse(
        query: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ParsedLibraryDateQuery? {
        val value = query.trim()
        if (value.isEmpty()) return null
        val date =
            formatters.firstNotNullOfOrNull { formatter ->
                runCatching { LocalDate.parse(value, formatter) }.getOrNull()
            } ?: return null

        val startLocal = date.atStartOfDay()
        val endLocal = date.plusDays(1).atStartOfDay()
        return ParsedLibraryDateQuery(
            fromLocalIso = startLocal.toString(),
            toLocalIsoExclusive = endLocal.toString(),
            fromEpochMs = startLocal.atZone(zoneId).toInstant().toEpochMilli(),
            toEpochMsExclusive = endLocal.atZone(zoneId).toInstant().toEpochMilli(),
        )
    }
}
