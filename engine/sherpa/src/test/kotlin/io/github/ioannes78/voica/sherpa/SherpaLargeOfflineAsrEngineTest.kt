package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.AsrExecutionMode
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelPunctuationMode
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.TimestampCapability
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaLargeOfflineAsrEngineTest {
    @Test
    fun fireRedLayoutRejectsMissingTokensBeforeNativeLoad() {
        val directory = Files.createTempDirectory("firered-layout").toFile()
        try {
            directory.resolve("model.int8.onnx").writeBytes(byteArrayOf(1))
            val failure =
                runCatching {
                    createSherpaLargeOfflineAsrEngine(
                        model = descriptor(SherpaOfflineAsrModelType.FIRE_RED_ASR2_CTC),
                        modelDirectory = directory,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("tokens.txt"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun qwenLayoutRejectsMissingTokenizerBeforeNativeLoad() {
        val directory = Files.createTempDirectory("qwen3-layout").toFile()
        try {
            directory.resolve("conv_frontend.onnx").writeBytes(byteArrayOf(1))
            directory.resolve("encoder.int8.onnx").writeBytes(byteArrayOf(1))
            directory.resolve("decoder.int8.onnx").writeBytes(byteArrayOf(1))
            val failure =
                runCatching {
                    createSherpaLargeOfflineAsrEngine(
                        model = descriptor(SherpaOfflineAsrModelType.QWEN3_ASR),
                        modelDirectory = directory,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("tokenizer"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownRuntimeModelTypeFailsBeforeNativeLoad() {
        val directory = Files.createTempDirectory("large-offline-unknown").toFile()
        try {
            val failure =
                runCatching {
                    createSherpaLargeOfflineAsrEngine(
                        model = descriptor("unknown-large-asr"),
                        modelDirectory = directory,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("runtimeModelType"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descriptor(runtimeModelType: String) =
        ModelDescriptor(
            modelId = "large-test",
            kind = ModelKind.ASR_LARGE,
            displayName = "Large ASR test",
            version = "1",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh"),
            capabilities =
                ModelCapabilities(
                    supportsSecondPass = true,
                    executionMode = AsrExecutionMode.OFFLINE,
                    timestampCapability = TimestampCapability.NONE,
                    punctuationMode = ModelPunctuationMode.EXTERNAL,
                ),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.TAR_BZ2,
            downloadUrl = "https://example.invalid/model.tar.bz2",
            downloadSizeBytes = 1,
            installedSizeBytes = 1,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.int8.onnx",
                        sizeBytes = 1,
                        sha256 = "b".repeat(64),
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
