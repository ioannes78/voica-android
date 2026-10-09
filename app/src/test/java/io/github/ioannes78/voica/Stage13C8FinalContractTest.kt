package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.DiarizationConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage13C8FinalContractTest {
    @Test
    fun finalRuntimeHasNoDiarizationProfilingConstructionOrExportSurface() {
        val applicationSource = productionSource("VoicaApplication.kt")
        val activitySource = productionSource("MainActivity.kt")
        val compatibilitySource = productionSource("DiarizationBenchmark.kt")

        assertFalse(applicationSource.contains("DiarizationBenchmarkRunner("))
        assertFalse(applicationSource.contains("Stage13CBoundaryProfilingProvider("))
        assertFalse(applicationSource.contains("profilingEngineProvider()"))
        assertTrue(activitySource.contains("diarizationBenchmarkRunner = null"))
        assertTrue(compatibilitySource.contains("typealias DiarizationBenchmarkRunner = Nothing"))
        assertFalse(compatibilitySource.contains("DiarizationProfiler"))
        assertFalse(compatibilitySource.contains("JSONObject"))
        assertFalse(productionFileExists("DiarizationBenchmarkExportController.kt"))
        assertFalse(productionFileExists("Stage13CBoundaryProfiler.kt"))
    }

    @Test
    fun finalDiarizationProductDefaultsRemainFrozen() {
        val config = DiarizationConfig()

        assertEquals(60L * 16_000L, config.chunkSizeSamples)
        assertEquals(10L * 16_000L, config.chunkOverlapSamples)
        assertEquals(SpeakerEmbeddingModelChoice.CAMP_PLUS, LocalSpeechSettings().speakerEmbeddingModel)
        assertEquals(30F, LocalSpeechSettings().vad.maxSpeechDurationSeconds)
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

    private fun productionFileExists(relative: String): Boolean =
        listOf(
            File("src/main/java/io/github/ioannes78/voica/$relative"),
            File("app/src/main/java/io/github/ioannes78/voica/$relative"),
        ).any(File::isFile)
}
