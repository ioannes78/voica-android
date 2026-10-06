package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelInstallBackend
import io.github.ioannes78.voica.model.ModelInstallCandidateInspection
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface DurableModelInstallController {
    val installOperations: StateFlow<Map<String, ModelInstallJournalRecord>>

    suspend fun installAndActivate(
        modelId: String,
        version: String,
        revision: Long,
        origin: ModelInstallOrigin,
    )
}

internal data class ModelInstallRuntimeHolder(
    val manager: ModelManager,
    val orchestrator: ModelInstallOrchestrator,
    val journalStore: ModelInstallJournalStore,
)

object ModelInstallRuntime {
    internal val processScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var holder: ModelInstallRuntimeHolder? = null

    internal fun register(
        manager: ModelManager,
        orchestrator: ModelInstallOrchestrator,
        journalStore: ModelInstallJournalStore,
        scheduler: ModelInstallScheduler,
        startupInterruptedOperationIds: List<String> = emptyList(),
    ) {
        holder =
            ModelInstallRuntimeHolder(
                manager = manager,
                orchestrator = orchestrator,
                journalStore = journalStore,
            )
        processScope.launch(Dispatchers.IO) {
            startupInterruptedOperationIds.forEach { operationId ->
                runCatching { scheduler.cancel(operationId) }
            }
            orchestrator.reconcileOnStartup()
        }
    }

    internal fun requireHolder(): ModelInstallRuntimeHolder =
        checkNotNull(holder) { "durable model install runtime is not initialized" }
}

class DurableAwareModelManager(
    private val delegate: ModelManager,
    private val orchestrator: ModelInstallOrchestrator,
    private val backend: ModelInstallBackend,
    private val journalStore: ModelInstallJournalStore,
    private val scheduler: ModelInstallScheduler,
    scope: CoroutineScope,
) : ModelManager by delegate, DurableModelInstallController {
    private val localOperationOverrides =
        MutableStateFlow<Map<String, ModelInstallJournalRecord>>(emptyMap())

    override val installOperations: StateFlow<Map<String, ModelInstallJournalRecord>> =
        combine(orchestrator.operations, localOperationOverrides) { durable, local ->
            durable + local
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = orchestrator.operations.value,
        )

    private val durableOperations =
        installOperations.map(::toLegacyOperationMap)

    override val operations: StateFlow<Map<String, ModelOperationStatus>> =
        combine(delegate.operations, durableOperations) { legacy, durable ->
            legacy + durable
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = delegate.operations.value + toLegacyOperationMap(installOperations.value),
        )

    override suspend fun install(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val operationId =
            requestDurableInstall(
                modelId = modelId,
                version = version,
                revision = revision,
                origin = ModelInstallOrigin.MANUAL,
            )
        orchestrator.awaitCandidateInstalled(operationId)
    }

    override suspend fun confirmInstalledVersion(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        installAndActivate(
            modelId = modelId,
            version = version,
            revision = revision,
            origin = ModelInstallOrigin.MANUAL,
        )
    }

    override suspend fun installAndActivate(
        modelId: String,
        version: String,
        revision: Long,
        origin: ModelInstallOrigin,
    ) {
        val operationId =
            try {
                requestDurableInstall(
                    modelId = modelId,
                    version = version,
                    revision = revision,
                    origin = origin,
                )
            } catch (error: IllegalStateException) {
                if (origin == ModelInstallOrigin.AUTO_SMALL &&
                    error.message.orEmpty().contains("another model install is already active")
                ) {
                    throw ModelInstallUserResumeRequiredException(
                        "已有未完成的模型安装，需要用户处理后再自动更新",
                    )
                }
                throw error
            }
        orchestrator.awaitReady(operationId)
    }

    override suspend fun cancelInstall(modelId: String) {
        val current = journalStore.activeForModel(modelId)
        if (current == null) {
            clearLocalOverride(modelId)
            orchestrator.cancelModel(modelId)
            return
        }

        if (current.phase == ModelInstallPhase.INTERRUPTED ||
            current.phase == ModelInstallPhase.FAILED_RECOVERABLE
        ) {
            clearLocalOverride(modelId)
            orchestrator.cancelModel(modelId)
            return
        }

        val inspection = runCatching { backend.inspectCandidate(current.snapshot) }.getOrNull()
        if (!shouldPauseInsteadOfCancel(current, inspection)) {
            clearLocalOverride(modelId)
            orchestrator.cancelModel(modelId)
            return
        }

        pausePostDownloadInstall(current, inspection)
    }

    override suspend fun removeDownloadedVersion(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        check(journalStore.activeForModel(modelId) == null) {
            "model install is active; cancel it before deleting a downloaded version"
        }
        delegate.removeDownloadedVersion(modelId, version, revision)
        clearLocalOverride(modelId)
    }

    override suspend fun rollback(modelId: String) {
        check(journalStore.activeForModel(modelId) == null) {
            "model install is active; cancel it before rollback"
        }
        delegate.rollback(modelId)
        clearLocalOverride(modelId)
    }

    override suspend fun cleanupTransientStorage(): Long =
        backend.cleanupTransientStorage(journalStore.transientProtection())

    private suspend fun requestDurableInstall(
        modelId: String,
        version: String,
        revision: Long,
        origin: ModelInstallOrigin,
    ): String {
        supersedeStaleHistoricalReady(
            modelId = modelId,
            version = version,
            revision = revision,
        )
        return try {
            orchestrator.requestInstall(
                modelId = modelId,
                version = version,
                revision = revision,
                origin = origin,
            )
        } finally {
            clearLocalOverride(modelId)
        }
    }

    private suspend fun supersedeStaleHistoricalReady(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val latest = journalStore.latestForModel(modelId) ?: return
        val inspection = backend.inspectCandidate(latest.snapshot)
        if (!shouldSupersedeHistoricalReady(latest, inspection, version, revision)) return

        val tombstone =
            journalStore.write(
                journalStore.newRecord(
                    operationId = "supersede-${UUID.randomUUID()}",
                    snapshot = latest.snapshot,
                    origin = ModelInstallOrigin.MANUAL,
                ).copy(
                    phase = ModelInstallPhase.CANCELLED,
                    lastFailureCode = "MODEL_REMOVED",
                    lastFailureMessage = "模型已从本机删除，可重新下载安装",
                ),
            )
        publishLocalOverride(tombstone)
    }

    private suspend fun pausePostDownloadInstall(
        current: ModelInstallJournalRecord,
        initialInspection: ModelInstallCandidateInspection?,
    ) {
        val alreadyActive = initialInspection?.active == true
        val invalidated =
            journalStore.write(
                current.copy(
                    phase = if (alreadyActive) ModelInstallPhase.READY else ModelInstallPhase.INTERRUPTED,
                    cancelRequested = false,
                    requiresUserResume = !alreadyActive,
                    executorKind = ModelInstallExecutorKind.NONE,
                    executorGeneration = current.executorGeneration + 1L,
                    lastFailureCode = if (alreadyActive) null else "USER_PAUSED",
                    lastFailureMessage = if (alreadyActive) null else "安装已暂停，可继续安装",
                ),
            )
        publishLocalOverride(invalidated)

        runCatching { scheduler.cancel(current.operationId) }

        if (!alreadyActive) {
            val afterCancel = runCatching { backend.inspectCandidate(current.snapshot) }.getOrNull()
            if (afterCancel?.active == true) {
                val ready =
                    journalStore.write(
                        invalidated.copy(
                            phase = ModelInstallPhase.READY,
                            requiresUserResume = false,
                            lastFailureCode = null,
                            lastFailureMessage = null,
                        ),
                    )
                publishLocalOverride(ready)
            }
        }
    }

    private fun publishLocalOverride(record: ModelInstallJournalRecord) {
        localOperationOverrides.update { current -> current + (record.modelId to record) }
    }

    private fun clearLocalOverride(modelId: String) {
        localOperationOverrides.update { current -> current - modelId }
    }

    private fun toLegacyOperationMap(
        records: Map<String, ModelInstallJournalRecord>,
    ): Map<String, ModelOperationStatus> =
        records.mapNotNull { (modelId, record) ->
            record.toLegacyStatus()?.let { modelId to it }
        }.toMap()

    private fun ModelInstallJournalRecord.toLegacyStatus(): ModelOperationStatus? {
        val state =
            when (phase) {
                ModelInstallPhase.REQUESTED,
                ModelInstallPhase.DOWNLOAD -> ModelState.DOWNLOADING

                ModelInstallPhase.VERIFY,
                ModelInstallPhase.EXTRACT,
                ModelInstallPhase.FILE_VERIFY,
                ModelInstallPhase.RUNTIME_VALIDATE,
                ModelInstallPhase.ATOMIC_ACTIVATE -> ModelState.VERIFYING

                ModelInstallPhase.FAILED_INTEGRITY -> ModelState.CORRUPTED
                ModelInstallPhase.INTERRUPTED,
                ModelInstallPhase.FAILED_RECOVERABLE,
                ModelInstallPhase.FAILED_RUNTIME,
                ModelInstallPhase.FAILED_CONFIGURATION -> ModelState.LOAD_FAILED

                ModelInstallPhase.READY,
                ModelInstallPhase.CANCELLED -> return null
            }
        return ModelOperationStatus(
            state = state,
            downloadedBytes = downloadedBytes.takeIf { totalBytes != null },
            totalBytes = totalBytes,
            errorMessage =
                modelInstallRecordUserMessage(this)
                    ?: if (phase == ModelInstallPhase.INTERRUPTED) {
                        "安装已中断，可重新点击继续"
                    } else {
                        null
                    },
        )
    }
}

