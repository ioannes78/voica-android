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
    fun defaultsToSystemAndMint() {
        val store = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaThemeMode.SYSTEM, store.settings.value.mode)
        assertEquals(VoicaColorPreset.MINT, store.settings.value.preset)
    }

    @Test
    fun persistsModeAndPreset() {
        val first = SharedPreferencesThemeSettingsStore(application)
        first.setMode(VoicaThemeMode.DARK)
        first.setPreset(VoicaColorPreset.PURPLE)

        val restored = SharedPreferencesThemeSettingsStore(application)

        assertEquals(VoicaThemeMode.DARK, restored.settings.value.mode)
        assertEquals(VoicaColorPreset.PURPLE, restored.settings.value.preset)
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
