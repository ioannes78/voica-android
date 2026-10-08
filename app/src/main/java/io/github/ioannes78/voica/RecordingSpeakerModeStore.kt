package io.github.ioannes78.voica

import android.app.Application
import org.json.JSONObject

/**
 * Durable per-recording speaker-count preference used by Stage 13C C6A.
 *
 * This is deliberately separate from [LocalSpeechSettings]: choosing a mode for one recording
 * must never mutate the global default. The actual mode used by every diarization run remains
 * snapshotted in that run's configSnapshot.
 */
class RecordingSpeakerModeStore(
    application: Application,
) {
    private val preferences =
        application.getSharedPreferences(
            PREFERENCES_NAME,
            Application.MODE_PRIVATE,
        )

    fun get(recordingId: String): SpeakerCountChoice? {
        require(recordingId.isNotBlank())
        val raw = preferences.getString(KEY_PREFIX + recordingId, null) ?: return null
        return runCatching { SpeakerCountChoice.valueOf(raw) }.getOrNull()
    }

    fun set(
        recordingId: String,
        choice: SpeakerCountChoice,
    ) {
        require(recordingId.isNotBlank())
        preferences.edit()
            .putString(KEY_PREFIX + recordingId, choice.name)
            .commit()
    }

    fun clear(recordingId: String) {
        if (recordingId.isBlank()) return
        preferences.edit().remove(KEY_PREFIX + recordingId).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-recording-speaker-mode"
        const val KEY_PREFIX = "recording."
    }
}

internal fun speakerCountChoiceFromConfigSnapshot(configSnapshot: String?): SpeakerCountChoice? {
    if (configSnapshot.isNullOrBlank()) return null
    val raw =
        runCatching {
            JSONObject(configSnapshot).optString("speakerCountPreset", "")
        }.getOrNull().orEmpty()
    if (raw.isBlank() || raw == "CUSTOM") return null
    return runCatching { SpeakerCountChoice.valueOf(raw) }.getOrNull()
}

internal fun SpeakerCountChoice.productLabel(): String =
    when (this) {
        SpeakerCountChoice.AUTO -> "自动"
        SpeakerCountChoice.ONE -> "1人"
        SpeakerCountChoice.TWO -> "2人"
        SpeakerCountChoice.THREE -> "3人"
        SpeakerCountChoice.FOUR -> "4人"
        SpeakerCountChoice.FIVE_PLUS -> "5人+"
    }
