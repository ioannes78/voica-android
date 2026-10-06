package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelInstallDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val operationId =
            inputData.getString(MODEL_INSTALL_WORK_OPERATION_ID)
                ?: return Result.failure()
        val generation = inputData.getLong(MODEL_INSTALL_WORK_GENERATION, -1L)
        if (generation < 1L) return Result.failure()
        val runtime = ModelInstallRuntime.requireHolder()
        val record = runtime.journalStore.read(operationId) ?: return Result.success()

        if (record.origin == ModelInstallOrigin.MANUAL &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            setForeground(ModelInstallNotifications.foregroundInfo(applicationContext, record))
        }

        return when (
            runtime.orchestrator.executeDownload(
                operationId = operationId,
                generation = generation,
            )
        ) {
            ModelInstallExecutionOutcome.SUCCESS -> Result.success()
            ModelInstallExecutionOutcome.RETRY -> Result.retry()
            ModelInstallExecutionOutcome.FAILURE -> Result.failure()
        }
    }
}

class ModelInstallFinalizeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val operationId =
            inputData.getString(MODEL_INSTALL_WORK_OPERATION_ID)
                ?: return Result.failure()
        val generation = inputData.getLong(MODEL_INSTALL_WORK_GENERATION, -1L)
        if (generation < 1L) return Result.failure()
        val runtime = ModelInstallRuntime.requireHolder()
        val record = runtime.journalStore.read(operationId) ?: return Result.success()
        val manual = record.origin == ModelInstallOrigin.MANUAL
        if (manual) {
            ModelInstallNotifications.notify(applicationContext, record)
        }
        return try {
            when (
                runtime.orchestrator.executeFinalize(
                    operationId = operationId,
                    generation = generation,
                )
            ) {
                ModelInstallExecutionOutcome.SUCCESS -> Result.success()
                ModelInstallExecutionOutcome.RETRY -> Result.retry()
                ModelInstallExecutionOutcome.FAILURE -> Result.failure()
            }
        } finally {
            if (manual) {
                val latest = runtime.journalStore.read(operationId)
                if (latest == null || latest.phase.terminal || latest.requiresUserResume) {
                    ModelInstallNotifications.cancel(applicationContext, operationId)
                } else {
                    ModelInstallNotifications.notify(applicationContext, latest)
                }
            }
        }
    }
}

class ModelInstallUidtJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val operationId = params.extras.getString(MODEL_INSTALL_WORK_OPERATION_ID) ?: return false
        val generation = params.extras.getLong(MODEL_INSTALL_WORK_GENERATION, -1L)
        if (generation < 1L) return false
        val runtime = ModelInstallRuntime.requireHolder()
        val record = runtime.journalStore.read(operationId) ?: return false

        setNotification(
            params,
            ModelInstallNotifications.notificationId(operationId),
            ModelInstallNotifications.notification(this, record),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )

        val job =
            scope.launch {
                try {
                    val reschedule =
                        runtime.orchestrator.executeDownload(operationId, generation) ==
                            ModelInstallExecutionOutcome.RETRY
                    runningJobs.remove(params.jobId)
                    jobFinished(params, reschedule)
                } catch (cancelled: CancellationException) {
                    runningJobs.remove(params.jobId)
                    throw cancelled
                } catch (_: Throwable) {
                    runningJobs.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        runningJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel()
        val operationId = params.extras.getString(MODEL_INSTALL_WORK_OPERATION_ID)
        val record = operationId?.let { ModelInstallRuntime.requireHolder().journalStore.read(it) }
        return record?.cancelRequested != true
    }

    override fun onDestroy() {
        runningJobs.values.forEach(Job::cancel)
        runningJobs.clear()
        scope.cancel()
        super.onDestroy()
    }
}

class ModelInstallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_MODEL_INSTALL) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ModelInstallRuntime.requireHolder().orchestrator.cancelOperation(operationId)
            } finally {
                pending.finish()
            }
        }
    }
}

internal object ModelInstallNotifications {
    private const val CHANNEL_ID = "model-install"
    private const val CHANNEL_NAME = "模型安装"

    fun foregroundInfo(
        context: Context,
        record: ModelInstallJournalRecord,
    ): ForegroundInfo =
        ForegroundInfo(
            notificationId(record.operationId),
            notification(context, record),
        )

    fun notify(
        context: Context,
        record: ModelInstallJournalRecord,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        manager.notify(notificationId(record.operationId), notification(context, record))
    }

    fun cancel(
        context: Context,
        operationId: String,
    ) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(operationId))
    }

    fun notification(
        context: Context,
        record: ModelInstallJournalRecord,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val openIntent =
            PendingIntent.getActivity(
                context,
                notificationId(record.operationId),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancelIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId(record.operationId) + 1,
                Intent(context, ModelInstallActionReceiver::class.java)
                    .setAction(ACTION_CANCEL_MODEL_INSTALL)
                    .putExtra(EXTRA_OPERATION_ID, record.operationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(record.snapshot.descriptor.displayName)
                .setContentText(phaseText(record))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(!record.phase.terminal && !record.requiresUserResume)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (record.phase == ModelInstallPhase.DOWNLOAD &&
            record.totalBytes != null &&
            record.totalBytes > 0L
        ) {
            val percent =
                ((record.downloadedBytes * 100L) / record.totalBytes)
                    .coerceIn(0L, 100L)
                    .toInt()
            builder.setProgress(100, percent, false)
        } else if (!record.phase.terminal && !record.requiresUserResume) {
            builder.setProgress(0, 0, true)
        }

        if (!record.phase.terminal && !record.cancelRequested) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelIntent,
            )
        }
        return builder.build()
    }

    fun notificationId(operationId: String): Int =
        0x41000000 or (operationId.hashCode() and 0x0000ffff)

    private fun phaseText(record: ModelInstallJournalRecord): String =
        when (record.phase) {
            ModelInstallPhase.REQUESTED -> "等待开始"
            ModelInstallPhase.DOWNLOAD ->
                if (record.totalBytes != null) {
                    "正在下载 · ${formatMiB(record.downloadedBytes)} / ${formatMiB(record.totalBytes)}"
                } else {
                    "正在下载"
                }
            ModelInstallPhase.VERIFY -> "正在校验下载文件"
            ModelInstallPhase.EXTRACT -> "正在解压模型"
            ModelInstallPhase.FILE_VERIFY -> "正在校验模型文件"
            ModelInstallPhase.RUNTIME_VALIDATE -> "正在验证运行库"
            ModelInstallPhase.ATOMIC_ACTIVATE -> "正在启用模型"
            ModelInstallPhase.READY -> "已启用"
            ModelInstallPhase.INTERRUPTED -> "安装已中断，可继续"
            ModelInstallPhase.FAILED_RECOVERABLE ->
                record.lastFailureMessage ?: "安装暂停，可继续"
            ModelInstallPhase.FAILED_INTEGRITY -> "文件校验失败"
            ModelInstallPhase.FAILED_RUNTIME -> "运行库验证失败"
            ModelInstallPhase.FAILED_CONFIGURATION -> "模型安装配置失败"
            ModelInstallPhase.CANCELLED -> "已取消"
        }

    private fun formatMiB(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes.coerceAtLeast(0L) / 1048576.0)

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

internal const val ACTION_CANCEL_MODEL_INSTALL =
    "io.github.ioannes78.voica.action.CANCEL_MODEL_INSTALL"
internal const val EXTRA_OPERATION_ID = "model-install-operation-id"
