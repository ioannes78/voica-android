package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DeviceSessionForegroundPhase {
    IDLE,
    STARTING,
    ACTIVE,
    FAILED,
}

internal data class DeviceSessionForegroundSnapshot(
    val generation: Long = 0L,
    val phase: DeviceSessionForegroundPhase = DeviceSessionForegroundPhase.IDLE,
    val deviceName: String? = null,
    val label: String? = null,
    val failureClass: String? = null,
    val failureMessage: String? = null,
)

internal data class DeviceSessionForegroundAcquireResult(
    val generation: Long,
    val requestAccepted: Boolean,
)

/**
 * Process-local diagnostics for the Android execution owner only.
 *
 * Device/recording truth still belongs to DeviceRepository. This state exists so an OEM/permission
 * foreground-service failure can be observed and cannot leave the BLE repository pretending it has
 * a valid background execution lease.
 */
internal object DeviceSessionForegroundDiagnostics {
    private val sequence = AtomicLong(0L)
    private val mutableState = MutableStateFlow(DeviceSessionForegroundSnapshot())
    val state: StateFlow<DeviceSessionForegroundSnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun begin(deviceName: String, label: String): Long {
        val generation = sequence.incrementAndGet()
        mutableState.value =
            DeviceSessionForegroundSnapshot(
                generation = generation,
                phase = DeviceSessionForegroundPhase.STARTING,
                deviceName = deviceName,
                label = label,
            )
        return generation
    }

    @Synchronized
    fun markActive(generation: Long, deviceName: String, label: String) {
        if (mutableState.value.generation != generation) return
        mutableState.value =
            DeviceSessionForegroundSnapshot(
                generation = generation,
                phase = DeviceSessionForegroundPhase.ACTIVE,
                deviceName = deviceName,
                label = label,
            )
    }

    @Synchronized
    fun markFailed(generation: Long, deviceName: String, label: String, error: RuntimeException) {
        if (mutableState.value.generation != generation) return
        mutableState.value =
            DeviceSessionForegroundSnapshot(
                generation = generation,
                phase = DeviceSessionForegroundPhase.FAILED,
                deviceName = deviceName,
                label = label,
                failureClass = error.javaClass.name,
                failureMessage = error.message,
            )
    }

    @Synchronized
    fun markIdle() {
        mutableState.value =
            DeviceSessionForegroundSnapshot(
                generation = sequence.incrementAndGet(),
                phase = DeviceSessionForegroundPhase.IDLE,
            )
    }
}

/**
 * Foreground ownership boundary for an active QS668/CB08 session that must remain alive while the
 * Activity is backgrounded. Stage 13B.3 wires the BLE repository to this host only while recording,
 * reconnect or file transfer actually requires it.
 */
