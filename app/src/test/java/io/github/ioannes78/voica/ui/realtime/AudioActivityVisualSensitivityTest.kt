package io.github.ioannes78.voica.ui.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioActivityVisualSensitivityTest {
    @Test
    fun `silence stays at baseline`() {
        assertEquals(0f, AudioActivityVisualSensitivity.toVisualLevel(0f), 0.0001f)
        assertEquals(0f, AudioActivityVisualSensitivity.toVisualLevel(Float.NaN), 0.0001f)
    }

    @Test
    fun `noise floor maps close to zero`() {
        val rawAtFloor = dbfsToMagnitude(AudioActivityVisualSensitivity.NOISE_FLOOR_DBFS)
        assertEquals(0f, AudioActivityVisualSensitivity.toVisualLevel(rawAtFloor), 0.01f)
    }

    @Test
    fun `normal speech range is visibly lifted`() {
        val quietSpeech = AudioActivityVisualSensitivity.toVisualLevel(dbfsToMagnitude(-36f))
        val normalSpeech = AudioActivityVisualSensitivity.toVisualLevel(dbfsToMagnitude(-24f))
        val loudSpeech = AudioActivityVisualSensitivity.toVisualLevel(dbfsToMagnitude(-14f))

        assertTrue(quietSpeech > 0.25f)
        assertTrue(normalSpeech > quietSpeech)
        assertTrue(loudSpeech > normalSpeech)
    }

    @Test
    fun `ceiling and clipping clamp to one`() {
        val rawAtCeiling = dbfsToMagnitude(AudioActivityVisualSensitivity.VISUAL_CEILING_DBFS)
        assertEquals(1f, AudioActivityVisualSensitivity.toVisualLevel(rawAtCeiling), 0.01f)
        assertEquals(1f, AudioActivityVisualSensitivity.toVisualLevel(1f), 0.0001f)
    }

    private fun dbfsToMagnitude(dbfs: Float): Float =
        Math.pow(10.0, dbfs.toDouble() / 20.0).toFloat()
}
