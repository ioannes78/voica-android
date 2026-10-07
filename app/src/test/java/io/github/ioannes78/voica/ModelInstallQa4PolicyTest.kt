package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallQa4PolicyTest {
    @Test
    fun ownerTaskPresentKeepsManualInstallRunning() {
        val record = record(generation = 7L)
        val owner = ModelInstallTaskOwner(record.operationId, 7L, taskId = 1843)
        assertFalse(
            shouldInterruptForMissingOwnerTask(
                owner = owner,
                record = record,
                activeTaskIds = setOf(1843, 1900),
            ),
        )
    }

    @Test
    fun missingOwnerTaskInterruptsExactManualGeneration() {
        val record = record(generation = 7L)
        val owner = ModelInstallTaskOwner(record.operationId, 7L, taskId = 1843)
        assertTrue(
            shouldInterruptForMissingOwnerTask(
                owner = owner,
                record = record,
                activeTaskIds = setOf(1900),
            ),
        )
    }

    @Test
    fun staleGenerationAutoTaskAndInterruptedTaskAreIgnored() {
        val record = record(generation = 7L)
        val staleOwner = ModelInstallTaskOwner(record.operationId, 6L, taskId = 1843)
        assertFalse(
            shouldInterruptForMissingOwnerTask(
                owner = staleOwner,
                record = record,
                activeTaskIds = emptySet(),
            ),
        )
        assertFalse(
            shouldInterruptForMissingOwnerTask(
                owner = ModelInstallTaskOwner(record.operationId, 7L, taskId = 1843),
                record = record.copy(origin = ModelInstallOrigin.AUTO_SMALL),
                activeTaskIds = emptySet(),
            ),
        )
        assertFalse(
            shouldInterruptForMissingOwnerTask(
                owner = ModelInstallTaskOwner(record.operationId, 7L, taskId = 1843),
                record = record.copy(
                    phase = ModelInstallPhase.INTERRUPTED,
                    requiresUserResume = true,
                    executorKind = ModelInstallExecutorKind.NONE,
                ),
                activeTaskIds = emptySet(),
            ),
        )
    }

    @Test
    fun missingOwnershipDoesNotInventUserIntent() {
        assertFalse(
            shouldInterruptForMissingOwnerTask(
                owner = null,
                record = record(generation = 7L),
                activeTaskIds = emptySet(),
            ),
        )
    }

    private fun record(generation: Long): ModelInstallJournalRecord =
        ModelInstallJournalRecord(
            operationId = "qa4-op",
            snapshot = snapshot(),
            origin = ModelInstallOrigin.MANUAL,
            phase = ModelInstallPhase.DOWNLOAD,
            downloadedBytes = 4L,
            totalBytes = 10L,
            executorKind = ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            executorGeneration = generation,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

    private fun snapshot(): ModelDescriptorSnapshot =
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
