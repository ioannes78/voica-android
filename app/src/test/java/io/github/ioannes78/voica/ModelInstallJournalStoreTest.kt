package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.modelInstallPackagePartName
import io.github.ioannes78.voica.model.modelInstallStagingDirectoryName
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallJournalStoreTest {
    private val root = Files.createTempDirectory("voica-model-install-journal").toFile()
    private var now = 1_000L
    private val store = ModelInstallJournalStore(root) { now++ }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun roundTripPreservesFrozenCandidateAndExecutionState() {
        val snapshot = snapshot()
        val initial =
            store.newRecord(
                operationId = "op-1",
                snapshot = snapshot,
                origin = ModelInstallOrigin.MANUAL,
            )
        val written =
            store.write(
                initial.copy(
                    phase = ModelInstallPhase.DOWNLOAD,
                    downloadedBytes = 4L,
                    executorKind = ModelInstallExecutorKind.UIDT,
                    executorGeneration = 3L,
                    attempt = 2,
                ),
            )

        val restored = store.read("op-1")!!

        assertEquals(written, restored)
        assertEquals(snapshot, restored.snapshot)
        assertEquals("https://example.invalid/model.bin", restored.snapshot.descriptor.downloadUrl)
        assertEquals(4L, restored.downloadedBytes)
        assertEquals(3L, restored.executorGeneration)
    }

    @Test
    fun activeJournalProtectsItsPartAndStagingButReadyDoesNot() {
        val snapshot = snapshot()
        val record =
            store.write(
                store.newRecord(
                    operationId = "op-2",
                    snapshot = snapshot,
                    origin = ModelInstallOrigin.AUTO_SMALL,
                ).copy(phase = ModelInstallPhase.EXTRACT),
            )

        val activeProtection = store.transientProtection()
        assertTrue(
            modelInstallPackagePartName(snapshot) in activeProtection.packagePartNames,
        )
        assertTrue(
            modelInstallStagingDirectoryName(snapshot) in activeProtection.stagingDirectoryNames,
        )

        store.write(record.copy(phase = ModelInstallPhase.READY))
        val terminalProtection = store.transientProtection()
        assertFalse(
            modelInstallPackagePartName(snapshot) in terminalProtection.packagePartNames,
        )
        assertFalse(
            modelInstallStagingDirectoryName(snapshot) in terminalProtection.stagingDirectoryNames,
        )
    }

    @Test
    fun corruptJournalDoesNotCrashDirectoryReconciliation() {
        root.resolve("broken.properties").writeText("not=a-valid-journal\n")

        assertTrue(store.readAll().isEmpty())
        assertNull(store.activeForModel("vad"))
    }

    @Test
    fun latestWriteAtomicallyReplacesPreviousState() {
        val initial =
            store.write(
                store.newRecord(
                    operationId = "op-3",
                    snapshot = snapshot(),
                    origin = ModelInstallOrigin.MANUAL,
                ),
            )
        store.write(
            initial.copy(
                phase = ModelInstallPhase.FAILED_RECOVERABLE,
                downloadedBytes = 7L,
                requiresUserResume = true,
                lastFailureCode = "NETWORK",
            ),
        )

        val restored = store.read("op-3")!!
        assertEquals(ModelInstallPhase.FAILED_RECOVERABLE, restored.phase)
        assertEquals(7L, restored.downloadedBytes)
        assertTrue(restored.requiresUserResume)
        assertEquals("NETWORK", restored.lastFailureCode)
    }

    private fun snapshot(): ModelDescriptorSnapshot =
        ModelDescriptorSnapshot(
            descriptor =
                ModelDescriptor(
                    modelId = "vad",
                    kind = ModelKind.VAD,
                    displayName = "Silero VAD",
                    version = "v2",
                    revision = 2,
                    runtimeId = "sherpa-onnx",
                    runtimeVersionMin = null,
                    runtimeVersionMax = null,
                    languages = setOf("zh"),
                    capabilities = ModelCapabilities(),
                    sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                    builtinAssetPath = null,
                    packageFormat = ModelPackageFormat.SINGLE_FILE,
                    downloadUrl = "https://example.invalid/model.bin",
                    mirrors = listOf("https://mirror.invalid/model.bin"),
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
                    autoUpdateEligible = true,
                ),
            manifestDigest = "a".repeat(64),
        )
}
