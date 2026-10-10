package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCatalog
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage14SecurityContractTest {
    @Test
    fun productionCatalogIsPinnedToAcceptedChannelVersionAndDigest() {
        val accepted = catalog()

        assertSame(accepted, VoicaModelChannel.requirePinnedProductionCatalog(accepted))
        assertRejected(catalog(channel = "candidate"))
        assertRejected(catalog(manifestVersion = VoicaModelChannel.PRODUCTION_MANIFEST_VERSION + 1))
        assertRejected(catalog(manifestDigest = "0".repeat(64)))
    }

    @Test
    fun productionManifestUsesImmutableModelChannelCommit() {
        assertTrue(
            VoicaModelChannel.PRODUCTION_MANIFEST_URL.contains(
                "/${VoicaModelChannel.PRODUCTION_MODEL_CHANNEL_COMMIT}/manifests/production.json",
            ),
        )
        assertTrue(!VoicaModelChannel.PRODUCTION_MANIFEST_URL.contains("/main/"))
    }

    @Test
    fun v1ManifestDisablesBackupAndCleartextTraffic() {
        val manifest = appMainFile("AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
    }

    private fun catalog(
        channel: String = "production",
        manifestVersion: Int = VoicaModelChannel.PRODUCTION_MANIFEST_VERSION,
        manifestDigest: String = VoicaModelChannel.PRODUCTION_MANIFEST_DIGEST,
    ) =
        ModelCatalog(
            catalogVersion = 1,
            manifestVersion = manifestVersion,
            channel = channel,
            publishedAt = null,
            manifestDigest = manifestDigest,
            signature = null,
            keyId = null,
            models = emptyList(),
        )

    private fun assertRejected(catalog: ModelCatalog) {
        var rejected = false
        try {
            VoicaModelChannel.requirePinnedProductionCatalog(catalog)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("untrusted production catalog must be rejected", rejected)
    }

    private fun appMainFile(relative: String): File {
        val candidates =
            listOf(
                File("src/main/$relative"),
                File("app/src/main/$relative"),
            )
        return candidates.firstOrNull(File::isFile)
            ?: error("app main file not found: $relative")
    }
}
