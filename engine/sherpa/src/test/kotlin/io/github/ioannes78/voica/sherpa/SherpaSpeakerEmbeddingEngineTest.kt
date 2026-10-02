package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.SpeakerModelRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSpeakerEmbeddingEngineTest {
    @Test
    fun normalizesPcmForNativeEmbeddingAndReleasesSession() = runBlocking {
        val native = FakeNativeSpeakerEmbeddingSession(floatArrayOf(0.4F, -0.2F))
        val engine =
            SherpaSpeakerEmbeddingEngine(
                model = embeddingDescriptor(),
                native = native,
            )

        val result =
            engine.embed(
                shortArrayOf(16_384, -16_384),
                16_000,
            )

        assertEquals(0.5F, native.lastSamples!![0], 0.0001F)
        assertEquals(-0.5F, native.lastSamples!![1], 0.0001F)
        assertEquals(0.4F, result[0], 0.0001F)
        engine.close()
        assertTrue(native.closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteEmbedding() {
        runBlocking {
            val engine =
                SherpaSpeakerEmbeddingEngine(
                    model = embeddingDescriptor(),
                    native = FakeNativeSpeakerEmbeddingSession(floatArrayOf(Float.NaN)),
                )
            try {
                engine.embed(shortArrayOf(1, 2, 3), 16_000)
                Unit
            } finally {
                engine.close()
            }
        }
    }

    private class FakeNativeSpeakerEmbeddingSession(
        private val output: FloatArray,
    ) : NativeSpeakerEmbeddingSession {
        var lastSamples: FloatArray? = null
        var closed = false

        override fun compute(
            samples: FloatArray,
            sampleRateHz: Int,
        ): FloatArray {
            assertEquals(16_000, sampleRateHz)
            lastSamples = samples.copyOf()
            return output.copyOf()
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        fun embeddingDescriptor() =
            ModelDescriptor(
                modelId = "eres2net-base-zh-stage9",
                kind = ModelKind.SPEAKER,
                displayName = "ERes2Net Base 中文",
                version = "stage9",
                revision = 1,
                runtimeId = SherpaRuntime.RUNTIME_ID,
                runtimeVersionMin = SherpaRuntime.RUNTIME_VERSION,
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/eres2net.onnx",
                mirrors = emptyList(),
                downloadSizeBytes = 39_593_761L,
                installedSizeBytes = 39_593_761L,
                packageSha256 =
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath =
                                "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                            sizeBytes = 39_593_761L,
                            sha256 =
                                "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b",
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 24,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "3D-Speaker upstream",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "candidate",
                autoUpdateEligible = false,
                speakerRole = SpeakerModelRole.EMBEDDING,
            )
    }
}
