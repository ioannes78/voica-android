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
import android.content.pm.ServiceInfo
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

internal enum class ModelInstallNotificationAction {
    CANCEL_DOWNLOAD,
    PAUSE_INSTALL,
    CONTINUE_INSTALL,
    NONE,
}

internal fun modelInstallDownloadPercent(
    downloadedBytes: Long,
    totalBytes: Long?,
): Int? =
    totalBytes
        ?.takeIf { it > 0L }
        ?.let { total ->
            ((downloadedBytes.coerceAtLeast(0L) * 100L) / total)
                .coerceIn(0L, 100L)
                .toInt()
        }

internal fun modelInstallNotificationAction(
    phase: ModelInstallPhase,
    requiresUserResume: Boolean,
    downloadedBytes: Long,
    totalBytes: Long?,
): ModelInstallNotificationAction {
    if (phase.terminal) return ModelInstallNotificationAction.NONE
    if (requiresUserResume ||
        phase == ModelInstallPhase.INTERRUPTED ||
        phase == ModelInstallPhase.FAILED_RECOVERABLE
    ) {
        return ModelInstallNotificationAction.CONTINUE_INSTALL
    }
    if (phase == ModelInstallPhase.REQUESTED) {
        return ModelInstallNotificationAction.CANCEL_DOWNLOAD
    }
    if (phase == ModelInstallPhase.DOWNLOAD) {
        val complete = totalBytes != null && totalBytes > 0L && downloadedBytes >= totalBytes
        return if (complete) {
            ModelInstallNotificationAction.PAUSE_INSTALL
        } else {
            ModelInstallNotificationAction.CANCEL_DOWNLOAD
        }
    }
    return ModelInstallNotificationAction.PAUSE_INSTALL
}

