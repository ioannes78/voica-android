package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaCtTransformerPunctuationEngineTest {
    @Test
    fun delegatesTextAndClosesNativeProcessor() {
        runBlocking {
            val native = FakeNativePunctuationProcessor()
            val engine =
                SherpaCtTransformerPunctuationEngine(
                    model = descriptor(),
                    native = native,
                )

            assertEquals("今天我们开会。", engine.addPunctuation("今天我们开会"))
            assertEquals("", engine.addPunctuation(""))
            assertEquals(1, native.calls)

            engine.close()
            assertTrue(native.closed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPunctuationDescriptor() {
        SherpaCtTransformerPunctuationEngine(
            model = descriptor().copy(kind = ModelKind.ASR_STREAMING),
            native = FakeNativePunctuationProcessor(),
        )
    }

    private fun descriptor() =
        ModelDescriptor(
            modelId = "ct-transformer-zh-en-int8",
            kind = ModelKind.PUNCTUATION,
            displayName = "中英文标点恢复",
            version = "2024-04-12",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.SINGLE_FILE,
            downloadUrl = "https://example.invalid/model.int8.onnx",
            downloadSizeBytes = 1L,
            installedSizeBytes = 1L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = CtTransformerPunctuationLayout.MODEL,
                        sizeBytes = 1L,
                        sha256 = "b".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "upstream CT-Transformer",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )

    private class FakeNativePunctuationProcessor : NativePunctuationProcessor {
        var calls = 0
        var closed = false

        override fun addPunctuation(text: String): String {
            calls += 1
            return text + "。"
        }

        override fun close() {
            closed = true
        }
    }
}
