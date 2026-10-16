package io.github.ioannes78.voica

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Stage16A16QaAudioSourceMode(
    val label: String,
) {
    AUTO("自动（MIC → VOICE_RECOGNITION）"),
    FORCE_VOICE_RECOGNITION("强制 VOICE_RECOGNITION"),
    FORCE_MIC("强制 MIC"),
}

internal data class Stage16A16QaAudioSourceOverrideSnapshot(
    val enabled: Boolean,
    val mode: Stage16A16QaAudioSourceMode,
) {
    fun toDiagnosticsText(): String =
        buildString {
            appendLine("[Stage 16A.1.6 QA audio-source calibration]")
            append("enabled=$enabled mode=${mode.name}")
        }
}

/**
 * QA-only calibration control. Production builds ignore mutation and always use the calibrated
 * product AudioSource policy.
 *
 * This does not own AudioRecord and does not create a second capture path. PhoneMicrophoneCapture
 * only reads the mode when a normal product capture starts, so formal WAV remains the primary truth.
 */
internal object Stage16A16QaAudioSourceOverride {
    private val qaEnabled = BuildConfig.APPLICATION_ID.endsWith(".qa")
    private val mutableState =
        MutableStateFlow(
            Stage16A16QaAudioSourceOverrideSnapshot(
                enabled = qaEnabled,
                mode = Stage16A16QaAudioSourceMode.AUTO,
            ),
        )

    val state: StateFlow<Stage16A16QaAudioSourceOverrideSnapshot> = mutableState.asStateFlow()

    fun snapshot(): Stage16A16QaAudioSourceOverrideSnapshot = mutableState.value

    fun setMode(mode: Stage16A16QaAudioSourceMode) {
        if (!qaEnabled) return
        mutableState.value = mutableState.value.copy(mode = mode)
    }

    fun sourceOrder(
        availableSources: List<PhoneMicrophoneAudioSource>,
    ): List<PhoneMicrophoneAudioSource> =
        resolveStage16A16QaAudioSourceOrder(
            enabled = qaEnabled,
            mode = mutableState.value.mode,
            availableSources = availableSources,
        )

    internal fun resetForTest() {
        mutableState.value =
            Stage16A16QaAudioSourceOverrideSnapshot(
                enabled = qaEnabled,
                mode = Stage16A16QaAudioSourceMode.AUTO,
            )
    }
}

internal fun resolveStage16A16QaAudioSourceOrder(
    enabled: Boolean,
    mode: Stage16A16QaAudioSourceMode,
    availableSources: List<PhoneMicrophoneAudioSource>,
): List<PhoneMicrophoneAudioSource> {
    val productOrder = PhoneMicrophoneAudioSourcePolicy.resolveAvailable(availableSources)
    if (!enabled || mode == Stage16A16QaAudioSourceMode.AUTO) return productOrder
    return when (mode) {
        Stage16A16QaAudioSourceMode.AUTO -> productOrder
        Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION ->
            listOf(PhoneMicrophoneAudioSource.VOICE_RECOGNITION)
        Stage16A16QaAudioSourceMode.FORCE_MIC -> listOf(PhoneMicrophoneAudioSource.MIC)
    }
}
