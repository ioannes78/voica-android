package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.ModelInstallExecutorKind
import io.github.ioannes78.voica.ModelInstallJournalRecord
import io.github.ioannes78.voica.ModelInstallOrigin
import io.github.ioannes78.voica.ModelInstallPhase
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelInstallActivityAdapterTest {
    @Test
    fun downloadMapsToBackgroundProgress() {
        val fact = modelInstallActivityFact(record(ModelInstallPhase.DOWNLOAD))

        requireNotNull(fact)
        assertEquals(AppActivityKind.MODEL_INSTALL, fact.kind)
        assertEquals(AppActivityLifecycleClass.BACKGROUND_RUNNING, fact.lifecycleClass)
        assertEquals(0.4f, fact.progressFraction)
        assertEquals(setOf(AppActivityAction.CANCEL), fact.availableActions)
    }

    @Test
    fun interruptedAndRecoverableRequireAttention() {
        listOf(ModelInstallPhase.INTERRUPTED, ModelInstallPhase.FAILED_RECOVERABLE).forEach { phase ->
            val fact = modelInstallActivityFact(record(phase, requiresUserResume = true))
            requireNotNull(fact)
            assertEquals(AppActivityLifecycleClass.INTERRUPTED_ATTENTION, fact.lifecycleClass)
            assertEquals(setOf(AppActivityAction.RETRY), fact.availableActions)
        }
    }

    @Test
    fun integrityFailureMapsToFailedAttention() {
        val fact = modelInstallActivityFact(record(ModelInstallPhase.FAILED_INTEGRITY))
        requireNotNull(fact)
        assertEquals(AppActivityLifecycleClass.FAILED_ATTENTION, fact.lifecycleClass)
    }

    @Test
    fun readyAndCancelledArePassiveAndNotProjected() {
        assertNull(modelInstallActivityFact(record(ModelInstallPhase.READY)))
        assertNull(modelInstallActivityFact(record(ModelInstallPhase.CANCELLED)))
    }

    private fun record(
        phase: ModelInstallPhase,
        requiresUserResume: Boolean = false,
    ) =
        ModelInstallJournalRecord(
            operationId = "model-op",
            snapshot = snapshot(),
            origin = ModelInstallOrigin.MANUAL,
            phase = phase,
            requiresUserResume = requiresUserResume,
            downloadedBytes = 4L,
            totalBytes = 10L,
            executorKind = ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            executorGeneration = 1L,
            createdAtMs = 1L,
            updatedAtMs = 2L,
        )

    private fun snapshot() =
        ModelDescriptorSnapshot(
            descriptor =
                ModelDescriptor(
                    modelId = "vad",
                    kind = ModelKind.VAD,
                    displayName = "Silero VAD",
                    version = "v2",
                    revision = 2L,
                    runtimeId = "sherpa-onnx",
                    runtimeVersionMin = null,
                    runtimeVersionMax = null,
                    languages = setOf("zh"),
                    capabilities = ModelCapabilities(),
                    sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                    builtinAssetPath = null,
                    packageFormat = ModelPackageFormat.SINGLE_FILE,
                    downloadUrl = "https://example.invalid/model.bin",
                    mirrors = emptyList(),
                    downloadSizeBytes = 10L,
                    installedSizeBytes = 10L,
                    packageSha256 = "0".repeat(64),
                    files =
                        listOf(
                            ModelFileDescriptor(
                                relativePath = "model.onnx",
                                sizeBytes = 10L,
                                sha256 = "1".repeat(64),
                                packagePath = "model.onnx",
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
                    attribution = "test",
                    redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                    releaseChannel = "production",
                    autoUpdateEligible = false,
                ),
            manifestDigest = "a".repeat(64),
        )
}
