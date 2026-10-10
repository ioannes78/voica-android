package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage14V1BaselineContractTest {
    @Test
    fun v1StartupDoesNotRunPreV1DataRepairs() {
        val source = appProductionSource("VoicaApplication.kt")

        assertFalse(
            "V1.0 is the first supported install baseline; startup must not scan/import Stage 5 metadata",
            source.contains("importLegacyStage5IfNeeded()"),
        )
        assertFalse(
            "V1.0 clean baseline must not scan existing recordings for pre-V1 display-name repair",
            source.contains("normalizeStandardDeviceDisplayNames()"),
        )
    }

    @Test
    fun v1RoomRuntimeStartsDirectlyFromSchema12() {
        val source = databaseProductionSource("VoicaDatabase.kt")

        assertTrue(source.contains("version = 12"))
        assertFalse(
            "pre-V1 Room migrations must not be registered in the V1 production database builder",
            source.contains(".addMigrations("),
        )
    }

    private fun appProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app production source not found: $relative")
    }

    private fun databaseProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("../core/database/src/main/java/io/github/ioannes78/voica/database/$relative"),
                File("core/database/src/main/java/io/github/ioannes78/voica/database/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("database production source not found: $relative")
    }
}
