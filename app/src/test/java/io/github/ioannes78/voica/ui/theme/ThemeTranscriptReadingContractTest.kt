package io.github.ioannes78.voica.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Test

class ThemeTranscriptReadingContractTest {
    @Test
    fun `all presets provide readable theme-derived word highlight roles in light and dark`() {
        val cases =
            listOf(
                VoicaColorPreset.WENZHI_INK to VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                VoicaColorPreset.WENZHI_PAPER to VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                VoicaColorPreset.MINT to VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                VoicaColorPreset.BLUE to VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                VoicaColorPreset.PURPLE to VoicaThemeSettings.DEFAULT_CUSTOM_ACCENT,
                VoicaColorPreset.CUSTOM to 0xFFA65D4E.toInt(),
            )

        cases.forEach { (preset, customAccent) ->
            listOf(false, true).forEach { darkTheme ->
                val scheme =
                    resolveVoicaColorScheme(
                        preset = preset,
                        darkTheme = darkTheme,
                        customAccentArgb = customAccent,
                    )

                assertTrue(
                    colorDistance(scheme.primaryContainer, scheme.background) > 0.025f,
                    "$preset dark=$darkTheme word-highlight container must remain visible against the page",
                )
                assertTrue(
                    contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 3.0f,
                    "$preset dark=$darkTheme current-word text must remain readable",
                )
                assertTrue(
                    colorDistance(scheme.primary, scheme.background) > 0.08f,
                    "$preset dark=$darkTheme active-line accent must remain visible",
                )
            }
        }
    }

    @Test
    fun `legacy and custom themes keep paragraph and word emphasis roles distinct`() {
        listOf(
            VoicaColorPreset.MINT,
            VoicaColorPreset.BLUE,
            VoicaColorPreset.PURPLE,
            VoicaColorPreset.CUSTOM,
        ).forEach { preset ->
            listOf(false, true).forEach { darkTheme ->
                val scheme =
                    resolveVoicaColorScheme(
                        preset = preset,
                        darkTheme = darkTheme,
                        customAccentArgb = 0xFF63745A.toInt(),
                    )
                assertTrue(
                    colorDistance(scheme.primaryContainer, scheme.secondaryContainer) > 0.02f,
                    "$preset dark=$darkTheme paragraph and current-word bases must not collapse to one color",
                )
            }
        }
    }

    @Test
    fun `transcript renderer keeps weak paragraph and strong word paint theme-derived`() {
        val source =
            appRoot()
                .resolve("src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt")
                .readText()
        val segmentCard =
            source
                .substringAfter("fun TranscriptSegmentCard(")
                .substringBefore("internal fun productTranscriptionFailureMessage")

        assertTrue(segmentCard.contains("val paragraphBase = MaterialTheme.colorScheme.surfaceContainerLow"))
        assertTrue(segmentCard.contains("transcriptColors.transcriptActiveParagraphBg"))
        assertTrue(segmentCard.contains("copy(alpha = 0.22f)"))
        assertTrue(segmentCard.contains("compositeOver(paragraphBase)"))
        assertTrue(segmentCard.contains("color = transcriptColors.transcriptCurrentWordBg"))
        assertTrue(segmentCard.contains("transcriptColors.accent.copy(alpha = 0.62f)"))
        assertTrue(segmentCard.contains("style = Stroke(width = outlineWidth)"))
        assertTrue(segmentCard.contains("text = displayText"))
        assertTrue(!segmentCard.contains("Color(0x"))
        assertTrue(!segmentCard.contains("SpanStyle"))
    }

    @Test
    fun `paper and ink semantic mappings preserve stronger word paint than paragraph base`() {
        val themeSource =
            appRoot()
                .resolve("src/main/java/io/github/ioannes78/voica/ui/theme/Theme.kt")
                .readText()

        assertTrue(
            themeSource.contains(
                "transcriptCurrentWordBg = mix(scheme.primaryContainer, scheme.primary, if (darkTheme) 0.34f else 0.30f)",
            ),
        )
        assertTrue(themeSource.contains("transcriptActiveParagraphBg = scheme.primaryContainer"))
        assertTrue(themeSource.contains("WenzhiTranscriptCurrentWordBg = mix(WenzhiAccentContainer, WenzhiAccent, 0.24f)"))
        assertTrue(themeSource.contains("WenzhiDarkTranscriptCurrentWordBg = mix(WenzhiDarkAccentContainer, WenzhiDarkAccent, 0.26f)"))
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun colorDistance(
        first: Color,
        second: Color,
    ): Float =
        abs(first.red - second.red) +
            abs(first.green - second.green) +
            abs(first.blue - second.blue)

    private fun appRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "app") userDir else userDir.resolve("app")
    }
}
