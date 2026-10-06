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
                // START_NOT_STICKY should not recreate this service without an explicit lease.
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

        fun acquire(
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

/**
 * Foreground execution host for long local media work such as canonical conversion, offline ASR and
 * diarization. It intentionally stores only process-local execution leases; Room business entities
 * remain the durable source of truth and reconcile interrupted work after process death.
 */
class MediaProcessingForegroundService : Service() {
    private lateinit var notificationManager: NotificationManager
    private val activeTasks = linkedMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                if (taskId != null) {
                    activeTasks[taskId] =
                        intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "处理录音" }
                }
                publish()
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
                if (activeTasks.isEmpty()) {
                    stopSelf()
                } else {
                    publish()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ gives mediaProcessing a shared six-hour budget. Business state is deliberately
        // not rewritten here; the owning coordinator/repository performs terminal or interrupted
        // reconciliation. Stage 13B integrations must cancel work before releasing this host.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun publish() {
        val primary = activeTasks.values.firstOrNull() ?: "处理录音"
        val text =
            if (activeTasks.size <= 1) {
                primary
            } else {
                "$primary · 另有 ${activeTasks.size - 1} 个任务"
            }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Voica 正在处理录音")
                .setContentText(text)
                .setContentIntent(mainActivityPendingIntent(this, REQUEST_CONTENT))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build(),
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
        private const val ACTION_RELEASE = "io.github.ioannes78.voica.media.RELEASE"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_LABEL = "label"

        fun acquire(context: Context, taskId: String, label: String) {
            require(taskId.isNotBlank())
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaProcessingForegroundService::class.java)
                    .setAction(ACTION_ACQUIRE)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_LABEL, label),
            )
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
    }
}

private fun mainActivityPendingIntent(context: Context, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
