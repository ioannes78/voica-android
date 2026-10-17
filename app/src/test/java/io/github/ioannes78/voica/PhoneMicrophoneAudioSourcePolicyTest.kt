package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMicrophoneAudioSourcePolicyTest {
    @Test
    fun `calibrated policy prefers mic and keeps voice recognition fallback`() {
        assertEquals(
            listOf(
                PhoneMicrophoneAudioSource.MIC,
                PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
            ),
            PhoneMicrophoneAudioSourcePolicy.resolveAvailable(
                listOf(
                    PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
                    PhoneMicrophoneAudioSource.MIC,
                ),
            ),
        )
    }

    @Test
    fun `calibrated policy keeps the only available source`() {
        assertEquals(
            listOf(PhoneMicrophoneAudioSource.VOICE_RECOGNITION),
            PhoneMicrophoneAudioSourcePolicy.resolveAvailable(
                listOf(PhoneMicrophoneAudioSource.VOICE_RECOGNITION),
            ),
        )
        assertEquals(
            listOf(PhoneMicrophoneAudioSource.MIC),
            PhoneMicrophoneAudioSourcePolicy.resolveAvailable(
                listOf(PhoneMicrophoneAudioSource.MIC),
            ),
        )
    }
}
