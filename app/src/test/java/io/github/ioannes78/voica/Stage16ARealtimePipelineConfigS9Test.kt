package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage16ARealtimePipelineConfigS9Test {
    @Test
    fun `default realtime jitter buffer absorbs two seconds of canonical pcm`() {
        val config = Stage16ARealtimePipelineConfig()
        assertEquals(100, config.maxBufferedPcmFrames)
        assertEquals(32_000, config.maxBufferedPcmSamples)
        assertEquals(2_000, config.maxBufferedPcmSamples * 1_000 / 16_000)
    }
}
