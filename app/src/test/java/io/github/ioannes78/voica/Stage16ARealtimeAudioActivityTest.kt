package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeAudioActivityTest {
    @Test
    fun `silence produces zero activity`() {
        val level = Stage16ARealtimePcmActivityMeter.normalizedMeanAbsolute(ShortArray(320))
        assertEquals(0f, level, 0f)
    }

    @Test
    fun `half scale pcm produces normalized half activity`() {
        val samples = ShortArray(320) { 16_384.toShort() }
        val level = Stage16ARealtimePcmActivityMeter.normalizedMeanAbsolute(samples)
        assertEquals(0.5f, level, 0.0001f)
    }

    @Test
    fun `pcm extremes remain finite and clamped`() {
        val samples = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE)
        val level = Stage16ARealtimePcmActivityMeter.normalizedMeanAbsolute(samples)
        assertTrue(level.isFinite())
        assertTrue(level in 0f..1f)
    }

    @Test
    fun `empty defensive input produces zero instead of nan`() {
        val level = Stage16ARealtimePcmActivityMeter.normalizedMeanAbsolute(shortArrayOf())
        assertEquals(0f, level, 0f)
    }
}
