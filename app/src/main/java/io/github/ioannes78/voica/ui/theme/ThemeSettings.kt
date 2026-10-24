package io.github.ioannes78.voica.ui.theme

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoicaThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class VoicaColorPreset {
    WENZHI_INK,
    WENZHI_PAPER,
    MINT,
    BLUE,
    PURPLE,
    CUSTOM,
}

enum class WenzhiPaperTone {
    PAPER_WHITE,
    XUAN_WARM,
    RICE_APRICOT,
    ANTIQUE,
}

data class VoicaThemeSettings(
    val mode: VoicaThemeMode = VoicaThemeMode.SYSTEM,
    val preset: VoicaColorPreset = VoicaColorPreset.WENZHI_INK,
    val paperTone: WenzhiPaperTone = WenzhiPaperTone.XUAN_WARM,
    val customAccentArgb: Int = DEFAULT_CUSTOM_ACCENT,
) {
    companion object {
        const val DEFAULT_CUSTOM_ACCENT: Int = 0xFF4FBFA5.toInt()
    }
}

interface ThemeSettingsStore {
    val settings: StateFlow<VoicaThemeSettings>

    fun setMode(mode: VoicaThemeMode)

    fun setPreset(preset: VoicaColorPreset)

    fun setPaperTone(tone: WenzhiPaperTone)

    fun setCustomAccent(argb: Int)
}

class SharedPreferencesThemeSettingsStore(
    application: Application,
) : ThemeSettingsStore {
    private val preferences =
        application.getSharedPreferences(
            PREFERENCES_NAME,
            Application.MODE_PRIVATE,
        )

    private val mutableSettings =
        MutableStateFlow(
            VoicaThemeSettings(
                mode =
                    preferences.getString(KEY_MODE, null)
                        ?.let { runCatching { VoicaThemeMode.valueOf(it) }.getOrNull() }
                        ?: VoicaThemeMode.SYSTEM,
                preset =
                    preferences.getString(KEY_PRESET, null)
                        ?.let { runCatching { VoicaColorPreset.valueOf(it) }.getOrNull() }
                        ?: VoicaColorPreset.WENZHI_INK,
                paperTone =
                    preferences.getString(KEY_PAPER_TONE, null)
                        ?.let { runCatching { WenzhiPaperTone.valueOf(it) }.getOrNull() }
                        ?: WenzhiPaperTone.XUAN_WARM,
                customAccentArgb =
                    preferences.getInt(
                        KEY_CUSTOM_ACCENT,
                        VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                    ),
            ),
        )

    override val settings: StateFlow<VoicaThemeSettings> =
        mutableSettings.asStateFlow()

    override fun setMode(mode: VoicaThemeMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        mutableSettings.value = mutableSettings.value.copy(mode = mode)
    }

    override fun setPreset(preset: VoicaColorPreset) {
        preferences.edit().putString(KEY_PRESET, preset.name).apply()
        mutableSettings.value = mutableSettings.value.copy(preset = preset)
    }

    override fun setPaperTone(tone: WenzhiPaperTone) {
        preferences.edit().putString(KEY_PAPER_TONE, tone.name).apply()
        mutableSettings.value =
            mutableSettings.value.copy(
                paperTone = tone,
                preset = VoicaColorPreset.WENZHI_PAPER,
            )
        preferences.edit()
            .putString(KEY_PRESET, VoicaColorPreset.WENZHI_PAPER.name)
            .apply()
    }

    override fun setCustomAccent(argb: Int) {
        preferences.edit().putInt(KEY_CUSTOM_ACCENT, argb).apply()
        mutableSettings.value =
            mutableSettings.value.copy(
                customAccentArgb = argb,
                preset = VoicaColorPreset.CUSTOM,
            )
        preferences.edit()
            .putString(KEY_PRESET, VoicaColorPreset.CUSTOM.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-theme"
        const val KEY_MODE = "mode"
        const val KEY_PRESET = "preset"
        const val KEY_PAPER_TONE = "paper-tone"
        const val KEY_CUSTOM_ACCENT = "custom-accent"
    }
}