class DeviceSessionForegroundService : Service() {
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                DeviceSessionForegroundDiagnostics.markIdle()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ACQUIRE -> {
                val generation = intent.getLongExtra(EXTRA_GENERATION, 0L)
                val deviceName =
                    intent.getStringExtra(EXTRA_DEVICE_NAME)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: FALLBACK_DEVICE_NAME
                val label =
                    intent.getStringExtra(EXTRA_LABEL)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "保持连接"
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(deviceName, label),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                    DeviceSessionForegroundDiagnostics.markActive(
                        generation = generation,
                        deviceName = deviceName,
                        label = label,
                    )
                } catch (error: RuntimeException) {
                    Log.e(TAG, "connectedDevice foreground start failed", error)
                    DeviceSessionForegroundDiagnostics.markFailed(
                        generation = generation,
                        deviceName = deviceName,
                        label = label,
                        error = error,
                    )
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf(startId)
                }
            }

            else -> {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(deviceName: String, label: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(deviceName)
            .setContentText(label)
            .setContentIntent(mainActivityPendingIntent(this, REQUEST_CONTENT))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "录音卡连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "录音、重连和文件传输期间保持录音卡连接"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "VoicaDeviceSessionFGS"
        private const val CHANNEL_ID = "voica-device-session"
        private const val NOTIFICATION_ID = 13031
        private const val REQUEST_CONTENT = 13031
        private const val ACTION_ACQUIRE = "io.github.ioannes78.voica.device.ACQUIRE"
        private const val ACTION_RELEASE = "io.github.ioannes78.voica.device.RELEASE"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_GENERATION = "generation"
        private const val FALLBACK_DEVICE_NAME = "录音卡"

        internal fun acquire(
            context: Context,
            deviceName: String,
            label: String,
        ): DeviceSessionForegroundAcquireResult {
            val safeName = deviceName.trim().ifEmpty { FALLBACK_DEVICE_NAME }
            val safeLabel = label.trim().ifEmpty { "保持连接" }
            val generation = DeviceSessionForegroundDiagnostics.begin(safeName, safeLabel)
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DeviceSessionForegroundService::class.java)
                        .setAction(ACTION_ACQUIRE)
                        .putExtra(EXTRA_DEVICE_NAME, safeName)
                        .putExtra(EXTRA_LABEL, safeLabel)
                        .putExtra(EXTRA_GENERATION, generation),
                )
                DeviceSessionForegroundAcquireResult(
                    generation = generation,
                    requestAccepted = true,
                )
            } catch (error: RuntimeException) {
                Log.e(TAG, "connectedDevice foreground request failed", error)
                DeviceSessionForegroundDiagnostics.markFailed(
                    generation = generation,
                    deviceName = safeName,
                    label = safeLabel,
                    error = error,
                )
                DeviceSessionForegroundAcquireResult(
                    generation = generation,
                    requestAccepted = false,
                )
            }
        }

        fun release(context: Context) {
            try {
                context.stopService(Intent(context, DeviceSessionForegroundService::class.java))
            } catch (error: RuntimeException) {
                Log.e(TAG, "connectedDevice foreground release failed", error)
            } finally {
                DeviceSessionForegroundDiagnostics.markIdle()
            }
        }
    }
}

private data class MediaProcessingTaskDisplay(
    val title: String,
    val label: String,
    val progressPercent: Int?,
    val cancelAction: String?,
    val recordingId: String?,
    val destination: RecordingDetailDestination?,
)

/**
 * Foreground execution host for long local media work such as canonical conversion, offline ASR and
 * diarization. Room/coordinator business state remains authoritative; this service only owns Android
 * execution lifetime and the user-visible system notification.
 */
