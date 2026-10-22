package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage14V1BaselineContractTest {
    @Test
    fun preReleaseStartupDoesNotRunHistoricalDataRepairs() {
        val appSource = appProductionSource("VoicaApplication.kt")
        val databaseSource = databaseProductionSource("VoicaDatabase.kt")

        assertFalse(
            "pre-release startup must not scan/import Stage 5 metadata",
            appSource.contains("importLegacyStage5IfNeeded()"),
        )
        assertFalse(
            "pre-release startup must not scan existing recordings for old display-name repair",
            appSource.contains("normalizeStandardDeviceDisplayNames()"),
        )
        assertFalse(
            "pre-release Room open must not run old phone canonical lineage repair",
            databaseSource.contains("repairPhoneCanonicalIdentityLineage"),
        )
    }

    @Test
    fun preReleaseRoomRuntimeUsesCurrentSchemaWithoutHistoricalMigrationChain() {
        val source = databaseProductionSource("VoicaDatabase.kt")

        assertTrue(source.contains("version = 14"))
        assertFalse(
            "pre-release personal-use baseline must not register historical migrations at runtime",
            source.contains(".addMigrations("),
        )
        for (legacyMigration in listOf(
            "MIGRATION_1_2",
            "MIGRATION_2_3",
            "MIGRATION_3_4",
            "MIGRATION_4_5",
            "MIGRATION_5_6",
            "MIGRATION_6_7",
            "MIGRATION_7_8",
            "MIGRATION_8_9",
            "MIGRATION_9_10",
            "MIGRATION_10_11",
            "MIGRATION_11_12",
            "MIGRATION_12_13",
            "MIGRATION_13_14",
        )) {
            assertFalse(
                "historical migration must not be registered at runtime: $legacyMigration",
                source.contains(legacyMigration),
            )
        }
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