internal fun modelInstallNotificationPhaseLabel(phase: ModelInstallPhase): String =
    when (phase) {
        ModelInstallPhase.REQUESTED -> "等待开始"
        ModelInstallPhase.DOWNLOAD -> "正在下载"
        ModelInstallPhase.VERIFY -> "正在校验下载文件"
        ModelInstallPhase.EXTRACT -> "正在解压模型"
        ModelInstallPhase.FILE_VERIFY -> "正在校验模型文件"
        ModelInstallPhase.RUNTIME_VALIDATE -> "正在验证运行库"
        ModelInstallPhase.ATOMIC_ACTIVATE -> "正在启用模型"
        ModelInstallPhase.READY -> "已启用"
        ModelInstallPhase.INTERRUPTED -> "安装已中断，可继续"
        ModelInstallPhase.FAILED_RECOVERABLE -> "安装暂停，可继续"
        ModelInstallPhase.FAILED_INTEGRITY -> "文件校验失败"
        ModelInstallPhase.FAILED_RUNTIME -> "运行库验证失败"
        ModelInstallPhase.FAILED_CONFIGURATION -> "模型安装配置失败"
        ModelInstallPhase.CANCELLED -> "已取消"
    }

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

        return try {
            when (
                runtime.orchestrator.executeDownload(
                    operationId = operationId,
                    generation = generation,
                )
            ) {
                ModelInstallExecutionOutcome.SUCCESS -> Result.success()
                ModelInstallExecutionOutcome.RETRY -> Result.retry()
                ModelInstallExecutionOutcome.FAILURE -> Result.failure()
            }
        } finally {
            runtime.journalStore.read(operationId)?.let { latest ->
                ModelInstallNotifications.sync(applicationContext, latest)
            }
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
            ModelInstallNotifications.sync(applicationContext, record)
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
                if (latest == null) {
                    ModelInstallNotifications.cancel(applicationContext, operationId)
                } else {
                    ModelInstallNotifications.sync(applicationContext, latest)
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
                    runtime.journalStore.read(operationId)?.let { latest ->
                        ModelInstallNotifications.sync(this@ModelInstallUidtJobService, latest)
                    }
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
        if (intent.action != ACTION_CONTROL_MODEL_INSTALL) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = ModelInstallRuntime.requireHolder()
                val controller = runtime.manager as? DurableModelInstallController
                    ?: return@launch
                val current = runtime.journalStore.read(operationId) ?: return@launch
                when (
                    modelInstallNotificationAction(
                        phase = current.phase,
                        requiresUserResume = current.requiresUserResume,
                        downloadedBytes = current.downloadedBytes,
                        totalBytes = current.totalBytes,
                    )
                ) {
                    ModelInstallNotificationAction.CONTINUE_INSTALL ->
                        controller.resumeInstall(operationId)

                    ModelInstallNotificationAction.CANCEL_DOWNLOAD,
                    ModelInstallNotificationAction.PAUSE_INSTALL ->
                        controller.requestUserStop(operationId)

                    ModelInstallNotificationAction.NONE -> Unit
                }
                runtime.journalStore.latestForModel(current.modelId)?.let { latest ->
                    ModelInstallNotifications.sync(context, latest)
                }
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun sync(
        context: Context,
        record: ModelInstallJournalRecord,
    ) {
        if (record.origin != ModelInstallOrigin.MANUAL || record.phase.terminal) {
            cancel(context, record.operationId)
            return
        }
        notify(context, record)
    }

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
        val controlIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId(record.operationId) + 1,
                Intent(context, ModelInstallActionReceiver::class.java)
                    .setAction(ACTION_CONTROL_MODEL_INSTALL)
                    .putExtra(EXTRA_OPERATION_ID, record.operationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val progressPercent =
            if (record.phase == ModelInstallPhase.DOWNLOAD) {
                modelInstallDownloadPercent(record.downloadedBytes, record.totalBytes)
            } else {
                null
            }
        val contentText =
            if (progressPercent != null) {
                "$progressPercent% · ${formatMiB(record.downloadedBytes)} / ${formatMiB(record.totalBytes ?: 0L)}"
            } else {
                phaseText(record)
            }
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(record.snapshot.descriptor.displayName)
                .setContentText(contentText)
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(!record.phase.terminal && !record.requiresUserResume)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (record.phase == ModelInstallPhase.DOWNLOAD) {
            if (progressPercent != null) {
                builder.setSubText("正在下载")
                builder.setProgress(100, progressPercent, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else if (!record.phase.terminal && !record.requiresUserResume) {
            builder.setProgress(0, 0, true)
        }

        val action =
            modelInstallNotificationAction(
                phase = record.phase,
                requiresUserResume = record.requiresUserResume,
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes,
            )
        if (action != ModelInstallNotificationAction.NONE && !record.cancelRequested) {
            val label =
                when (action) {
                    ModelInstallNotificationAction.CANCEL_DOWNLOAD -> "取消下载"
                    ModelInstallNotificationAction.PAUSE_INSTALL -> "暂停安装"
                    ModelInstallNotificationAction.CONTINUE_INSTALL -> "继续安装"
                    ModelInstallNotificationAction.NONE -> ""
                }
            val icon =
                if (action == ModelInstallNotificationAction.CONTINUE_INSTALL) {
                    android.R.drawable.ic_media_play
                } else {
                    android.R.drawable.ic_menu_close_clear_cancel
                }
            builder.addAction(icon, label, controlIntent)
        }
        return builder.build()
    }

    fun notificationId(operationId: String): Int =
        0x41000000 or (operationId.hashCode() and 0x0000ffff)

    private fun phaseText(record: ModelInstallJournalRecord): String =
        when (record.phase) {
            ModelInstallPhase.FAILED_RECOVERABLE ->
                modelInstallRecordUserMessage(record) ?: modelInstallNotificationPhaseLabel(record.phase)
            else -> modelInstallNotificationPhaseLabel(record.phase)
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

internal const val ACTION_CONTROL_MODEL_INSTALL =
    "io.github.ioannes78.voica.action.CONTROL_MODEL_INSTALL"
internal const val EXTRA_OPERATION_ID = "model-install-operation-id"
