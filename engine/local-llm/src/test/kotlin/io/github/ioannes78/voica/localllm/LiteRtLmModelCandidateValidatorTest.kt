package io.github.ioannes78.voica.localllm

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmModelCandidateValidatorTest {
    @Test
    fun `runtime contract stays aligned with packaged LiteRT-LM version`() {
        assertEquals(BuildConfig.LITERT_LM_VERSION, LiteRtLmRuntimeContract.RUNTIME_VERSION)
    }

    @Test
    fun `validator accepts one managed litertlm artifact and crosses injected runtime boundary`() =
        runBlocking {
            val root = Files.createTempDirectory("litertlm-candidate").toFile()
            try {
                val model = root.resolve("gemma-4-E2B-it.litertlm")
                model.writeText("fixture")
                var validatedPath: String? = null
                val validator =
                    LiteRtLmModelCandidateValidator { path ->
                        validatedPath = path
                    }

                validator.validate(descriptor(model.length()), root)

                assertEquals(model.canonicalPath, validatedPath)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `validator rejects non local llm descriptor before runtime`() = runBlocking {
        val root = Files.createTempDirectory("litertlm-candidate").toFile()
        try {
            root.resolve("gemma-4-E2B-it.litertlm").writeText("fixture")
            var runtimeCalled = false
            val validator =
                LiteRtLmModelCandidateValidator {
                    runtimeCalled = true
                }

            val failure =
                runCatching {
                    validator.validate(
                        descriptor(7L).copy(kind = ModelKind.ASR_LARGE),
                        root,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(false, runtimeCalled)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `validator wraps runtime init failure but preserves cancellation and oom policy`() =
        runBlocking {
            val root = Files.createTempDirectory("litertlm-candidate").toFile()
            try {
                root.resolve("gemma-4-E2B-it.litertlm").writeText("fixture")
                val validator =
                    LiteRtLmModelCandidateValidator {
                        error("native init failed")
                    }

                val failure =
                    runCatching { validator.validate(descriptor(7L), root) }
                        .exceptionOrNull()

                assertTrue(failure is LiteRtLmModelValidationException)
                assertEquals(
                    LiteRtLmModelValidationStage.NATIVE_INIT,
                    (failure as LiteRtLmModelValidationException).stage,
                )
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `local llm descriptor requires exact model kind in shared model contract`() {
        val descriptor = descriptor(7L)
        assertEquals(ModelKind.LOCAL_LLM, descriptor.kind)
        assertEquals(LiteRtLmRuntimeContract.RUNTIME_ID, descriptor.runtimeId)
        assertEquals(
            "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            descriptor.version,
        )
    }

    private fun descriptor(size: Long): ModelDescriptor =
        ModelDescriptor(
            modelId = "gemma-4-e2b-it-litertlm",
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
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "gemma-4-E2B-it.litertlm",
                        sizeBytes = size,
                        sha256 = "a".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 92,
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
