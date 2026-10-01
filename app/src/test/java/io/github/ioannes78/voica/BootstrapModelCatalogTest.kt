package io.github.ioannes78.voica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.model.ModelCatalogCodec
import io.github.ioannes78.voica.model.ModelSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootstrapModelCatalogTest {
    @Test
    fun bundledCatalogDecodesBuiltinSileroWithoutDownloadObject() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text =
            context.assets.open(VoicaModelChannel.BOOTSTRAP_CATALOG_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        val catalog = ModelCatalogCodec.decode(text)
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
}
