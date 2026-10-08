package io.github.ioannes78.voica

import android.app.Application
import java.util.concurrent.ConcurrentHashMap
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
        val raw = preferences.getString(KEY_PREFIX + recordingId, null)
        val choice = raw?.let { value -> runCatching { SpeakerCountChoice.valueOf(value) }.getOrNull() }
        if (choice != null) {
            cachedChoices[recordingId] = choice
        } else {
            cachedChoices.remove(recordingId)
        }
        return choice
    }

    fun set(
        recordingId: String,
        choice: SpeakerCountChoice,
    ) {
        require(recordingId.isNotBlank())
        preferences.edit()
            .putString(KEY_PREFIX + recordingId, choice.name)
            .commit()
        cachedChoices[recordingId] = choice
    }

    fun clear(recordingId: String) {
        if (recordingId.isBlank()) return
        preferences.edit().remove(KEY_PREFIX + recordingId).commit()
        cachedChoices.remove(recordingId)
    }

    companion object {
        private const val PREFERENCES_NAME = "voica-recording-speaker-mode"
        private const val KEY_PREFIX = "recording."
        private val cachedChoices = ConcurrentHashMap<String, SpeakerCountChoice>()

        /**
         * The detail screen loads durable file preferences before a retry action can be shown.
         * This process cache lets the shared ViewModel preserve that explicit file choice without
         * depending on the mutable global default.
         */
        fun cached(recordingId: String): SpeakerCountChoice? = cachedChoices[recordingId]
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