class MediaProcessingForegroundService : Service() {
    private lateinit var notificationManager: NotificationManager
    private val activeTasks = linkedMapOf<String, MediaProcessingTaskDisplay>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
        when (intent?.action) {
            ACTION_ACQUIRE,
            ACTION_UPDATE,
            -> {
                if (taskId != null) {
                    activeTasks[taskId] = taskFrom(intent)
                    publish()
                } else if (activeTasks.isEmpty()) {
                    stopSelf(startId)
                }
            }

            ACTION_RELEASE -> {
                if (taskId != null) activeTasks.remove(taskId)
                if (activeTasks.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    publish()
                }
            }

            else -> {
                if (activeTasks.isEmpty()) stopSelf(startId) else publish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun taskFrom(intent: Intent): MediaProcessingTaskDisplay =
        MediaProcessingTaskDisplay(
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "录音" },
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "正在处理录音" },
            progressPercent =
                intent.getIntExtra(EXTRA_PROGRESS_PERCENT, NO_PROGRESS)
                    .takeIf { it in 0..100 },
            cancelAction = intent.getStringExtra(EXTRA_CANCEL_ACTION)?.takeIf { it.isNotBlank() },
            recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)?.takeIf { it.isNotBlank() },
            destination =
                intent.getStringExtra(EXTRA_DESTINATION)
                    ?.let { value ->
                        RecordingDetailDestination.entries.firstOrNull { it.name == value }
                    },
        )

    private fun publish() {
        val primaryEntry = activeTasks.entries.firstOrNull() ?: return
        val taskId = primaryEntry.key
        val task = primaryEntry.value
        val text =
            if (activeTasks.size <= 1) {
                task.label
            } else {
                "${task.label} · 另有 ${activeTasks.size - 1} 个任务"
            }
        val contentIntent =
            if (task.recordingId != null && task.destination != null) {
                recordingOpenPendingIntent(
                    context = this,
                    requestCode = REQUEST_CONTENT,
                    recordingId = task.recordingId,
                    destination = task.destination,
                )
            } else {
                mainActivityPendingIntent(this, REQUEST_CONTENT)
            }
        val builder =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(task.title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setProgress(100, task.progressPercent ?: 0, task.progressPercent == null)
        task.cancelAction?.let { action ->
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                taskCancelPendingIntent(this, action, taskId),
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "录音处理",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "长时间音频转换、转写和说话人分析"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "voica-media-processing"
        private const val NOTIFICATION_ID = 13041
        private const val REQUEST_CONTENT = 13041
        private const val ACTION_ACQUIRE = "io.github.ioannes78.voica.media.ACQUIRE"
        private const val ACTION_UPDATE = "io.github.ioannes78.voica.media.UPDATE"
        private const val ACTION_RELEASE = "io.github.ioannes78.voica.media.RELEASE"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PROGRESS_PERCENT = "progressPercent"
        private const val EXTRA_CANCEL_ACTION = "cancelAction"
        private const val EXTRA_RECORDING_ID = "recordingId"
        private const val EXTRA_DESTINATION = "destination"
        private const val NO_PROGRESS = -1

        fun acquire(
            context: Context,
            taskId: String,
            title: String,
            label: String,
            progressPercent: Int?,
            cancelAction: String?,
            recordingId: String? = null,
            destination: RecordingDetailDestination? = null,
        ) {
            require(taskId.isNotBlank())
            ContextCompat.startForegroundService(
                context,
                taskIntent(
                    context = context,
                    action = ACTION_ACQUIRE,
                    taskId = taskId,
                    title = title,
                    label = label,
                    progressPercent = progressPercent,
                    cancelAction = cancelAction,
                    recordingId = recordingId,
                    destination = destination,
                ),
            )
        }

        fun update(
            context: Context,
            taskId: String,
            title: String,
            label: String,
            progressPercent: Int?,
            cancelAction: String?,
            recordingId: String? = null,
            destination: RecordingDetailDestination? = null,
        ) {
            require(taskId.isNotBlank())
            runCatching {
                context.startService(
                    taskIntent(
                        context = context,
                        action = ACTION_UPDATE,
                        taskId = taskId,
                        title = title,
                        label = label,
                        progressPercent = progressPercent,
                        cancelAction = cancelAction,
                        recordingId = recordingId,
                        destination = destination,
                    ),
                )
            }
        }

        fun release(context: Context, taskId: String) {
            require(taskId.isNotBlank())
            runCatching {
                context.startService(
                    Intent(context, MediaProcessingForegroundService::class.java)
                        .setAction(ACTION_RELEASE)
                        .putExtra(EXTRA_TASK_ID, taskId),
                )
            }
        }

        private fun taskIntent(
            context: Context,
            action: String,
            taskId: String,
            title: String,
            label: String,
            progressPercent: Int?,
            cancelAction: String?,
            recordingId: String?,
            destination: RecordingDetailDestination?,
        ): Intent =
            Intent(context, MediaProcessingForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent ?: NO_PROGRESS)
                .putExtra(EXTRA_CANCEL_ACTION, cancelAction)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
                .putExtra(EXTRA_DESTINATION, destination?.name)
    }
}

private fun taskCancelPendingIntent(
    context: Context,
    action: String,
    taskId: String,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskNotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun mainActivityPendingIntent(context: Context, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
