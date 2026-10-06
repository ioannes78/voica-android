package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelDownloadProgress
import io.github.ioannes78.voica.model.ModelDownloadProgressListener
import io.github.ioannes78.voica.model.ModelInstallBackend
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ModelInstallExecutionOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

class ModelInstallUserResumeRequiredException(
    message: String = "模型安装已中断，需要用户继续",
) : IllegalStateException(message)

class ModelInstallOrchestrator(
    private val backend: ModelInstallBackend,
    private val journalStore: ModelInstallJournalStore,
    private val scheduler: ModelInstallScheduler,
) {
    private val mutex = Mutex()
    private val reconciled = CompletableDeferred<Unit>()
    private val mutableOperations =
        MutableStateFlow(latestRecordsByModel(journalStore.readAll()))

    val operations: StateFlow<Map<String, ModelInstallJournalRecord>> =
        mutableOperations.asStateFlow()

    suspend fun reconcileOnStartup() {
        if (reconciled.isCompleted) return
        try {
            journalStore.readAll()
                .filter { !it.phase.terminal }
                .forEach { record ->
                    runCatching {
                        reconcileRecord(record.operationId)
                    }
                }
        } finally {
            if (!reconciled.isCompleted) reconciled.complete(Unit)
        }
    }

    suspend fun requestInstall(
        modelId: String,
        version: String,
        revision: Long,
        origin: ModelInstallOrigin,
    ): String {
        reconciled.await()
        val snapshot = backend.freezeCandidate(modelId, version, revision)
        return mutex.withLock {
            val latest = journalStore.latestForModel(modelId)
            if (latest != null && sameCandidate(latest, snapshot.manifestDigest, version, revision)) {
                if (latest.phase == ModelInstallPhase.READY) {
                    return@withLock latest.operationId
                }
                if (latest.requiresUserResume && origin == ModelInstallOrigin.AUTO_SMALL) {
                    throw ModelInstallUserResumeRequiredException()
                }
                if (latest.phase == ModelInstallPhase.FAILED_RUNTIME ||
                    latest.phase == ModelInstallPhase.INTERRUPTED ||
                    latest.phase == ModelInstallPhase.FAILED_RECOVERABLE ||
                    (!latest.phase.terminal && !scheduler.isScheduled(latest))
                ) {
                    return@withLock resumeLocked(latest, origin).operationId
                }
                if (!latest.phase.terminal) {
                    return@withLock latest.operationId
                }
            }

            val active = journalStore.activeForModel(modelId)
            check(active == null) {
                "another model install is already active for $modelId"
            }

            val inspection = backend.inspectCandidate(snapshot)
            val initialPhase =
                when {
                    inspection.active -> ModelInstallPhase.READY
                    inspection.installed -> ModelInstallPhase.RUNTIME_VALIDATE
                    inspection.packageComplete -> ModelInstallPhase.VERIFY
                    else -> ModelInstallPhase.DOWNLOAD
                }
            val operationId =
                buildString {
                    append(modelId)
                    append('-')
                    append(revision)
                    append('-')
                    append(UUID.randomUUID().toString())
                }
            var record =
                journalStore.newRecord(
                    operationId = operationId,
                    snapshot = snapshot,
                    origin = origin,
                ).copy(
                    phase = initialPhase,
                    downloadedBytes = inspection.downloadedBytes,
                    totalBytes = inspection.totalBytes,
                )
            record = persist(record)
            if (record.phase != ModelInstallPhase.READY) {
                scheduleLocked(record)
            }
            operationId
        }
    }

    suspend fun cancelModel(modelId: String) {
        reconciled.await()
        val operationId =
            mutex.withLock {
                journalStore.activeForModel(modelId)?.operationId
                    ?: journalStore.latestForModel(modelId)
                        ?.takeIf { it.phase != ModelInstallPhase.READY }
                        ?.operationId
            } ?: return
        cancelOperation(operationId)
    }

    suspend fun cancelOperation(operationId: String) {
        reconciled.await()
        val cancelledGeneration =
            mutex.withLock {
                val current = journalStore.read(operationId) ?: return
                if (current.phase == ModelInstallPhase.READY ||
                    current.phase == ModelInstallPhase.CANCELLED
                ) {
                    return
                }
                val next =
                    persist(
                        current.copy(
                            cancelRequested = true,
                            executorGeneration = current.executorGeneration + 1L,
                            executorKind = ModelInstallExecutorKind.NONE,
                        ),
                    )
                next.executorGeneration
            }

        scheduler.cancel(operationId)
        val current = journalStore.read(operationId) ?: return
        runCatching { backend.cleanupUserCancelled(current.snapshot) }
        val inspection = runCatching { backend.inspectCandidate(current.snapshot) }.getOrNull()

        mutex.withLock {
            val latest = journalStore.read(operationId) ?: return@withLock
            if (latest.executorGeneration != cancelledGeneration) return@withLock
            persist(
                latest.copy(
                    phase =
                        if (inspection?.active == true) {
                            ModelInstallPhase.READY
                        } else {
                            ModelInstallPhase.CANCELLED
                        },
                    cancelRequested = false,
                    requiresUserResume = false,
                    executorKind = ModelInstallExecutorKind.NONE,
                    lastFailureCode = null,
                    lastFailureMessage = null,
                ),
            )
        }
    }

    suspend fun executeDownload(
        operationId: String,
        generation: Long,
    ): ModelInstallExecutionOutcome {
        reconciled.await()
        val start = currentExecution(operationId, generation, ModelInstallPhase.DOWNLOAD)
            ?: return ModelInstallExecutionOutcome.SUCCESS
        var lastPersistedBytes = start.downloadedBytes

        return try {
            backend.ensureInstallSpace(start.snapshot)
            backend.downloadPackage(
                snapshot = start.snapshot,
                progressListener =
                    ModelDownloadProgressListener { progress ->
                        val complete =
                            progress.totalBytes != null &&
                                progress.downloadedBytes >= progress.totalBytes
                        if (complete ||
                            progress.downloadedBytes - lastPersistedBytes >= PROGRESS_PERSIST_STEP_BYTES
                        ) {
                            persistDownloadProgress(
                                operationId = operationId,
                                generation = generation,
                                progress = progress,
                            )
                            lastPersistedBytes = progress.downloadedBytes
                        }
                    },
            )

            mutex.withLock {
                val current = journalStore.read(operationId)
                    ?.takeIf {
                        it.executorGeneration == generation &&
                            it.phase == ModelInstallPhase.DOWNLOAD &&
                            !it.cancelRequested
                    }
                    ?: return@withLock
                val completed =
                    persist(
                        current.copy(
                            phase = ModelInstallPhase.VERIFY,
                            downloadedBytes = current.totalBytes ?: current.downloadedBytes,
                            executorKind = ModelInstallExecutorKind.NONE,
                            lastFailureCode = null,
                            lastFailureMessage = null,
                        ),
                    )
                scheduleLocked(completed)
            }
            ModelInstallExecutionOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            handleExecutionFailure(
                operationId = operationId,
                generation = generation,
                phase = ModelInstallPhase.DOWNLOAD,
                error = error,
            )
        }
    }

    suspend fun executeFinalize(
        operationId: String,
        generation: Long,
    ): ModelInstallExecutionOutcome {
        reconciled.await()
        var record = journalStore.read(operationId)
            ?.takeIf { it.executorGeneration == generation }
            ?: return ModelInstallExecutionOutcome.SUCCESS

        val initialInspection = runCatching { backend.inspectCandidate(record.snapshot) }.getOrNull()
        if (initialInspection?.active == true) {
            mutex.withLock {
                val latest = journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
                    ?: return@withLock
                persist(
                    latest.copy(
                        phase = ModelInstallPhase.READY,
                        executorKind = ModelInstallExecutorKind.NONE,
                        cancelRequested = false,
                        requiresUserResume = false,
                        lastFailureCode = null,
                        lastFailureMessage = null,
                    ),
                )
            }
            return ModelInstallExecutionOutcome.SUCCESS
        }

        while (true) {
            record = journalStore.read(operationId)
                ?.takeIf { it.executorGeneration == generation }
                ?: return ModelInstallExecutionOutcome.SUCCESS
            if (record.cancelRequested) return ModelInstallExecutionOutcome.SUCCESS

            val phase = record.phase
            try {
                when (phase) {
                    ModelInstallPhase.VERIFY -> {
                        backend.verifyPackage(record.snapshot)
                        advance(operationId, generation, ModelInstallPhase.EXTRACT)
                    }

                    ModelInstallPhase.EXTRACT -> {
                        backend.cleanupInterruptedStaging(record.snapshot)
                        backend.extractPackage(record.snapshot)
                        advance(operationId, generation, ModelInstallPhase.FILE_VERIFY)
                    }

                    ModelInstallPhase.FILE_VERIFY -> {
                        backend.verifyFilesAndPromote(record.snapshot)
                        advance(operationId, generation, ModelInstallPhase.RUNTIME_VALIDATE)
                    }

                    ModelInstallPhase.RUNTIME_VALIDATE -> {
                        backend.runtimeValidate(record.snapshot)
                        advance(operationId, generation, ModelInstallPhase.ATOMIC_ACTIVATE)
                    }

                    ModelInstallPhase.ATOMIC_ACTIVATE -> {
                        backend.activate(record.snapshot)
                        val inspection = backend.inspectCandidate(record.snapshot)
                        check(inspection.active) {
                            "activated model candidate was not observed as confirmed-good active"
                        }
                        mutex.withLock {
                            val latest = journalStore.read(operationId)
                                ?.takeIf { it.executorGeneration == generation }
                                ?: return@withLock
                            persist(
                                latest.copy(
                                    phase = ModelInstallPhase.READY,
                                    executorKind = ModelInstallExecutorKind.NONE,
                                    cancelRequested = false,
                                    requiresUserResume = false,
                                    lastFailureCode = null,
                                    lastFailureMessage = null,
                                ),
                            )
                        }
                        return ModelInstallExecutionOutcome.SUCCESS
                    }

                    ModelInstallPhase.READY,
                    ModelInstallPhase.CANCELLED,
                    ModelInstallPhase.FAILED_INTEGRITY,
                    ModelInstallPhase.FAILED_CONFIGURATION,
                    ModelInstallPhase.FAILED_RUNTIME,
                    -> return ModelInstallExecutionOutcome.SUCCESS

                    ModelInstallPhase.INTERRUPTED,
                    ModelInstallPhase.FAILED_RECOVERABLE,
                    ModelInstallPhase.REQUESTED,
                    ModelInstallPhase.DOWNLOAD,
                    -> return ModelInstallExecutionOutcome.FAILURE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return handleExecutionFailure(
                    operationId = operationId,
                    generation = generation,
                    phase = phase,
                    error = error,
                )
            }
        }
    }

    suspend fun awaitCandidateInstalled(operationId: String) {
        val initial = journalStore.read(operationId)
            ?: error("model install operation not found")
        val record =
            operations
                .map { it[initial.modelId] }
                .filterNotNull()
                .first { current ->
                    current.operationId == operationId &&
                        (
                            current.phase == ModelInstallPhase.RUNTIME_VALIDATE ||
                                current.phase == ModelInstallPhase.ATOMIC_ACTIVATE ||
                                current.phase == ModelInstallPhase.READY ||
                                current.phase == ModelInstallPhase.FAILED_RUNTIME ||
                                current.phase == ModelInstallPhase.FAILED_INTEGRITY ||
                                current.phase == ModelInstallPhase.FAILED_CONFIGURATION ||
                                current.phase == ModelInstallPhase.CANCELLED ||
                                current.requiresUserResume
                            )
                }
        when (record.phase) {
            ModelInstallPhase.RUNTIME_VALIDATE,
            ModelInstallPhase.ATOMIC_ACTIVATE,
            ModelInstallPhase.READY,
            ModelInstallPhase.FAILED_RUNTIME,
            -> return

            else -> throw record.asFailure()
        }
    }

    suspend fun awaitReady(operationId: String) {
        val initial = journalStore.read(operationId)
            ?: error("model install operation not found")
        val record =
            operations
                .map { it[initial.modelId] }
                .filterNotNull()
                .first { current ->
                    current.operationId == operationId &&
                        (
                            current.phase == ModelInstallPhase.READY ||
                                current.phase == ModelInstallPhase.FAILED_RUNTIME ||
                                current.phase == ModelInstallPhase.FAILED_INTEGRITY ||
                                current.phase == ModelInstallPhase.FAILED_CONFIGURATION ||
                                current.phase == ModelInstallPhase.CANCELLED ||
                                current.requiresUserResume
                            )
                }
        if (record.phase != ModelInstallPhase.READY) throw record.asFailure()
    }

    private suspend fun reconcileRecord(operationId: String) {
        mutex.withLock {
            val current = journalStore.read(operationId) ?: return@withLock
            if (current.phase.terminal) return@withLock
            val inspection = backend.inspectCandidate(current.snapshot)
            if (inspection.active) {
                persist(
                    current.copy(
                        phase = ModelInstallPhase.READY,
                        executorKind = ModelInstallExecutorKind.NONE,
                        cancelRequested = false,
                        requiresUserResume = false,
                    ),
                )
                return@withLock
            }
            if (current.cancelRequested) {
                return@withLock
            }
            if (current.requiresUserResume ||
                current.phase == ModelInstallPhase.FAILED_RECOVERABLE ||
                current.phase == ModelInstallPhase.INTERRUPTED
            ) {
                persist(current.copy(executorKind = ModelInstallExecutorKind.NONE))
                return@withLock
            }
            if (scheduler.isScheduled(current)) return@withLock
            persist(
                current.copy(
                    phase = ModelInstallPhase.INTERRUPTED,
                    requiresUserResume = true,
                    executorKind = ModelInstallExecutorKind.NONE,
                    lastFailureCode = "EXECUTOR_MISSING",
                    lastFailureMessage = "安装执行器已中断，请手动继续",
                ),
            )
        }
    }

    private suspend fun resumeLocked(
        record: ModelInstallJournalRecord,
        requestedOrigin: ModelInstallOrigin,
    ): ModelInstallJournalRecord {
        val inspection = backend.inspectCandidate(record.snapshot)
        val phase =
            when {
                inspection.active -> ModelInstallPhase.READY
                inspection.installed -> ModelInstallPhase.RUNTIME_VALIDATE
                inspection.packageComplete -> ModelInstallPhase.VERIFY
                else -> ModelInstallPhase.DOWNLOAD
            }
        val resumed =
            persist(
                record.copy(
                    origin =
                        if (requestedOrigin == ModelInstallOrigin.MANUAL) {
                            ModelInstallOrigin.MANUAL
                        } else {
                            record.origin
                        },
                    phase = phase,
                    cancelRequested = false,
                    requiresUserResume = false,
                    attempt = record.attempt + 1,
                    downloadedBytes = inspection.downloadedBytes,
                    totalBytes = inspection.totalBytes,
                    executorKind = ModelInstallExecutorKind.NONE,
                    executorGeneration = record.executorGeneration + 1L,
                    lastFailureCode = null,
                    lastFailureMessage = null,
                ),
            )
        if (resumed.phase != ModelInstallPhase.READY) {
            scheduleLocked(resumed)
        }
        return resumed
    }

    private suspend fun scheduleLocked(record: ModelInstallJournalRecord) {
        val executor =
            when (record.phase) {
                ModelInstallPhase.DOWNLOAD -> scheduler.downloadExecutor(record.origin)
                ModelInstallPhase.VERIFY,
                ModelInstallPhase.EXTRACT,
                ModelInstallPhase.FILE_VERIFY,
                ModelInstallPhase.RUNTIME_VALIDATE,
                ModelInstallPhase.ATOMIC_ACTIVATE,
                -> ModelInstallExecutorKind.WORK_MANAGER_FINALIZE

                else -> ModelInstallExecutorKind.NONE
            }
        if (executor == ModelInstallExecutorKind.NONE) return
        val scheduled = persist(record.copy(executorKind = executor))
        try {
            if (executor == ModelInstallExecutorKind.WORK_MANAGER_FINALIZE) {
                scheduler.scheduleFinalize(scheduled)
            } else {
                scheduler.scheduleDownload(scheduled)
            }
        } catch (error: Throwable) {
            persist(
                scheduled.copy(
                    phase = ModelInstallPhase.INTERRUPTED,
                    requiresUserResume = true,
                    executorKind = ModelInstallExecutorKind.NONE,
                    lastFailureCode = "SCHEDULE_FAILED",
                    lastFailureMessage = error.message ?: error::class.java.simpleName,
                ),
            )
        }
    }

    private suspend fun advance(
        operationId: String,
        generation: Long,
        nextPhase: ModelInstallPhase,
    ) {
        mutex.withLock {
            val current = journalStore.read(operationId)
                ?.takeIf {
                    it.executorGeneration == generation &&
                        !it.cancelRequested
                }
                ?: return@withLock
            persist(
                current.copy(
                    phase = nextPhase,
                    executorKind = ModelInstallExecutorKind.WORK_MANAGER_FINALIZE,
                    lastFailureCode = null,
                    lastFailureMessage = null,
                ),
            )
        }
    }

    private suspend fun currentExecution(
        operationId: String,
        generation: Long,
        phase: ModelInstallPhase,
    ): ModelInstallJournalRecord? =
        mutex.withLock {
            journalStore.read(operationId)
                ?.takeIf {
                    it.executorGeneration == generation &&
                        it.phase == phase &&
                        !it.cancelRequested
                }
        }

    private suspend fun persistDownloadProgress(
        operationId: String,
        generation: Long,
        progress: ModelDownloadProgress,
    ) {
        mutex.withLock {
            val current = journalStore.read(operationId)
                ?.takeIf {
                    it.executorGeneration == generation &&
                        it.phase == ModelInstallPhase.DOWNLOAD &&
                        !it.cancelRequested
                }
                ?: return@withLock
            persist(
                current.copy(
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes ?: current.totalBytes,
                ),
            )
        }
    }

    private suspend fun handleExecutionFailure(
        operationId: String,
        generation: Long,
        phase: ModelInstallPhase,
        error: Throwable,
    ): ModelInstallExecutionOutcome {
        val current =
            mutex.withLock {
                journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
            } ?: return ModelInstallExecutionOutcome.SUCCESS

        if (isStorageFailure(error)) {
            mutex.withLock {
                val latest = journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
                    ?: return@withLock
                persist(
                    latest.copy(
                        phase = ModelInstallPhase.FAILED_RECOVERABLE,
                        requiresUserResume = true,
                        executorKind = ModelInstallExecutorKind.NONE,
                        lastFailureCode = "STORAGE_LOW",
                        lastFailureMessage = error.message ?: "存储空间不足",
                    ),
                )
            }
            return ModelInstallExecutionOutcome.FAILURE
        }

        if (phase == ModelInstallPhase.RUNTIME_VALIDATE) {
            mutex.withLock {
                val latest = journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
                    ?: return@withLock
                persist(
                    latest.copy(
                        phase = ModelInstallPhase.FAILED_RUNTIME,
                        executorKind = ModelInstallExecutorKind.NONE,
                        lastFailureCode = "RUNTIME_VALIDATE",
                        lastFailureMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
            }
            return ModelInstallExecutionOutcome.FAILURE
        }

        if (isIntegrityFailure(error)) {
            runCatching { backend.cleanupUserCancelled(current.snapshot) }
            mutex.withLock {
                val latest = journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
                    ?: return@withLock
                persist(
                    latest.copy(
                        phase = ModelInstallPhase.FAILED_INTEGRITY,
                        executorKind = ModelInstallExecutorKind.NONE,
                        lastFailureCode = "INTEGRITY",
                        lastFailureMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
            }
            return ModelInstallExecutionOutcome.FAILURE
        }

        if (phase == ModelInstallPhase.DOWNLOAD && isRetryableNetworkFailure(error)) {
            mutex.withLock {
                val latest = journalStore.read(operationId)
                    ?.takeIf { it.executorGeneration == generation }
                    ?: return@withLock
                persist(
                    latest.copy(
                        attempt = latest.attempt + 1,
                        lastFailureCode = "NETWORK",
                        lastFailureMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
            }
            return ModelInstallExecutionOutcome.RETRY
        }

        mutex.withLock {
            val latest = journalStore.read(operationId)
                ?.takeIf { it.executorGeneration == generation }
                ?: return@withLock
            persist(
                latest.copy(
                    phase =
                        if (phase == ModelInstallPhase.ATOMIC_ACTIVATE) {
                            ModelInstallPhase.FAILED_RECOVERABLE
                        } else {
                            ModelInstallPhase.FAILED_CONFIGURATION
                        },
                    requiresUserResume = phase == ModelInstallPhase.ATOMIC_ACTIVATE,
                    executorKind = ModelInstallExecutorKind.NONE,
                    lastFailureCode = "EXECUTION",
                    lastFailureMessage = error.message ?: error::class.java.simpleName,
                ),
            )
        }
        return ModelInstallExecutionOutcome.FAILURE
    }

    private fun persist(record: ModelInstallJournalRecord): ModelInstallJournalRecord {
        val written = journalStore.write(record)
        mutableOperations.update { current -> current + (written.modelId to written) }
        return written
    }

    private fun sameCandidate(
        record: ModelInstallJournalRecord,
        manifestDigest: String,
        version: String,
        revision: Long,
    ): Boolean =
        record.snapshot.manifestDigest == manifestDigest &&
            record.snapshot.descriptor.version == version &&
            record.snapshot.descriptor.revision == revision

    private fun ModelInstallJournalRecord.asFailure(): Throwable =
        if (requiresUserResume ||
            phase == ModelInstallPhase.INTERRUPTED ||
            phase == ModelInstallPhase.FAILED_RECOVERABLE
        ) {
            ModelInstallUserResumeRequiredException(
                lastFailureMessage ?: "模型安装已中断，需要用户继续",
            )
        } else {
            IllegalStateException(
                lastFailureMessage ?: "模型安装失败：${phase.name}",
            )
        }

    private fun isStorageFailure(error: Throwable): Boolean =
        "存储空间不足" in error.message.orEmpty()

    private fun isIntegrityFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "sha-256" in message ||
            "sha256" in message ||
            "size mismatch" in message ||
            "verification" in message ||
            "archive entry" in message ||
            "extracted model files" in message
    }

    private fun isRetryableNetworkFailure(error: Throwable): Boolean {
        if (error is IOException) return true
        val message = error.message.orEmpty().lowercase()
        val httpCode = Regex("http\\s+(\\d{3})").find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (httpCode != null) return httpCode >= 500
        return "timeout" in message ||
            "timed out" in message ||
            "connection" in message ||
            "network" in message ||
            "content-range" in message
    }

    private companion object {
        const val PROGRESS_PERSIST_STEP_BYTES = 4L * 1024L * 1024L

        fun latestRecordsByModel(
            records: List<ModelInstallJournalRecord>,
        ): Map<String, ModelInstallJournalRecord> =
            records
                .groupBy { it.modelId }
                .mapValues { (_, candidates) -> candidates.maxBy { it.updatedAtMs } }
    }
}
