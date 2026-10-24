package io.github.ioannes78.voica.ui.status

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16XWenzhiBrandResourceContractTest {
    @Test
    fun `WENZHI exact raster resources cover launcher monochrome and notification densities`() {
        listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { density ->
            listOf(
                "drawable-$density/ic_launcher_foreground.webp",
                "drawable-$density/ic_launcher_monochrome.webp",
                "drawable-$density/ic_stat_wenzhi.webp",
            ).forEach { relativePath ->
                assertTrue("Missing WENZHI resource: $relativePath", resourceFile(relativePath).isFile)
            }
        }

        listOf(
            "drawable/ic_stat_voica.xml",
            "drawable/ic_voica_foreground.xml",
            "drawable/ic_voica_monochrome.xml",
            "drawable/voica_brand_symbol.xml",
            "drawable-nodpi/voica_brand_symbol_original.webp",
            "drawable-nodpi/voica_app_icon_foreground.webp",
            "drawable-nodpi/ic_voica_notification.png",
            "drawable/voica_brand_symbol_original.xml",
            "drawable/voica_app_icon_foreground.xml",
            "drawable/ic_voica_notification.xml",
        ).forEach { relativePath ->
            assertFalse("Old Voica graphic resource must be removed: $relativePath", resourceFile(relativePath).exists())
        }
    }

    @Test
    fun `adaptive launcher uses paper background exact foreground and dedicated monochrome`() {
        val v26 = resourceFile("mipmap-anydpi-v26/ic_launcher.xml").readText()
        val v26Round = resourceFile("mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        val v33 = resourceFile("mipmap-anydpi-v33/ic_launcher.xml").readText()
        val v33Round = resourceFile("mipmap-anydpi-v33/ic_launcher_round.xml").readText()

        listOf(v26, v26Round, v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/ic_launcher_foreground"))
            assertTrue(xml.contains("@color/wenzhi_paper"))
            assertFalse(xml.contains("voica_launcher_background"))
            assertFalse(xml.contains("ic_voica_foreground"))
        }
        listOf(v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/ic_launcher_monochrome"))
            assertFalse(xml.contains("ic_stat_wenzhi"))
        }

        val colors = resourceFile("values/colors.xml").readText()
        assertTrue(colors.contains("#F5F2EA"))
        assertTrue(colors.contains("#171717"))
        assertFalse(colors.contains("#06132B"))
    }

    @Test
    fun `splash and manifest expose WENZHI brand without changing technical package identity`() {
        val lightStyle = resourceFile("values/styles.xml").readText()
        val darkStyle = resourceFile("values-night/styles.xml").readText()
        val android12Style = resourceFile("values-v31/styles.xml").readText()
        val launchScreen = resourceFile("drawable/wenzhi_launch_screen.xml").readText()
        val brandStrings = resourceFile("values/wenzhi_brand_strings.xml").readText()
        val manifest = appRoot().resolve("src/main/AndroidManifest.xml").readText()

        assertTrue(lightStyle.contains("@drawable/wenzhi_launch_screen"))
        assertTrue(darkStyle.contains("@drawable/wenzhi_launch_screen"))
        assertTrue(android12Style.contains("@color/wenzhi_paper"))
        assertTrue(android12Style.contains("@drawable/ic_launcher_foreground"))
        assertTrue(launchScreen.contains("@color/wenzhi_paper"))
        assertTrue(launchScreen.contains("@drawable/ic_launcher_foreground"))
        assertTrue(brandStrings.contains("闻知"))
        assertTrue(brandStrings.contains("WENZHI"))
        assertTrue(brandStrings.contains("闻声 · 知意"))
        assertTrue(manifest.contains("android:label=\"@string/wenzhi_app_name\""))
        assertTrue(manifest.contains("android:name=\".VoicaApplication\""))
    }

    @Test
    fun `production notification sources use WENZHI small icon only`() {
        val sourceRoot = appRoot().resolve("src/main/java")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val legacyUsers =
            kotlinFiles.filter { file ->
                val text = file.readText()
                text.contains("R.drawable.ic_voica_notification") ||
                    text.contains("R.drawable.ic_stat_voica")
            }

        assertTrue(
            "Old Voica notification resource references remain: ${legacyUsers.map { it.path }}",
            legacyUsers.isEmpty(),
        )
        assertTrue(kotlinFiles.any { it.readText().contains("R.drawable.ic_stat_wenzhi") })
        assertFalse(resourceFile("values/stage16x_brand_compat.xml").exists())
    }

    @Test
    fun `user visible About copy and default export destinations use WENZHI`() {
        val sourceRoot = appRoot().resolve("src/main/java/io/github/ioannes78/voica")
        val settings = sourceRoot.resolve("ui/settings/ProductSettingsScreen.kt").readText()
        assertFalse(settings.contains("关于 Voica"))
        assertFalse(settings.contains("Text(\"Voica\")"))
        assertTrue(settings.contains("关于闻知"))
        assertTrue(settings.contains("闻知 WENZHI"))

        listOf(
            sourceRoot.resolve("ContentTextExportCoordinator.kt"),
            sourceRoot.resolve("LocalAudioExportCoordinator.kt"),
        ).forEach { sourceFile ->
            val source = sourceFile.readText()
            assertFalse(
                "${sourceFile.name} still writes default exports into Voica",
                source.contains("DIRECTORY_DOWNLOADS + \"/Voica"),
            )
            assertFalse(
                "${sourceFile.name} still uses a Voica fallback export name",
                source.contains("\"Voica-\""),
            )
            assertTrue(
                "${sourceFile.name} must use the WENZHI default export directory",
                source.contains("DIRECTORY_DOWNLOADS + \"/WENZHI/\""),
            )
        }
    }

    @Test
    fun `home brand symbol follows theme foreground without replacing exact raster`() {
        val header =
            appRoot()
                .resolve("src/main/java/io/github/ioannes78/voica/ui/device/VoicaBrandHeader.kt")
                .readText()
        assertTrue(header.contains("R.drawable.ic_launcher_foreground"))
        assertTrue(header.contains("ColorFilter.tint(MaterialTheme.colorScheme.onBackground)"))
    }

    private fun resourceFile(relativePath: String): File = appRoot().resolve("src/main/res/$relativePath")

    private fun appRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "app") userDir else userDir.resolve("app")
    }
}
