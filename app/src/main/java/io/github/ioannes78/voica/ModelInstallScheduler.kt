package io.github.ioannes78.voica

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ModelInstallScheduler {
    fun downloadExecutor(origin: ModelInstallOrigin): ModelInstallExecutorKind

    suspend fun scheduleDownload(record: ModelInstallJournalRecord)

    suspend fun scheduleFinalize(record: ModelInstallJournalRecord)

    suspend fun cancel(operationId: String)

    suspend fun isScheduled(record: ModelInstallJournalRecord): Boolean
}

internal fun modelInstallDownloadExecutor(
    origin: ModelInstallOrigin,
    sdkInt: Int,
): ModelInstallExecutorKind =
    if (origin == ModelInstallOrigin.MANUAL &&
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        ModelInstallExecutorKind.UIDT
    } else {
        ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD
    }

class AndroidModelInstallScheduler(
    private val application: Application,
) : ModelInstallScheduler {
    private val workManager = WorkManager.getInstance(application)
    private val jobScheduler = application.getSystemService(JobScheduler::class.java)

    override fun downloadExecutor(origin: ModelInstallOrigin): ModelInstallExecutorKind =
        modelInstallDownloadExecutor(origin, Build.VERSION.SDK_INT)

    override suspend fun scheduleDownload(record: ModelInstallJournalRecord) {
        when (downloadExecutor(record.origin)) {
            ModelInstallExecutorKind.UIDT -> scheduleUidtDownload(record)
            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD -> scheduleWorkManagerDownload(record)
            else -> error("unsupported model download executor")
        }
    }

    override suspend fun scheduleFinalize(record: ModelInstallJournalRecord) {
        val request =
            OneTimeWorkRequestBuilder<ModelInstallFinalizeWorker>()
                .setInputData(
                    workDataOf(
                        MODEL_INSTALL_WORK_OPERATION_ID to record.operationId,
                        MODEL_INSTALL_WORK_GENERATION to record.executorGeneration,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()

        workManager.enqueueUniqueWork(
            finalizeWorkName(record.operationId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun cancel(operationId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            jobScheduler.cancel(modelInstallJobId(operationId))
        }
        workManager.cancelUniqueWork(downloadWorkName(operationId))
        workManager.cancelUniqueWork(finalizeWorkName(operationId))
    }

    override suspend fun isScheduled(record: ModelInstallJournalRecord): Boolean =
        when (record.executorKind) {
            ModelInstallExecutorKind.UIDT ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    jobScheduler.getPendingJob(modelInstallJobId(record.operationId)) != null

            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD ->
                hasUnfinishedWork(downloadWorkName(record.operationId))

            ModelInstallExecutorKind.WORK_MANAGER_FINALIZE ->
                hasUnfinishedWork(finalizeWorkName(record.operationId))

            ModelInstallExecutorKind.NONE -> false
        }

    private fun scheduleUidtDownload(record: ModelInstallJournalRecord) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val totalBytes =
            requireNotNull(record.totalBytes) {
                "UIDT model download requires an expected byte count"
            }
        val extras =
            PersistableBundle().apply {
                putString(MODEL_INSTALL_WORK_OPERATION_ID, record.operationId)
                putLong(MODEL_INSTALL_WORK_GENERATION, record.executorGeneration)
            }
        val job =
            JobInfo.Builder(
                modelInstallJobId(record.operationId),
                ComponentName(application, ModelInstallUidtJobService::class.java),
            )
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(totalBytes, 0L)
                .setRequiresStorageNotLow(true)
                .setPersisted(true)
                .setExtras(extras)
                .build()

        check(jobScheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "failed to schedule user-initiated model download"
        }
    }

    private fun scheduleWorkManagerDownload(record: ModelInstallJournalRecord) {
        val request =
            OneTimeWorkRequestBuilder<ModelInstallDownloadWorker>()
                .setInputData(
                    workDataOf(
                        MODEL_INSTALL_WORK_OPERATION_ID to record.operationId,
                        MODEL_INSTALL_WORK_GENERATION to record.executorGeneration,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()

        workManager.enqueueUniqueWork(
            downloadWorkName(record.operationId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private suspend fun hasUnfinishedWork(name: String): Boolean =
        withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(name).get().any { workInfo ->
                workInfo.state == WorkInfo.State.ENQUEUED ||
                    workInfo.state == WorkInfo.State.RUNNING ||
                    workInfo.state == WorkInfo.State.BLOCKED
            }
        }
}

internal const val MODEL_INSTALL_WORK_OPERATION_ID = "model-install-operation-id"
internal const val MODEL_INSTALL_WORK_GENERATION = "model-install-generation"

internal fun downloadWorkName(operationId: String): String =
    "model-install-download:$operationId"

internal fun finalizeWorkName(operationId: String): String =
    "model-install-finalize:$operationId"

internal fun modelInstallJobId(operationId: String): Int =
    MODEL_INSTALL_JOB_ID_BASE or (operationId.hashCode() and MODEL_INSTALL_JOB_ID_MASK)

private const val MODEL_INSTALL_JOB_ID_BASE = 0x50000000
private const val MODEL_INSTALL_JOB_ID_MASK = 0x0fffffff
