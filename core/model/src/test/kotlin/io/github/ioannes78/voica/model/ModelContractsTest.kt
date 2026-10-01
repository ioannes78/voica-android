package io.github.ioannes78.voica.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractsTest {
    @Test
    fun managedDownloadRequiresPinnedPackageIntegrity() {
        val descriptor = streamingDescriptor()

        assertEquals(ModelKind.ASR_STREAMING, descriptor.kind)
        assertNotNull(descriptor.packageSha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelFileRejectsPathTraversal() {
        ModelFileDescriptor(
            relativePath = "../escape.onnx",
            sizeBytes = 1L,
            sha256 = "a".repeat(64),
        )
    }

    @Test
    fun catalogRejectsDuplicateModelIds() {
        val base = builtinVadDescriptor()

        try {
            ModelCatalog(
                catalogVersion = 1,
                manifestVersion = 1,
                channel = "production",
                publishedAt = null,
                manifestDigest = "f".repeat(64),
                models = listOf(base, base.copy(version = "new", revision = 2)),
            )
            throw AssertionError("duplicate model ids must fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun compatibilityChecksRuntimeAbiSdkAndAppVersion() {
        val descriptor = streamingDescriptor()
        val ok =
            descriptor.compatibilityWith(
                ModelEnvironment(
                    runtimeId = "sherpa-onnx",
                    runtimeVersion = "1.13.8",
                    abi = "arm64-v8a",
                    sdkInt = 26,
                    appVersionCode = 20,
                ),
            )
        assertTrue(ok.compatible)

        val wrongAbi =
            descriptor.compatibilityWith(
                ModelEnvironment(
                    runtimeId = "sherpa-onnx",
                    runtimeVersion = "1.13.8",
                    abi = "x86_64",
                    sdkInt = 26,
                    appVersionCode = 20,
                ),
            )
        assertFalse(wrongAbi.compatible)
        assertEquals(ModelIncompatibilityReason.ABI, wrongAbi.reason)
    }

    @Test
    fun revisionNotVersionStringDeterminesUpdates() {
        assertTrue(isModelUpdateAvailable(installedRevision = 7, remoteRevision = 8))
        assertFalse(isModelUpdateAvailable(installedRevision = 8, remoteRevision = 8))
        assertFalse(isModelUpdateAvailable(installedRevision = 9, remoteRevision = 8))
    }

    private fun streamingDescriptor() =
        ModelDescriptor(
            modelId = "zipformer-small-bilingual",
            kind = ModelKind.ASR_STREAMING,
            displayName = "基础中英识别",
            version = "2023-02-16",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities =
                ModelCapabilities(
                    supportsStreaming = true,
                    supportsPartial = true,
                    supportsTokenTiming = true,
                ),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/model.zip",
            downloadSizeBytes = 1L,
            installedSizeBytes = 1L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "encoder.int8.onnx",
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
            attribution = "upstream",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )

    private fun builtinVadDescriptor() =
        ModelDescriptor(
            modelId = "silero",
            kind = ModelKind.VAD,
            displayName = "Silero VAD",
            version = "baseline",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("und"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
            builtinAssetPath = "models/silero/silero_vad.int8.onnx",
            packageFormat = null,
            downloadUrl = null,
            downloadSizeBytes = null,
            installedSizeBytes = 1L,
            packageSha256 = null,
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "silero_vad.int8.onnx",
                        sizeBytes = 1L,
                        sha256 = "a".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "MIT",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/silero",
            homepage = null,
            attribution = "Silero VAD",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = true,
        )
}
