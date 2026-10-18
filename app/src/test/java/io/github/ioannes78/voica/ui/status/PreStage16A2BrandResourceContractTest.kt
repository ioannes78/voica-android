package io.github.ioannes78.voica.ui.status

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreStage16A2BrandResourceContractTest {
    @Test
    fun `final Voica vector resources replace legacy bitmap brand assets`() {
        val symbol = resourceFile("drawable/voica_brand_symbol.xml")
        val foreground = resourceFile("drawable/ic_voica_foreground.xml")
        val monochrome = resourceFile("drawable/ic_voica_monochrome.xml")
        val notification = resourceFile("drawable/ic_stat_voica.xml")

        listOf(symbol, foreground, monochrome, notification).forEach { file ->
            assertTrue("Missing final Voica resource: ${file.path}", file.isFile)
        }

        val symbolXml = symbol.readText()
        assertTrue(symbolXml.contains("#16E6EA"))
        assertTrue(symbolXml.contains("#2C8BFF"))
        assertTrue(symbolXml.contains("#A46BFF"))
        assertTrue(symbolXml.contains("M105,535"))
        assertTrue(symbolXml.contains("M504,652"))

        val foregroundXml = foreground.readText()
        assertTrue(foregroundXml.contains("M22.000,58.309"))
        assertTrue(foregroundXml.contains("android:strokeWidth=\"4.293\""))

        val notificationXml = notification.readText()
        assertTrue(notificationXml.contains("android:strokeWidth=\"68\""))
        assertTrue(notificationXml.contains("#FFFFFFFF"))

        listOf(
            "drawable-nodpi/voica_brand_symbol_original.webp",
            "drawable-nodpi/voica_app_icon_foreground.webp",
            "drawable-nodpi/ic_voica_notification.png",
            "drawable/voica_brand_symbol_original.xml",
            "drawable/voica_app_icon_foreground.xml",
            "drawable/ic_voica_notification.xml",
        ).forEach { relativePath ->
            assertFalse("Legacy brand resource must be removed: $relativePath", resourceFile(relativePath).exists())
        }
    }

    @Test
    fun `adaptive launcher uses final safe zone foreground and dedicated monochrome`() {
        val v26 = resourceFile("mipmap-anydpi-v26/ic_launcher.xml").readText()
        val v26Round = resourceFile("mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        val v33 = resourceFile("mipmap-anydpi-v33/ic_launcher.xml").readText()
        val v33Round = resourceFile("mipmap-anydpi-v33/ic_launcher_round.xml").readText()

        listOf(v26, v26Round, v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/ic_voica_foreground"))
            assertTrue(xml.contains("@color/voica_launcher_background"))
            assertFalse(xml.contains("voica_app_icon_foreground"))
        }
        listOf(v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/ic_voica_monochrome"))
            assertFalse(xml.contains("ic_voica_notification"))
        }

        assertTrue(resourceFile("values/colors.xml").readText().contains("#06132B"))
    }

    @Test
    fun `production notification sources use ic stat Voica only`() {
        val sourceRoot = appRoot().resolve("src/main/java")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val legacyUsers = kotlinFiles.filter { it.readText().contains("R.drawable.ic_voica_notification") }
        assertTrue("Legacy notification resource references remain: ${legacyUsers.map { it.path }}", legacyUsers.isEmpty())
        assertTrue(kotlinFiles.any { it.readText().contains("R.drawable.ic_stat_voica") })
    }

    private fun resourceFile(relativePath: String): File = appRoot().resolve("src/main/res/$relativePath")

    private fun appRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "app") userDir else userDir.resolve("app")
    }
}
