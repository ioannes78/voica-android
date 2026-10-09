package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class Stage14V1BaselineContractTest {
    @Test
    fun v1StartupDoesNotRunPreV1Stage5Import() {
        val source = productionSource("VoicaApplication.kt")

        assertFalse(
            "V1.0 is the first supported install baseline; startup must not scan/import Stage 5 metadata",
            source.contains("importLegacyStage5IfNeeded()"),
        )
    }

    private fun productionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("production source not found: $relative")
    }
}