internal fun shouldPauseInsteadOfCancel(
    record: ModelInstallJournalRecord,
    inspection: ModelInstallCandidateInspection?,
): Boolean {
    if (record.phase == ModelInstallPhase.INTERRUPTED ||
        record.phase == ModelInstallPhase.FAILED_RECOVERABLE ||
        record.phase.terminal
    ) {
        return false
    }
    if (inspection?.active == true ||
        inspection?.installed == true ||
        inspection?.packageComplete == true
    ) {
        return true
    }
    if (record.phase == ModelInstallPhase.DOWNLOAD ||
        record.phase == ModelInstallPhase.REQUESTED
    ) {
        val total = record.totalBytes
        return total != null && total > 0L && record.downloadedBytes >= total
    }
    return record.phase == ModelInstallPhase.VERIFY ||
        record.phase == ModelInstallPhase.EXTRACT ||
        record.phase == ModelInstallPhase.FILE_VERIFY ||
        record.phase == ModelInstallPhase.RUNTIME_VALIDATE ||
        record.phase == ModelInstallPhase.ATOMIC_ACTIVATE
}

internal fun shouldSupersedeHistoricalReady(
    record: ModelInstallJournalRecord,
    inspection: ModelInstallCandidateInspection,
    requestedVersion: String,
    requestedRevision: Long,
): Boolean =
    record.phase == ModelInstallPhase.READY &&
        record.snapshot.descriptor.version == requestedVersion &&
        record.snapshot.descriptor.revision == requestedRevision &&
        !inspection.active
