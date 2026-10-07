package io.github.ioannes78.voica

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class ModelInstallNotificationController(
    private val application: Application,
    private val controller: DurableModelInstallController,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Default) {
            var knownOperationIds = emptySet<String>()
            var guardRunning = false
            controller.installOperations.collectLatest { operations ->
                val manualRecords =
                    operations.values
                        .filter { it.origin == ModelInstallOrigin.MANUAL }
                val currentIds = manualRecords.mapTo(linkedSetOf()) { it.operationId }

                (knownOperationIds - currentIds).forEach { operationId ->
                    ModelInstallNotifications.cancel(application, operationId)
                }
                manualRecords.forEach { record ->
                    ModelInstallNotifications.sync(application, record)
                }
                knownOperationIds = currentIds

                val shouldRunGuard =
                    manualRecords.any { record ->
                        !record.phase.terminal &&
                            !record.requiresUserResume &&
                            record.executorKind != ModelInstallExecutorKind.NONE
                    }
                if (shouldRunGuard && !guardRunning) {
                    val started =
                        runCatching {
                            application.startService(
                                Intent(application, ModelInstallTaskRemovalGuardService::class.java),
                            )
                        }.isSuccess
                    guardRunning = started
                } else if (!shouldRunGuard && guardRunning) {
                    runCatching {
                        application.stopService(
                            Intent(application, ModelInstallTaskRemovalGuardService::class.java),
                        )
                    }
                    guardRunning = false
                }
            }
        }
}

/**
 * Narrow lifecycle guard for recent-task removal. It is not an executor and owns no
 * model state. The durable journal remains truth; this service only records the explicit
 * user gesture before OEM process teardown and asks the durable controller to pause.
 */
class ModelInstallTaskRemovalGuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        val runtime = runCatching { ModelInstallRuntime.requireHolder() }.getOrNull()
        if (runtime != null) {
            val records =
                runtime.journalStore.readAll()
                    .filter { record ->
                        record.origin == ModelInstallOrigin.MANUAL &&
                            !record.phase.terminal &&
                            !record.requiresUserResume &&
                            record.executorKind != ModelInstallExecutorKind.NONE
                    }
            if (records.isNotEmpty()) {
                // commit() is intentional: the marker must reach disk before an OEM kills
                // the process immediately after task removal.
                ModelInstallTaskRemovalMarkerStore(this).mark(records)
                val controller = runtime.manager as? DurableModelInstallController
                if (controller != null) {
                    ModelInstallRuntime.processScope.launch(Dispatchers.IO) {
                        records.forEach { record ->
                            runCatching {
                                controller.interruptForTaskRemoval(record.operationId)
                            }
                        }
                    }
                }
            }
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
