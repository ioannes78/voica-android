package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage159FinalGateContractTest {
    @Test
    fun finalGateCounterDeltaIsBaselineRelative() {
        val baseline =
            Stage159CounterSnapshot(
                acceptedBlocks = 100,
                decodeSuccess = 400,
                canonicalSamples = 128_000,
                ingressOverflow = 2,
                sessions = 3,
                fileCount = 20,
            )
        val current =
            baseline.copy(
                acceptedBlocks = 145,
                decodeSuccess = 580,
                canonicalSamples = 185_600,
                ingressOverflow = 2,
                sessions = 4,
                fileCount = 21,
            )

        val delta = current.deltaFrom(baseline)

        assertEquals(45L, delta.acceptedBlocks)
        assertEquals(180L, delta.decodeSuccess)
        assertEquals(57_600L, delta.canonicalSamples)
        assertEquals(0L, delta.ingressOverflow)
        assertEquals(1L, delta.sessions)
        assertEquals(1L, delta.fileCount)
        assertEquals(0L, delta.anomalyCount())
    }

    @Test
    fun finalGateAnomalySummaryCountsOnlyFaultSignals() {
        val delta =
            Stage159CounterSnapshot(
                acceptedBlocks = 100,
                decodeSuccess = 400,
                ingressOverflow = 1,
                canonicalGaps = 2,
                appReconciliationFailures = 1,
            )

        assertEquals(4L, delta.anomalyCount())
    }

    @Test
    fun releaseQaCanSeeFinalGateButProductionReleaseCannot() {
        assertTrue(stage159QaEntryVisible(debuggable = true, applicationId = "io.github.ioannes78.voica"))
        assertTrue(stage159QaEntryVisible(debuggable = false, applicationId = "io.github.ioannes78.voica.qa"))
        assertFalse(stage159QaEntryVisible(debuggable = false, applicationId = "io.github.ioannes78.voica"))
    }

    @Test
    fun manifestKeepsExactlyOneLauncherAndItBelongsToMainActivity() {
        val manifest = appManifest()
        val launcherCount = Regex("android.intent.category.LAUNCHER").findAll(manifest).count()

        assertEquals("QA activities must never create extra launcher icons", 1, launcherCount)
        val mainActivityIndex = manifest.indexOf("android:name=\".MainActivity\"")
        val launcherIndex = manifest.indexOf("android.intent.category.LAUNCHER")
        assertTrue(mainActivityIndex >= 0)
        assertTrue(launcherIndex > mainActivityIndex)
        assertTrue(manifest.contains("android:name=\".Stage159FinalGateActivity\""))
        assertFalse(
            manifest.substringBefore("android:name=\".MainActivity\"").contains(
                "android.intent.category.LAUNCHER",
            ),
        )
    }

    private fun appManifest(): String {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app AndroidManifest.xml not found")
    }
}
