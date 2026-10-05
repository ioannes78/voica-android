package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.transcript.PunctuationCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSenseVoiceEngineTest {
    @Test
    fun mapsSenseVoiceResultWithoutFabricatingTiming() = runBlocking {
        val native =
            FakeRecognizer(
                NativeSenseVoiceResult(
                    text = "你好，world。",
                    tokens = listOf("你", "好", " world"),
                    timestampsSeconds = listOf(0.1F, 0.2F, 0.5F),
                    language = "zh",
                ),
            )
        val engine =
            SherpaSenseVoiceEngine(
                model = descriptor(),
                native = native,
            )

        val result =
            engine.transcribe(
                samples = ShortArray(16_000) { 16_384 },
                sampleRateHz = 16_000,
            )

        assertTrue(result.isFinal)
        assertEquals("你好，world。", result.text)
        assertEquals("zh", result.detectedLanguage)
        assertEquals(PunctuationCapability.NONE, result.punctuationCapability)
        assertEquals(1_600L, result.tokens[0].startSampleOffset)
        assertEquals(8_000L, result.tokens[2].startSampleOffset)
        assertEquals(16_000, native.lastSamples.size)
        assertEquals(0.5F, native.lastSamples.first(), 0.0001F)

        engine.close()
        assertTrue(native.closed)
    }

    @Test
    fun itnEnabledDeclaresNativePunctuationReliable() = runBlocking {
        val engine =
            SherpaSenseVoiceEngine(
                model = descriptor(),
                native =
                    FakeRecognizer(
                        NativeSenseVoiceResult(
                            text = "你好。",
                            tokens = listOf("你", "好"),
                            timestampsSeconds = listOf(0.1F, 0.2F),
                            language = "zh",
                        ),
                    ),
                useInverseTextNormalization = true,
            )

        val result = engine.transcribe(ShortArray(16_000), 16_000)

        assertEquals(PunctuationCapability.RELIABLE, engine.capabilities.punctuationCapability)
        assertEquals(PunctuationCapability.RELIABLE, result.punctuationCapability)
        engine.close()
    }

    @Test
    fun invalidSenseVoiceTokenTimingIsDroppedNotInvented() = runBlocking {
        val engine =
            SherpaSenseVoiceEngine(
                model = descriptor(),
                native =
                    FakeRecognizer(
                        NativeSenseVoiceResult(
                            text = "test",
                            tokens = listOf("a", "b"),
                            timestampsSeconds = listOf(0.4F, 0.2F),
                            language = "en",
                        ),
                    ),
            )

        val result = engine.transcribe(ShortArray(16_000), 16_000)

        assertTrue(result.tokens.isEmpty())
        engine.close()
    }

    private fun descriptor() =
        ModelDescriptor(
            modelId = "sensevoice-2024-int8",
            kind = ModelKind.ASR_SECOND_PASS,
            displayName = "高质量识别",
            version = "2024-07-17",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en", "ja", "ko", "yue"),
            capabilities =
                ModelCapabilities(
                    supportsTokenTiming = true,
                    supportsLanguageDetection = true,
                    supportsInverseTextNormalization = true,
                    supportsSecondPass = true,
                ),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.TAR_BZ2,
            downloadUrl = "https://example.invalid/sensevoice.tar.bz2",
            downloadSizeBytes = 1L,
            installedSizeBytes = 1L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = SenseVoiceLayout.MODEL,
                        sizeBytes = 1L,
                        sha256 = "b".repeat(64),
                    ),
                    ModelFileDescriptor(
                        relativePath = SenseVoiceLayout.TOKENS,
                        sizeBytes = 1L,
                        sha256 = "c".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "upstream-model-license",
            licenseUrl = null,
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
            homepage = null,
            attribution = "SenseVoice / sherpa-onnx converted model",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )

    private class FakeRecognizer(
        private val result: NativeSenseVoiceResult,
    ) : NativeSecondPassRecognizer {
        var lastSamples = FloatArray(0)
        var closed = false

        override fun transcribe(
            samples: FloatArray,
            sampleRateHz: Int,
        ): NativeSenseVoiceResult {
            lastSamples = samples.copyOf()
            return result
        }

        override fun close() {
            closed = true
        }
    }
}
