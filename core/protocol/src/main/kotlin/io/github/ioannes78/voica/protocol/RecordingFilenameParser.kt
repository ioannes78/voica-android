package io.github.ioannes78.voica.protocol

import java.time.DateTimeException
import java.time.LocalDateTime

object RecordingFilenameParser {
    private val pattern =
        Regex("^note(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})\\.(?:opus|wav)$")

    fun parse(filename: String): LocalDateTime? {
        val match = pattern.matchEntire(filename) ?: return null
        val groups = match.groupValues
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].toInt()
        val minute = groups[5].toInt()
        val second = groups[6].toInt()

        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: DateTimeException) {
            null
        }
    }
}
