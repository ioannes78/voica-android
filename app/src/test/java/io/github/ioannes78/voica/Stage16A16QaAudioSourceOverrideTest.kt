package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage16A16QaAudioSourceOverrideTest {
    private val availableSources =
        listOf(
            PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
            PhoneMicrophoneAudioSource.MIC,
        )

    private val productOrder =
        listOf(
            PhoneMicrophoneAudioSource.MIC,
            PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
        )

    @Test
    fun `production-disabled override always uses calibrated product source order`() {
        assertEquals(
            productOrder,
            resolveStage16A16QaAudioSourceOrder(
                enabled = false,
                mode = Stage16A16QaAudioSourceMode.FORCE_MIC,
                availableSources = availableSources,
            ),
        )
    }

    @Test
    fun `qa auto uses calibrated product source order`() {
        assertEquals(
            productOrder,
            resolveStage16A16QaAudioSourceOrder(
                enabled = true,
                mode = Stage16A16QaAudioSourceMode.AUTO,
                availableSources = availableSources,
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
                availableSources = availableSources,
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
                availableSources = availableSources,
            ),
        )
    }
}
