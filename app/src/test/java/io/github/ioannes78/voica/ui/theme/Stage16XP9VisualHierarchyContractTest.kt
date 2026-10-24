package io.github.ioannes78.voica.ui.theme

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16XP9VisualHierarchyContractTest {
    @Test
    fun `all preset and custom themes expose usable container hierarchy in light and dark`() {
        val presets =
            listOf(
                VoicaColorPreset.WENZHI_INK,
                VoicaColorPreset.WENZHI_PAPER,
                VoicaColorPreset.MINT,
                VoicaColorPreset.BLUE,
                VoicaColorPreset.PURPLE,
                VoicaColorPreset.CUSTOM,
            )

        presets.forEach { preset ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveVoicaColorScheme(preset, darkTheme)
                val backgroundDelta =
                    abs(scheme.background.red - scheme.surfaceContainerLow.red) +
                        abs(scheme.background.green - scheme.surfaceContainerLow.green) +
                        abs(scheme.background.blue - scheme.surfaceContainerLow.blue)
                val containerDelta =
                    abs(scheme.surfaceContainerLow.red - scheme.surfaceContainerHigh.red) +
                        abs(scheme.surfaceContainerLow.green - scheme.surfaceContainerHigh.green) +
                        abs(scheme.surfaceContainerLow.blue - scheme.surfaceContainerHigh.blue)

                assertTrue(
                    "$preset dark=$darkTheme page and card surfaces must remain distinguishable",
                    backgroundDelta > 0.012f,
                )
                assertTrue(
                    "$preset dark=$darkTheme low and high containers must remain distinguishable",
                    containerDelta > 0.018f,
                )
            }
        }
    }

    @Test
    fun `settings and transcript presentation use shared Material hierarchy roles`() {
        val settings = source("src/main/java/io/github/ioannes78/voica/ui/settings/ProductSettingsScreen.kt")
        val transcript = source("src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt")
        val controls = source("src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptProductContent.kt")
        val player = source("src/main/java/io/github/ioannes78/voica/ui/playback/GlobalMiniPlayer.kt")
        val themePreview = source("src/main/java/io/github/ioannes78/voica/ui/theme/ThemeSettingsCard.kt")

        assertTrue(settings.contains("surfaceContainerLow"))
        assertTrue(settings.contains("SettingsDivider()"))
        assertTrue(settings.contains("outlineVariant.copy(alpha = 0.62f)"))
        assertTrue(settings.contains("LocalContentColor provides MaterialTheme.colorScheme.primary"))

        assertTrue(transcript.contains("val paragraphBase = MaterialTheme.colorScheme.surfaceContainerLow"))
        assertTrue(transcript.contains("compositeOver(paragraphBase)"))
        assertTrue(transcript.contains("transcriptActiveParagraphBg"))
        assertTrue(transcript.contains("transcriptCurrentWordBg"))
        assertTrue(transcript.contains("style = Stroke(width = outlineWidth)"))

        assertTrue(controls.contains("selected && prominent -> colors.primaryContainer"))
        assertTrue(controls.contains("selected -> colors.surfaceContainerHigh"))
        assertTrue(controls.contains("else -> colors.surfaceContainerLow"))

        assertTrue(player.contains("color = MaterialTheme.colorScheme.surfaceContainerHigh"))
        assertTrue(player.contains("val inactive = MaterialTheme.colorScheme.outlineVariant"))

        assertTrue(themePreview.contains("CardDefaults.cardColors(containerColor = scheme.background)"))
        assertTrue(themePreview.contains("scheme.primaryContainer.copy(alpha = 0.42f)"))
        assertTrue(themePreview.contains(".background(scheme.primary, RoundedCornerShape(4.dp))"))
    }

    private fun source(relativePath: String): String = appRoot().resolve(relativePath).readText()

    private fun appRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "app") userDir else userDir.resolve("app")
    }
}
