package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCatalogCodec
import io.github.ioannes78.voica.model.ModelSourceType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapModelCatalogTest {
    @Test
    fun bundledCatalogDecodesBuiltinSileroWithoutDownloadObject() {
        val asset =
            listOf(
                File("src/main/assets/" + VoicaModelChannel.BOOTSTRAP_CATALOG_ASSET),
                File("app/src/main/assets/" + VoicaModelChannel.BOOTSTRAP_CATALOG_ASSET),
            ).firstOrNull(File::isFile)
                ?: error("bootstrap model catalog asset is missing from app/src/main/assets")

        val catalog = ModelCatalogCodec.decode(asset.readText(Charsets.UTF_8))
        val silero =
            requireNotNull(catalog.model(Stage8ModelIds.VAD))

        assertEquals("production", catalog.channel)
        assertEquals(ModelSourceType.BUILTIN_WITH_OVERRIDE, silero.sourceType)
        assertEquals(
            "models/silero/silero_vad.int8.onnx",
            silero.builtinAssetPath,
        )
        assertNull(silero.downloadUrl)
        assertNull(silero.packageFormat)
        assertEquals(212_860L, silero.installedSizeBytes)
        assertTrue(silero.autoUpdateEligible)
    }

    @Test
    fun debugCandidateManifestUrlIsRestrictedToVoicaReleaseProductionJson() {
        assertTrue(
            VoicaModelChannel.isAllowedDebugManifestUrl(
                "https://github.com/ioannes78/voica-model-channel/releases/download/" +
                    "candidate-small-bilingual-r1/production.json",
            ),
        )

        assertFalse(
            VoicaModelChannel.isAllowedDebugManifestUrl(
                "http://github.com/ioannes78/voica-model-channel/releases/download/" +
                    "candidate-small-bilingual-r1/production.json",
            ),
        )
        assertFalse(
            VoicaModelChannel.isAllowedDebugManifestUrl(
                "https://github.com/other/voica-model-channel/releases/download/" +
                    "candidate-small-bilingual-r1/production.json",
            ),
        )
        assertFalse(
            VoicaModelChannel.isAllowedDebugManifestUrl(
                "https://github.com/ioannes78/voica-model-channel/releases/download/" +
                    "candidate-small-bilingual-r1/model.zip",
            ),
        )
        assertFalse(
            VoicaModelChannel.isAllowedDebugManifestUrl(
                VoicaModelChannel.PRODUCTION_MANIFEST_URL,
            ),
        )
    }
}
