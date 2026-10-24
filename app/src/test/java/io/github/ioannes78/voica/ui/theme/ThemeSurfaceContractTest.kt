package io.github.ioannes78.voica.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ThemeSurfaceContractTest {
    @Test
    fun `preset themes own differentiated Material 3 surface ladders in light and dark`() {
        val presets =
            listOf(
                VoicaColorPreset.WENZHI_INK,
                VoicaColorPreset.WENZHI_PAPER,
                VoicaColorPreset.MINT,
                VoicaColorPreset.BLUE,
                VoicaColorPreset.PURPLE,
            )

        presets.forEach { preset ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveVoicaColorScheme(preset, darkTheme)
                val surfaceRoles = surfaceRoles(scheme)

                assertTrue(
                    surfaceRoles.distinct().size >= 6,
                    "$preset dark=$darkTheme must provide a differentiated Material 3 surface ladder",
                )
                assertTrue(
                    contrastRatio(scheme.onSurface, scheme.surface) >= 4.5,
                    "$preset dark=$darkTheme onSurface contrast is too low",
                )
                assertTrue(
                    contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 3.0,
                    "$preset dark=$darkTheme onSurfaceVariant contrast is too low",
                )
            }
        }
    }

    @Test
    fun `WENZHI Ink light surfaces are warm paper neutrals without cold purple fallback`() {
        val scheme = resolveVoicaColorScheme(VoicaColorPreset.WENZHI_INK, darkTheme = false)

        assertEquals(Color(0xFFF5F2EA), scheme.background)
        assertEquals(Color(0xFFFBFAF6), scheme.surface)
        assertEquals(Color(0xFFF2EFE7), scheme.surfaceContainer)
        assertEquals(Color(0xFFE6E1D7), scheme.surfaceContainerHighest)

        surfaceRoles(scheme).forEach { color ->
            assertTrue(
                color.red + 0.002f >= color.blue,
                "WENZHI Ink light surface must remain warm-neutral: $color",
            )
        }
    }

    @Test
    fun `WENZHI Ink dark surfaces are warm charcoal neutrals`() {
        val scheme = resolveVoicaColorScheme(VoicaColorPreset.WENZHI_INK, darkTheme = true)

        assertEquals(Color(0xFF111210), scheme.background)
        assertEquals(Color(0xFF191A18), scheme.surface)
        assertEquals(Color(0xFF1D1E1B), scheme.surfaceContainer)
        assertEquals(Color(0xFF2D2E2A), scheme.surfaceContainerHighest)

        surfaceRoles(scheme).forEach { color ->
            assertTrue(
                color.green + 0.002f >= color.blue,
                "WENZHI Ink dark surface must not drift into blue-purple: $color",
            )
        }
    }

    @Test
    fun `WENZHI Paper defaults to warm xuan paper and supports four paper tones`() {
        val expectedBackgrounds =
            mapOf(
                WenzhiPaperTone.PAPER_WHITE to Color(0xFFFAF6E9),
                WenzhiPaperTone.XUAN_WARM to Color(0xFFF6F0DF),
                WenzhiPaperTone.RICE_APRICOT to Color(0xFFF1E7CF),
                WenzhiPaperTone.ANTIQUE to Color(0xFFEFE3C5),
            )

        expectedBackgrounds.forEach { (tone, expected) ->
            val scheme =
                resolveVoicaColorScheme(
                    preset = VoicaColorPreset.WENZHI_PAPER,
                    darkTheme = false,
                    paperTone = tone,
                )
            assertEquals(expected, scheme.background)
            assertEquals(Color(0xFF776244), scheme.primary)
        }

        assertEquals(
            Color(0xFFF6F0DF),
            resolveVoicaColorScheme(VoicaColorPreset.WENZHI_PAPER, darkTheme = false).background,
        )
    }

    @Test
    fun `legacy preset surfaces retain their own color personalities`() {
        val mint = resolveVoicaColorScheme(VoicaColorPreset.MINT, darkTheme = false).surfaceContainer
        val blue = resolveVoicaColorScheme(VoicaColorPreset.BLUE, darkTheme = false).surfaceContainer
        val purple = resolveVoicaColorScheme(VoicaColorPreset.PURPLE, darkTheme = false).surfaceContainer

        assertTrue(mint.green > mint.red, "Mint surface must keep a cool green cast")
        assertTrue(blue.blue > blue.red, "Blue surface must keep a blue-grey cast")
        assertTrue(purple.blue > purple.green, "Purple surface must keep a restrained purple-grey cast")
        assertTrue(mint != blue && blue != purple && mint != purple)
    }

    @Test
    fun `custom theme generates its own controlled neutral surfaces`() {
        val accent = 0xFFC25B47.toInt()
        val light = resolveVoicaColorScheme(VoicaColorPreset.CUSTOM, false, customAccentArgb = accent)
        val dark = resolveVoicaColorScheme(VoicaColorPreset.CUSTOM, true, customAccentArgb = accent)

        assertEquals(Color(accent), light.primary)
        assertEquals(Color(accent), dark.primary)
        assertTrue(light.surfaceContainer != resolveVoicaColorScheme(VoicaColorPreset.MINT, false).surfaceContainer)
        assertTrue(dark.surfaceContainer != resolveVoicaColorScheme(VoicaColorPreset.MINT, true).surfaceContainer)
        assertTrue(light.surfaceContainer.red > light.surfaceContainer.blue)
    }

    private fun surfaceRoles(scheme: ColorScheme): List<Color> =
        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceDim,
            scheme.surfaceBright,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceVariant,
        )

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
