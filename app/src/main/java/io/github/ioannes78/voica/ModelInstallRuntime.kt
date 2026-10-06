package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelInstallBackend
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SharingStarted
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface DurableModelInstallController {
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
    ) {
        holder =
            ModelInstallRuntimeHolder(
                manager = manager,
                orchestrator = orchestrator,
                journalStore = journalStore,
            )
        processScope.launch(Dispatchers.IO) {
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
    scope: CoroutineScope,
) : ModelManager by delegate, DurableModelInstallController {
    private val durableOperations =
        orchestrator.operations.map(::toLegacyOperationMap)

    override val operations: StateFlow<Map<String, ModelOperationStatus>> =
        combine(delegate.operations, durableOperations) { legacy, durable ->
            legacy + durable
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = delegate.operations.value + toLegacyOperationMap(orchestrator.operations.value),
        )

    override suspend fun install(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val operationId =
            orchestrator.requestInstall(
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
            orchestrator.requestInstall(
                modelId = modelId,
                version = version,
                revision = revision,
                origin = origin,
            )
        orchestrator.awaitReady(operationId)
    }

    override suspend fun cancelInstall(modelId: String) {
        orchestrator.cancelModel(modelId)
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
    }

    override suspend fun rollback(modelId: String) {
        check(journalStore.activeForModel(modelId) == null) {
            "model install is active; cancel it before rollback"
        }
        delegate.rollback(modelId)
    }

    override suspend fun cleanupTransientStorage(): Long =
        backend.cleanupTransientStorage(journalStore.transientProtection())

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
                ModelInstallPhase.DOWNLOAD,
                -> ModelState.DOWNLOADING

                ModelInstallPhase.VERIFY,
                ModelInstallPhase.EXTRACT,
                ModelInstallPhase.FILE_VERIFY,
                ModelInstallPhase.RUNTIME_VALIDATE,
                ModelInstallPhase.ATOMIC_ACTIVATE,
                -> ModelState.VERIFYING

                ModelInstallPhase.FAILED_INTEGRITY -> ModelState.CORRUPTED
                ModelInstallPhase.INTERRUPTED,
                ModelInstallPhase.FAILED_RECOVERABLE,
                ModelInstallPhase.FAILED_RUNTIME,
                ModelInstallPhase.FAILED_CONFIGURATION,
                -> ModelState.LOAD_FAILED

                ModelInstallPhase.READY,
                ModelInstallPhase.CANCELLED,
                -> return null
            }
        return ModelOperationStatus(
            state = state,
            downloadedBytes = downloadedBytes.takeIf { totalBytes != null },
            totalBytes = totalBytes,
            errorMessage =
                lastFailureMessage
                    ?: if (phase == ModelInstallPhase.INTERRUPTED) {
                        "安装已中断，可重新点击继续"
                    } else {
                        null
                    },
        )
    }
}
