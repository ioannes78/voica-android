package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAnnotationFineAdjustTest {
    @Test
    fun fineWindowGrowsWithLongRecordingButRemainsLocal() {
        val sr = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong()
        assertEquals(5L * sr, fineAnnotationHalfSpanSamples(30L * sr))
        assertEquals(15L * sr, fineAnnotationHalfSpanSamples(5L * 60L * sr))
        assertEquals(30L * sr, fineAnnotationHalfSpanSamples(30L * 60L * sr))
    }

    @Test
    fun horizontalDragMovesWithinFineWindowInsteadOfWholeRecordingWidth() {
        val adjusted =
            fineAnnotationAdjustedRatio(
                currentRatio = 0.5f,
                centerRatio = 0.5f,
                normalizedHorizontalDelta = 0.25f,
                halfSpanRatio = 0.10f,
            )
        assertEquals(0.55f, adjusted, 0.0001f)
        assertTrue(adjusted < 0.75f)
    }

    @Test
    fun fineAdjustmentClampsToLocalWindowNearRecordingEdges() {
        val adjusted =
            fineAnnotationAdjustedRatio(
                currentRatio = 0.03f,
                centerRatio = 0.03f,
                normalizedHorizontalDelta = -1.0f,
                halfSpanRatio = 0.10f,
            )
        assertEquals(0.0f, adjusted, 0.0001f)
    }
}
