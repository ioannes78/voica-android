package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage16A16QaAudioSourceOverrideTest {
    private val defaultOrder =
        listOf(
            PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
            PhoneMicrophoneAudioSource.MIC,
        )

    @Test
    fun `production-disabled override always preserves normal source order`() {
        assertEquals(
            defaultOrder,
            resolveStage16A16QaAudioSourceOrder(
                enabled = false,
                mode = Stage16A16QaAudioSourceMode.FORCE_MIC,
                defaultOrder = defaultOrder,
            ),
        )
    }

    @Test
    fun `qa auto preserves normal source order`() {
        assertEquals(
            defaultOrder,
            resolveStage16A16QaAudioSourceOrder(
                enabled = true,
                mode = Stage16A16QaAudioSourceMode.AUTO,
                defaultOrder = defaultOrder,
            ),
        )
    }

    @Test
    fun `qa force voice recognition removes mic fallback`() {
        assertEquals(
            listOf(PhoneMicrophoneAudioSource.VOICE_RECOGNITION),
            resolveStage16A16QaAudioSourceOrder(
                enabled = true,
                mode = Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION,
                defaultOrder = defaultOrder,
            ),
        )
    }

    @Test
    fun `qa force mic removes voice recognition candidate`() {
        assertEquals(
            listOf(PhoneMicrophoneAudioSource.MIC),
            resolveStage16A16QaAudioSourceOrder(
                enabled = true,
                mode = Stage16A16QaAudioSourceMode.FORCE_MIC,
                defaultOrder = defaultOrder,
            ),
        )
    }
}
