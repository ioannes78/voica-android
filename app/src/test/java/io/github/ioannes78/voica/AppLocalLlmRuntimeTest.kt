package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LiteRtLmRuntimeContract
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmFailureCode
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import io.github.ioannes78.voica.model.ActiveModel
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

class AppLocalLlmRuntimeTest {
    @Test
    fun stage172Revision2ForcesAutoStructuredOutputToCpu() {
        val original =
            LocalLlmRuntimeConfig(
                backendPolicy = LocalLlmBackendPolicy.AUTO,
                responseJsonSchema = "{\"type\":\"object\"}",
            )

        val effective = applyStage172Revision2BackendSafety(original)

        assertEquals(LocalLlmBackendPolicy.CPU, effective.backendPolicy)
        assertEquals(original.responseJsonSchema, effective.responseJsonSchema)
        assertEquals(original.maxNumTokens, effective.maxNumTokens)
        assertEquals(original.maxOutputTokens, effective.maxOutputTokens)
    }

    @Test
    fun stage172Revision2LeavesUnconstrainedAutoGpuFirstCapabilityUntouched() {
        val original = LocalLlmRuntimeConfig(backendPolicy = LocalLlmBackendPolicy.AUTO)

        val effective = applyStage172Revision2BackendSafety(original)

        assertEquals(LocalLlmBackendPolicy.AUTO, effective.backendPolicy)
    }

    @Test
    fun stage172Revision2DoesNotOverrideExplicitDiagnosticBackend() {
        val explicitGpu =
            LocalLlmRuntimeConfig(
                backendPolicy = LocalLlmBackendPolicy.GPU,
                responseJsonSchema = "{\"type\":\"object\"}",
            )
        val explicitCpu = explicitGpu.copy(backendPolicy = LocalLlmBackendPolicy.CPU)

        assertEquals(
            LocalLlmBackendPolicy.GPU,
            applyStage172Revision2BackendSafety(explicitGpu).backendPolicy,
        )
        assertEquals(
            LocalLlmBackendPolicy.CPU,
            applyStage172Revision2BackendSafety(explicitCpu).backendPolicy,
        )
    }

    @Test
    fun managedGemmaResolutionUsesInstalledArtifactAndPreservesLineage() {
        val root = Files.createTempDirectory("managed-gemma").toFile()
        try {
            val file = root.resolve("gemma-4-E2B-it.litertlm")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val active = ActiveModel(descriptor = descriptor(3L), manifestDigest = "a".repeat(64), installedDirectory = root)

            val resolved = resolveManagedLocalLlmModel(active, LocalLlmBackendPolicy.AUTO)

            assertEquals(file.canonicalPath, resolved.modelFile.canonicalPath)
            assertEquals("7fa1d78473894f7e736a21d920c3aa80f950c0db:r1", resolved.identity.revision)
            assertEquals(LiteRtLmRuntimeContract.RUNTIME_ID, resolved.identity.runtimeId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingManagedArtifactIsClassifiedAsModelCorrupt() {
        val root = Files.createTempDirectory("managed-gemma-missing").toFile()
        try {
            val active = ActiveModel(descriptor(3L), manifestDigest = "a".repeat(64), installedDirectory = root)

            val failure = runCatching {
                resolveManagedLocalLlmModel(active, LocalLlmBackendPolicy.AUTO)
            }.exceptionOrNull()

            assertTrue(failure is LocalLlmRuntimeException)
            assertEquals(
                LocalLlmFailureCode.MODEL_CORRUPT,
                (failure as LocalLlmRuntimeException).failure.code,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun descriptor(size: Long): ModelDescriptor =
        ModelDescriptor(
            modelId = STAGE17_GEMMA_4_E2B_CANDIDATE_ID,
            kind = ModelKind.LOCAL_LLM,
            displayName = "Gemma 4 E2B",
            version = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            revision = 1,
            runtimeId = LiteRtLmRuntimeContract.RUNTIME_ID,
            runtimeVersionMin = LiteRtLmRuntimeContract.RUNTIME_VERSION,
            runtimeVersionMax = LiteRtLmRuntimeContract.RUNTIME_VERSION,
            languages = setOf("und"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.SINGLE_FILE,
            downloadUrl = "https://example.invalid/gemma.litertlm",
            mirrors = emptyList(),
            downloadSizeBytes = size,
            installedSizeBytes = size,
            packageSha256 = "b".repeat(64),
            files = listOf(
                ModelFileDescriptor(
                    relativePath = "gemma-4-E2B-it.litertlm",
                    sizeBytes = size,
                    sha256 = "b".repeat(64),
                ),
            ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = null,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "fixture",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
        )
}
