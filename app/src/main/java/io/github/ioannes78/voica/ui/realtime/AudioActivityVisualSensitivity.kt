package io.github.ioannes78.voica.ui.realtime

import kotlin.math.log10

/**
 * Presentation-only mapping from a normalized PCM activity magnitude to waveform height.
 *
 * The input remains the existing 0..1 activity truth produced by the recording/realtime
 * meters. This policy deliberately does not touch PCM, WAV, ASR, VAD, diarization, or any
 * recording lifecycle state.
 */
internal object AudioActivityVisualSensitivity {
    const val NOISE_FLOOR_DBFS: Float = -55f
    const val VISUAL_CEILING_DBFS: Float = -8f

    fun toVisualLevel(rawLevel: Float): Float {
        if (!rawLevel.isFinite() || rawLevel <= 0f) return 0f

        val clampedMagnitude = rawLevel.coerceIn(MIN_NORMALIZED_MAGNITUDE, 1f)
        val dbfs = 20f * log10(clampedMagnitude)
        return ((dbfs - NOISE_FLOOR_DBFS) / VISUAL_RANGE_DB)
            .coerceIn(0f, 1f)
    }

    private const val MIN_NORMALIZED_MAGNITUDE = 1e-6f
    private const val VISUAL_RANGE_DB = VISUAL_CEILING_DBFS - NOISE_FLOOR_DBFS
}
