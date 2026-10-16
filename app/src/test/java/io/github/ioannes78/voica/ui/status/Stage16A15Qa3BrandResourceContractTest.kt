package io.github.ioannes78.voica.ui.status

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A15Qa3BrandResourceContractTest {
    @Test
    fun `QA3 canonical brand resources stay byte stable and approximate vectors stay removed`() {
        assertSha256(
            "drawable-nodpi/voica_brand_symbol_original.webp",
            "2b30e9fd3a64fd1ef2eca4e67115e1f4b4a30d5740b656d32628659766de5e28",
        )
        assertSha256(
            "drawable-nodpi/voica_qs668_device_photo.webp",
            "48f4663d850f12f3ecf9bc7724c3c597cf9369d4fdde4bdac16083c4401beece",
        )
        assertSha256(
            "drawable-nodpi/voica_app_icon_foreground.webp",
            "2b30e9fd3a64fd1ef2eca4e67115e1f4b4a30d5740b656d32628659766de5e28",
        )
        assertSha256(
            "drawable-nodpi/ic_voica_notification.png",
            "838a7e2681f1d95df4351e59124c20e095ea1281f479d3f9f40cf0ac4a5e165d",
        )

        listOf(
            "drawable/voica_brand_symbol_original.xml",
            "drawable/voica_qs668_device_photo.xml",
            "drawable/voica_app_icon_foreground.xml",
            "drawable/ic_voica_notification.xml",
            "drawable/voica_brand_symbol.xml",
            "drawable/voica_qs668_device.xml",
        ).forEach { relativePath ->
            assertFalse(
                "Approximate QA2 resource must stay removed: $relativePath",
                resourceFile(relativePath).exists(),
            )
        }
    }

    @Test
    fun `adaptive launcher resources reference canonical QA3 artwork`() {
        val v26 = resourceFile("mipmap-anydpi-v26/ic_launcher.xml").readText()
        val v26Round = resourceFile("mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        val v33 = resourceFile("mipmap-anydpi-v33/ic_launcher.xml").readText()
        val v33Round = resourceFile("mipmap-anydpi-v33/ic_launcher_round.xml").readText()

        listOf(v26, v26Round, v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/voica_app_icon_foreground"))
            assertTrue(xml.contains("@color/voica_launcher_background"))
        }
        listOf(v33, v33Round).forEach { xml ->
            assertTrue(xml.contains("@drawable/ic_voica_notification"))
        }
    }

    private fun assertSha256(relativePath: String, expected: String) {
        val file = resourceFile(relativePath)
        assertTrue("Missing canonical QA3 resource: $relativePath", file.isFile)
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val actual = digest.joinToString("") { byte -> "%02x".format(byte) }
        assertEquals("Unexpected bytes for $relativePath", expected, actual)
    }

    private fun resourceFile(relativePath: String): File {
        val userDir = File(System.getProperty("user.dir"))
        val moduleRelative = File(userDir, "src/main/res/$relativePath")
        if (moduleRelative.exists() || userDir.name == "app") return moduleRelative
        return File(userDir, "app/src/main/res/$relativePath")
    }
}
