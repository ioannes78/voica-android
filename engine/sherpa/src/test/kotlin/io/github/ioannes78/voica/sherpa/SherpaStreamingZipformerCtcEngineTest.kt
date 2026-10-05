package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaStreamingZipformerCtcEngineTest {
    @Test
    fun resolvesZipformer2CtcLayoutWithoutAssumingTransducerFiles() {
        val directory = Files.createTempDirectory("voica-ctc-test").toFile()
        try {
            Zipformer2CtcLayout.REQUIRED_FILES.forEach { name ->
                directory.resolve(name).writeBytes(byteArrayOf(1))
            }

            val files = Zipformer2CtcModelFiles.fromDirectory(directory)

            assertEquals("model.int8.onnx", files.model.name)
            assertEquals("tokens.txt", files.tokens.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun factoryRejectsUnknownRuntimeModelTypeBeforeNativeLoad() {
        val directory = Files.createTempDirectory("voica-unknown-asr-test").toFile()
        try {
            val descriptor = descriptor("unknown-streaming-family")
            val error =
                runCatching {
                    createSherpaStreamingAsrEngine(
                        model = descriptor,
                        modelDirectory = directory,
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("runtimeModelType") == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descriptor(runtimeModelType: String) =
        ModelDescriptor(
            modelId = "streaming-test",
            kind = ModelKind.ASR_STREAMING,
            displayName = "流式测试模型",
            version = "2025-06-30",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh"),
            capabilities =
                ModelCapabilities(
                    supportsStreaming = true,
                    supportsPartial = true,
                    supportsTokenTiming = true,
                ),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.TAR_BZ2,
            downloadUrl = "https://example.invalid/model.tar.bz2",
            downloadSizeBytes = 1L,
            installedSizeBytes = 2L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.int8.onnx",
                        sizeBytes = 1,
                        sha256 = "b".repeat(64),
                    ),
                    ModelFileDescriptor(
                        relativePath = "tokens.txt",
                        sizeBytes = 1,
                        sha256 = "c".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 41,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "test",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
            runtimeModelType = runtimeModelType,
        )
}
