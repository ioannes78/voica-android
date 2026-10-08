package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelDownloadProgress
import io.github.ioannes78.voica.model.ModelDownloadProgressListener
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelInstallBackend
import io.github.ioannes78.voica.model.ModelInstallCandidateInspection
import io.github.ioannes78.voica.model.ModelInstallTransientProtection
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelInstallOrchestratorTest {
    private val root = Files.createTempDirectory("voica-model-install-orchestrator").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun fullPipelineMovesFrozenCandidateToReady() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot())
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()

        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        var record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.DOWNLOAD, record.phase)
        assertEquals(ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD, record.executorKind)

        assertEquals(
            ModelInstallExecutionOutcome.SUCCESS,
            orchestrator.executeDownload(operationId, record.executorGeneration),
        )
        record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.VERIFY, record.phase)
        assertEquals(ModelInstallExecutorKind.WORK_MANAGER_FINALIZE, record.executorKind)

        assertEquals(
            ModelInstallExecutionOutcome.SUCCESS,
            orchestrator.executeFinalize(operationId, record.executorGeneration),
        )
        val ready = store.read(operationId)!!
        assertEquals(ModelInstallPhase.READY, ready.phase)
        assertEquals(ModelInstallExecutorKind.NONE, ready.executorKind)
        assertTrue(backend.active)
        assertEquals(
            listOf(
                "ensure-space",
                "download",
                "verify-package",
                "cleanup-staging",
                "extract",
                "verify-files",
                "runtime-validate",
                "activate",
            ),
            backend.calls,
        )
    }

    @Test
    fun reconciliationAfterActivationBeforeReadyConvergesToReady() = runBlocking {
        val store = store()
        val candidate = snapshot()
        store.write(
            store.newRecord("op-active", candidate, ModelInstallOrigin.MANUAL).copy(
                phase = ModelInstallPhase.ATOMIC_ACTIVATE,
                executorKind = ModelInstallExecutorKind.WORK_MANAGER_FINALIZE,
            ),
        )
        val backend = FakeBackend(candidate).apply {
            installed = true
            confirmedGood = true
            active = true
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)

        orchestrator.reconcileOnStartup()

        val record = store.read("op-active")!!
        assertEquals(ModelInstallPhase.READY, record.phase)
        assertEquals(ModelInstallExecutorKind.NONE, record.executorKind)
        assertFalse(record.requiresUserResume)
    }

    @Test
    fun automaticUpdateNeverSilentlyResumesUserInterruptedOperation() = runBlocking {
        val store = store()
        val candidate = snapshot()
        store.write(
            store.newRecord("op-interrupted", candidate, ModelInstallOrigin.MANUAL).copy(
                phase = ModelInstallPhase.INTERRUPTED,
                requiresUserResume = true,
                executorKind = ModelInstallExecutorKind.NONE,
            ),
        )
        val backend = FakeBackend(candidate)
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()

        try {
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.AUTO_SMALL)
            fail("automatic update must not resume a user-interrupted operation")
        } catch (_: ModelInstallUserResumeRequiredException) {
            // expected
        }

        val record = store.read("op-interrupted")!!
        assertEquals(ModelInstallPhase.INTERRUPTED, record.phase)
        assertTrue(record.requiresUserResume)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun manualResumeReusesOperationAndAdvancesExecutorGeneration() = runBlocking {
        val store = store()
        val candidate = snapshot()
        store.write(
            store.newRecord("op-resume", candidate, ModelInstallOrigin.MANUAL).copy(
                phase = ModelInstallPhase.INTERRUPTED,
                requiresUserResume = true,
                executorGeneration = 4L,
            ),
        )
        val backend = FakeBackend(candidate).apply {
            downloadedBytes = 4L
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()

        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)

        assertEquals("op-resume", operationId)
        val record = store.read(operationId)!!
        assertEquals(5L, record.executorGeneration)
        assertEquals(ModelInstallPhase.DOWNLOAD, record.phase)
        assertEquals(4L, record.downloadedBytes)
        assertFalse(record.requiresUserResume)
        assertTrue(operationId in scheduler.scheduled)
    }

    @Test
    fun explicitCancelInvalidatesExecutorAndCleansCandidate() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot())
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        val oldGeneration = store.read(operationId)!!.executorGeneration

        orchestrator.cancelOperation(operationId)

        val record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.CANCELLED, record.phase)
        assertEquals(oldGeneration + 1L, record.executorGeneration)
        assertEquals(ModelInstallExecutorKind.NONE, record.executorKind)
        assertTrue(operationId in scheduler.cancelled)
        assertEquals(1, backend.cleanupCancelledCalls)
        assertEquals(
            ModelInstallExecutionOutcome.SUCCESS,
            orchestrator.executeDownload(operationId, oldGeneration),
        )
    }

    @Test
    fun activationWinsRaceAgainstLateCancel() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot())
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)

        backend.installed = true
        backend.confirmedGood = true
        backend.active = true
        orchestrator.cancelOperation(operationId)

        assertEquals(ModelInstallPhase.READY, store.read(operationId)!!.phase)
    }

    @Test
    fun retryableNetworkFailureKeepsDownloadDurable() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot()).apply {
            downloadFailure = IOException("connection reset")
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        val generation = store.read(operationId)!!.executorGeneration

        assertEquals(
            ModelInstallExecutionOutcome.RETRY,
            orchestrator.executeDownload(operationId, generation),
        )

        val record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.DOWNLOAD, record.phase)
        assertEquals("NETWORK", record.lastFailureCode)
        assertEquals(1, record.attempt)
        assertFalse(record.requiresUserResume)
    }

    @Test
    fun lowStorageBecomesRecoverableAndRequiresUserResume() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot()).apply {
            ensureFailure = IllegalStateException("存储空间不足：test")
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        val generation = store.read(operationId)!!.executorGeneration

        assertEquals(
            ModelInstallExecutionOutcome.FAILURE,
            orchestrator.executeDownload(operationId, generation),
        )

        val record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.FAILED_RECOVERABLE, record.phase)
        assertTrue(record.requiresUserResume)
        assertEquals("STORAGE_LOW", record.lastFailureCode)
    }

    @Test
    fun integrityFailureStopsBeforeRuntimeActivation() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot()).apply {
            downloadedBytes = 10L
            packageComplete = true
            verifyFailure = IllegalStateException("model package SHA-256 mismatch")
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        val generation = store.read(operationId)!!.executorGeneration

        assertEquals(
            ModelInstallExecutionOutcome.FAILURE,
            orchestrator.executeFinalize(operationId, generation),
        )

        val record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.FAILED_INTEGRITY, record.phase)
        assertFalse(backend.active)
        assertEquals(1, backend.cleanupCancelledCalls)
    }

    @Test
    fun runtimeValidationFailureKeepsOldActivationUntouched() = runBlocking {
        val store = store()
        val backend = FakeBackend(snapshot()).apply {
            installed = true
            runtimeFailure = IllegalStateException("native smoke failed")
        }
        val scheduler = FakeScheduler()
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)
        orchestrator.reconcileOnStartup()
        val operationId =
            orchestrator.requestInstall("vad", "v2", 2L, ModelInstallOrigin.MANUAL)
        val generation = store.read(operationId)!!.executorGeneration

        assertEquals(
            ModelInstallExecutionOutcome.FAILURE,
            orchestrator.executeFinalize(operationId, generation),
        )

        val record = store.read(operationId)!!
        assertEquals(ModelInstallPhase.FAILED_RUNTIME, record.phase)
        assertFalse(backend.active)
        assertEquals(listOf("runtime-validate"), backend.calls)
    }

    @Test
    fun startupFinishesPersistedUserCancellation() = runBlocking {
        val store = store()
        val candidate = snapshot()
        store.write(
            store.newRecord("op-cancel", candidate, ModelInstallOrigin.MANUAL).copy(
                phase = ModelInstallPhase.DOWNLOAD,
                cancelRequested = true,
                executorKind = ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
                executorGeneration = 8L,
            ),
        )
        val backend = FakeBackend(candidate)
        val scheduler = FakeScheduler().apply { scheduled += "op-cancel" }
        val orchestrator = ModelInstallOrchestrator(backend, store, scheduler)

        orchestrator.reconcileOnStartup()

        val record = store.read("op-cancel")!!
        assertEquals(ModelInstallPhase.CANCELLED, record.phase)
        assertFalse(record.cancelRequested)
        assertTrue("op-cancel" in scheduler.cancelled)
        assertEquals(1, backend.cleanupCancelledCalls)
    }

    private fun store(): ModelInstallJournalStore =
        ModelInstallJournalStore(root.resolve("journal-${System.nanoTime()}"))

    private class FakeScheduler : ModelInstallScheduler {
        val scheduled = linkedSetOf<String>()
        val cancelled = linkedSetOf<String>()

        override fun downloadExecutor(origin: ModelInstallOrigin): ModelInstallExecutorKind =
            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD

        override suspend fun scheduleDownload(record: ModelInstallJournalRecord) {
            scheduled += record.operationId
        }

        override suspend fun scheduleFinalize(record: ModelInstallJournalRecord) {
            scheduled += record.operationId
        }

        override suspend fun cancel(operationId: String) {
            scheduled -= operationId
            cancelled += operationId
        }

        override suspend fun isScheduled(record: ModelInstallJournalRecord): Boolean =
            record.operationId in scheduled
    }

    private class FakeBackend(
        private val frozen: ModelDescriptorSnapshot,
    ) : ModelInstallBackend {
        var downloadedBytes = 0L
        var packageComplete = false
        var installed = false
        var confirmedGood = false
        var active = false
        var ensureFailure: Throwable? = null
        var downloadFailure: Throwable? = null
        var verifyFailure: Throwable? = null
        var extractFailure: Throwable? = null
        var fileVerifyFailure: Throwable? = null
        var runtimeFailure: Throwable? = null
        var activateFailure: Throwable? = null
        var cleanupCancelledCalls = 0
        val calls = mutableListOf<String>()

        override suspend fun freezeCandidate(
            modelId: String,
            version: String,
            revision: Long,
        ): ModelDescriptorSnapshot {
            check(modelId == frozen.descriptor.modelId)
            check(version == frozen.descriptor.version)
            check(revision == frozen.descriptor.revision)
            return frozen
        }

        override suspend fun inspectCandidate(
            snapshot: ModelDescriptorSnapshot,
        ): ModelInstallCandidateInspection =
            ModelInstallCandidateInspection(
                downloadedBytes = downloadedBytes,
                totalBytes = frozen.descriptor.downloadSizeBytes,
                packageComplete = packageComplete,
                installed = installed,
                confirmedGood = confirmedGood,
                active = active,
            )

        override suspend fun ensureInstallSpace(snapshot: ModelDescriptorSnapshot) {
            calls += "ensure-space"
            ensureFailure?.let { throw it }
        }

        override suspend fun downloadPackage(
            snapshot: ModelDescriptorSnapshot,
            progressListener: ModelDownloadProgressListener?,
        ) {
            calls += "download"
            downloadFailure?.let { throw it }
            downloadedBytes = 10L
            packageComplete = true
            progressListener?.onProgress(ModelDownloadProgress(10L, 10L))
        }

        override suspend fun verifyPackage(snapshot: ModelDescriptorSnapshot) {
            calls += "verify-package"
            verifyFailure?.let { throw it }
        }

        override suspend fun extractPackage(snapshot: ModelDescriptorSnapshot) {
            calls += "extract"
            extractFailure?.let { throw it }
        }

        override suspend fun verifyFilesAndPromote(snapshot: ModelDescriptorSnapshot) {
            calls += "verify-files"
            fileVerifyFailure?.let { throw it }
            installed = true
        }

        override suspend fun runtimeValidate(snapshot: ModelDescriptorSnapshot) {
            calls += "runtime-validate"
            runtimeFailure?.let { throw it }
        }

        override suspend fun activate(snapshot: ModelDescriptorSnapshot) {
            calls += "activate"
            activateFailure?.let { throw it }
            installed = true
            confirmedGood = true
            active = true
            packageComplete = false
            downloadedBytes = 0L
        }

        override suspend fun cleanupInterruptedStaging(snapshot: ModelDescriptorSnapshot) {
            calls += "cleanup-staging"
        }

        override suspend fun cleanupUserCancelled(snapshot: ModelDescriptorSnapshot) {
            cleanupCancelledCalls += 1
            if (!active) {
                downloadedBytes = 0L
                packageComplete = false
                if (!confirmedGood) installed = false
            }
        }

        override suspend fun cleanupTransientStorage(
            protection: ModelInstallTransientProtection,
        ): Long = 0L
    }

    private companion object {
        fun snapshot(): ModelDescriptorSnapshot =
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
                        autoUpdateEligible = true,
                    ),
                manifestDigest = "a".repeat(64),
            )
    }
}
