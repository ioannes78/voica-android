package io.github.ioannes78.voica.protocol

import java.time.DateTimeException
import java.time.LocalDateTime

object RecordingFilenameParser {
    private val pattern =
        Regex("^note(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})\\.(?:opus|wav)$")

    fun parse(filename: String): LocalDateTime? {
        val match = pattern.matchEntire(filename) ?: return null
        val (year, month, day, hour, minute, second) =
            match.destructured.toList().map(String::toInt)

        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: DateTimeException) {
            null
        }
    }
}
