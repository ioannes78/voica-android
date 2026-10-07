package io.github.ioannes78.voica

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ModelInstallNotificationController(
    private val application: Application,
    private val controller: DurableModelInstallController,
) {
    private val taskOwnershipStore = ModelInstallTaskOwnershipStore(application)

    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Default) {
            coroutineScope {
                launch {
                    var knownOperationIds = emptySet<String>()
                    controller.installOperations.collectLatest { operations ->
                        val manualRecords =
                            operations.values
                                .filter { it.origin == ModelInstallOrigin.MANUAL }
                        val currentIds = manualRecords.mapTo(linkedSetOf()) { it.operationId }

                        (knownOperationIds - currentIds).forEach { operationId ->
                            ModelInstallNotifications.cancel(application, operationId)
                            taskOwnershipStore.clear(operationId)
                        }
                        manualRecords.forEach { record ->
                            ModelInstallNotifications.sync(application, record)
                            if (record.phase.terminal) {
                                taskOwnershipStore.clear(record.operationId)
                            } else {
                                taskOwnershipStore.bindIfCurrentTaskActive(record)
                            }
                        }
                        knownOperationIds = currentIds
                    }
                }

                launch {
                    while (isActive) {
                        val activeTaskIds = currentAppTaskIds(application)
                        if (activeTaskIds != null) {
                            controller.installOperations.value.values
                                .filter { record ->
                                    record.origin == ModelInstallOrigin.MANUAL &&
                                        !record.phase.terminal &&
                                        !record.requiresUserResume &&
                                        record.executorKind != ModelInstallExecutorKind.NONE
                                }
                                .forEach { record ->
                                    val owner = taskOwnershipStore.ownerFor(record.operationId)
                                    if (
                                        shouldInterruptForMissingOwnerTask(
                                            owner = owner,
                                            record = record,
                                            activeTaskIds = activeTaskIds,
                                        )
                                    ) {
                                        runCatching {
                                            controller.interruptForTaskRemoval(record.operationId)
                                        }
                                        taskOwnershipStore.clear(record.operationId)
                                    }
                                }
                        }
                        delay(TASK_PRESENCE_POLL_MS)
                    }
                }
            }
        }

    private companion object {
        const val TASK_PRESENCE_POLL_MS = 1_000L
    }
}
