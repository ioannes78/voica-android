package io.github.ioannes78.voica

/**
 * Product AudioSource preference calibrated by the Stage 16A.1.6 A1 real-device gate.
 *
 * AudioSource is deliberately kept separate from physical input-device routing. MIC-first does not
 * mean "built-in microphone only": wired, USB and supported Bluetooth inputs continue to be chosen
 * by PhoneMicrophoneInputRequest / AudioDeviceInfo and verified against the actual routed device.
 */
internal object PhoneMicrophoneAudioSourcePolicy {
    private val calibratedPreference =
        listOf(
            PhoneMicrophoneAudioSource.MIC,
            PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
        )

    fun resolveAvailable(
        availableSources: List<PhoneMicrophoneAudioSource>,
    ): List<PhoneMicrophoneAudioSource> {
        require(availableSources.isNotEmpty())
        val available = availableSources.distinct()
        return calibratedPreference.filter { it in available } +
            available.filter { it !in calibratedPreference }
    }
}
