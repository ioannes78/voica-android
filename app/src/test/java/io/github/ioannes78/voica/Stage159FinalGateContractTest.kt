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
    fun crossGenerationAccountingAddsCompletedAndNewRuntimeCounters() {
        val accumulator = Stage159GenerationAccumulator()
        accumulator.reset(
            generation = 1,
            counters = Stage159CounterSnapshot(),
        )

        accumulator.observe(
            generation = 1,
            counters =
                Stage159CounterSnapshot(
                    acceptedBlocks = 100,
                    acceptedBytes = 16_000,
                    opusPackets = 400,
                    decodeSuccess = 400,
                    canonicalFrames = 400,
                    canonicalSamples = 128_000,
                    sessions = 1,
                    epochs = 1,
                    startSuccess = 1,
                    stopSuccess = 1,
                ),
        )
        val afterReconnect =
            accumulator.observe(
                generation = 2,
                counters =
                    Stage159CounterSnapshot(
                        acceptedBlocks = 40,
                        acceptedBytes = 6_400,
                        opusPackets = 160,
                        decodeSuccess = 160,
                        canonicalFrames = 160,
                        canonicalSamples = 51_200,
                        sessions = 1,
                        epochs = 1,
                        startSuccess = 1,
                        stopSuccess = 1,
                    ),
            )

        assertEquals(1L, afterReconnect.baselineGeneration)
        assertEquals(2L, afterReconnect.currentGeneration)
        assertEquals(1, afterReconnect.generationTransitions)
        assertEquals(140L, afterReconnect.transportDelta.acceptedBlocks)
        assertEquals(560L, afterReconnect.transportDelta.opusPackets)
        assertEquals(179_200L, afterReconnect.transportDelta.canonicalSamples)
        assertEquals(2L, afterReconnect.transportDelta.sessions)
        assertEquals(2L, afterReconnect.transportDelta.startSuccess)
        assertEquals(2L, afterReconnect.transportDelta.stopSuccess)
        assertTrue(afterReconnect.transportDelta.acceptedBlocks >= 0)
        assertTrue(afterReconnect.transportDelta.canonicalSamples >= 0)
    }

    @Test
    fun crossGenerationTransportDeltaDoesNotOverwritePersistentCounters() {
        val direct =
            Stage159CounterSnapshot(
                acceptedBlocks = -60,
                canonicalSamples = -76_800,
                sourceSessionStarted = 2,
                sourceSessionEnded = 2,
                canonicalGaps = 0,
                appReconciliationFailures = 0,
                fileCount = 1,
            )
        val transport =
            Stage159CounterSnapshot(
                acceptedBlocks = 140,
                canonicalSamples = 179_200,
                sessions = 2,
            )

        val merged = mergeStage159TransportDelta(direct, transport)

        assertEquals(140L, merged.acceptedBlocks)
        assertEquals(179_200L, merged.canonicalSamples)
        assertEquals(2L, merged.sourceSessionStarted)
        assertEquals(2L, merged.sourceSessionEnded)
        assertEquals(1L, merged.fileCount)
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
