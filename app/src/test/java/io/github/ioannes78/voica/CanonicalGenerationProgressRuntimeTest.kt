package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalAudioWorkProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalGenerationProgressRuntimeTest {
    @Test
    fun firstWorkSamplePublishesImmediatelyAndIntermediateSamplesAreThrottled() {
        var now = 1_000L
        val runtime = CanonicalGenerationProgressRuntime(nowMs = { now })
        runtime.begin(RECORDING_ID, totalDurationUs = 10_000_000L)

        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 1_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 1L,
                totalUnits = 10L,
            ),
        )

        var progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(1_000_000L, progress.processedDurationUs)
        assertEquals(0.1f, progress.fraction ?: -1f, 0.0001f)

        now += 100L
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 2_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 2L,
                totalUnits = 10L,
            ),
        )
        progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(1_000_000L, progress.processedDurationUs)

        now += 150L
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 3_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 3L,
                totalUnits = 10L,
            ),
        )
        progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(3_000_000L, progress.processedDurationUs)
    }

    @Test
    fun terminalSamplePublishesWithoutWaitingForThrottleWindow() {
        var now = 2_000L
        val runtime = CanonicalGenerationProgressRuntime(nowMs = { now })
        runtime.begin(RECORDING_ID, totalDurationUs = 10_000_000L)
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 1_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 1L,
                totalUnits = 10L,
            ),
        )

        now += 10L
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 10_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 10L,
                totalUnits = 10L,
            ),
        )

        val progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(10_000_000L, progress.processedDurationUs)
        assertEquals(1f, progress.fraction ?: -1f, 0.0001f)
    }

    @Test
    fun progressIsMonotonicAndCancellationBecomesNonCancellable() {
        var now = 3_000L
        val runtime = CanonicalGenerationProgressRuntime(nowMs = { now })
        runtime.begin(RECORDING_ID, totalDurationUs = 10_000_000L)
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 5_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 5L,
                totalUnits = 10L,
            ),
            force = true,
        )

        now += 1L
        runtime.work(
            RECORDING_ID,
            CanonicalAudioWorkProgress(
                processedDurationUs = 4_000_000L,
                totalDurationUs = 10_000_000L,
                processedUnits = 4L,
                totalUnits = 10L,
            ),
            force = true,
        )
        var progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(5_000_000L, progress.processedDurationUs)
        assertEquals(5L, progress.processedUnits)

        runtime.cancelling(RECORDING_ID)
        progress = runtime.progress.value.getValue(RECORDING_ID)
        assertEquals(CanonicalGenerationPhase.CANCELLING, progress.phase)
        assertFalse(progress.cancellable)

        runtime.finish(RECORDING_ID)
        assertNull(runtime.progress.value[RECORDING_ID])
        assertTrue(runtime.progress.value.isEmpty())
    }

    private companion object {
        const val RECORDING_ID = "recording-progress-test"
    }
}
