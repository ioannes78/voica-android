package io.github.ioannes78.voica.ui.theme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeSettingsStoreTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application
            .getSharedPreferences("voica-theme", Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsToSystemAndWenzhiInkWhenNoExplicitPaletteExists() {
        val store = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaThemeMode.SYSTEM, store.settings.value.mode)
        assertEquals(VoicaColorPreset.WENZHI_INK, store.settings.value.preset)
        assertEquals(WenzhiPaperTone.XUAN_WARM, store.settings.value.paperTone)
    }

    @Test
    fun preservesExplicitLegacyModeAndPreset() {
        application
            .getSharedPreferences("voica-theme", Application.MODE_PRIVATE)
            .edit()
            .putString("mode", VoicaThemeMode.DARK.name)
            .putString("preset", VoicaColorPreset.PURPLE.name)
            .commit()

        val restored = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaThemeMode.DARK, restored.settings.value.mode)
        assertEquals(VoicaColorPreset.PURPLE, restored.settings.value.preset)
    }

    @Test
    fun persistsNewModeAndPresetSelections() {
        val first = SharedPreferencesThemeSettingsStore(application)
        first.setMode(VoicaThemeMode.LIGHT)
        first.setPreset(VoicaColorPreset.MINT)

        val restored = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaThemeMode.LIGHT, restored.settings.value.mode)
        assertEquals(VoicaColorPreset.MINT, restored.settings.value.preset)
    }

    @Test
    fun paperToneSelectsWenzhiPaperAndPersists() {
        val first = SharedPreferencesThemeSettingsStore(application)

        first.setPaperTone(WenzhiPaperTone.ANTIQUE)

        assertEquals(VoicaColorPreset.WENZHI_PAPER, first.settings.value.preset)
        assertEquals(WenzhiPaperTone.ANTIQUE, first.settings.value.paperTone)

        val restored = SharedPreferencesThemeSettingsStore(application)
        assertEquals(VoicaColorPreset.WENZHI_PAPER, restored.settings.value.preset)
        assertEquals(WenzhiPaperTone.ANTIQUE, restored.settings.value.paperTone)
    }

    @Test
    fun invalidOrUnknownStoredPresetFallsBackToWenzhiInk() {
        application
            .getSharedPreferences("voica-theme", Application.MODE_PRIVATE)
            .edit()
            .putString("preset", "REMOVED_LEGACY_THEME")
            .commit()

        val restored = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaColorPreset.WENZHI_INK, restored.settings.value.preset)
    }

    @Test
    fun invalidPaperToneFallsBackToWarmXuan() {
        application
            .getSharedPreferences("voica-theme", Application.MODE_PRIVATE)
            .edit()
            .putString("paper-tone", "REMOVED_PAPER_TONE")
            .commit()

        val restored = SharedPreferencesThemeSettingsStore(application)

        assertEquals(WenzhiPaperTone.XUAN_WARM, restored.settings.value.paperTone)
    }

    @Test
    fun customAccentSelectsCustomPresetAndPersists() {
        val accent = 0xFF57D4B2.toInt()
        val first = SharedPreferencesThemeSettingsStore(application)

        first.setCustomAccent(accent)

        assertEquals(VoicaColorPreset.CUSTOM, first.settings.value.preset)
        assertEquals(accent, first.settings.value.customAccentArgb)

        val restored = SharedPreferencesThemeSettingsStore(application)
        assertEquals(VoicaColorPreset.CUSTOM, restored.settings.value.preset)
        assertEquals(accent, restored.settings.value.customAccentArgb)
    }
}
