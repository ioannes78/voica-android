package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

internal enum class PhoneMicrophoneForegroundPhase {
    IDLE,
    STARTING,
    ACTIVE,
    FAILED,
}

internal data class PhoneMicrophoneForegroundSnapshot(
    val generation: Long = 0L,
    val phase: PhoneMicrophoneForegroundPhase = PhoneMicrophoneForegroundPhase.IDLE,
    val label: String? = null,
    val failureClass: String? = null,
    val failureMessage: String? = null,
)

internal data class PhoneMicrophoneForegroundAcquireResult(
    val generation: Long,
    val requestAccepted: Boolean,
)

/**
 * Process-local Android execution diagnostics for Stage 16A.1 phone microphone capture.
 *
 * Recording/media truth must never be inferred from this object. It only reports whether the
 * platform foreground-service owner was requested and whether Android accepted foreground state.
 */
internal object PhoneMicrophoneForegroundDiagnostics {
    private val sequence = AtomicLong(0L)
    private val mutableState = MutableStateFlow(PhoneMicrophoneForegroundSnapshot())
    val state: StateFlow<PhoneMicrophoneForegroundSnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun begin(label: String): Long {
        val generation = sequence.incrementAndGet()
        mutableState.value =
            PhoneMicrophoneForegroundSnapshot(
                generation = generation,
                phase = PhoneMicrophoneForegroundPhase.STARTING,
                label = label,
            )
        return generation
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean =
        generation > 0L && mutableState.value.generation == generation

    @Synchronized
    fun markActive(generation: Long, label: String) {
        if (!isCurrent(generation)) return
        mutableState.value =
            PhoneMicrophoneForegroundSnapshot(
                generation = generation,
                phase = PhoneMicrophoneForegroundPhase.ACTIVE,
                label = label,
            )
    }

    @Synchronized
    fun markFailed(generation: Long, label: String, error: RuntimeException) {
        if (!isCurrent(generation)) return
        mutableState.value =
            PhoneMicrophoneForegroundSnapshot(
                generation = generation,
                phase = PhoneMicrophoneForegroundPhase.FAILED,
                label = label,
                failureClass = error.javaClass.name,
                failureMessage = error.message,
            )
    }

    @Synchronized
    fun markIdle() {
        mutableState.value =
            PhoneMicrophoneForegroundSnapshot(
                generation = sequence.incrementAndGet(),
                phase = PhoneMicrophoneForegroundPhase.IDLE,
            )
    }
}

/**
 * Dedicated microphone foreground-service owner.
 *
 * This service is intentionally separate from the recorder-card `connectedDevice` service and from
 * Stage 16A inference lifetime. It may only be started from a user-visible flow after RECORD_AUDIO
 * has been granted. Once active, Android may keep microphone capture alive while the Activity moves
 * to background/screen-off.
 */
class PhoneMicrophoneForegroundService : Service() {
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                val generation = intent.getLongExtra(EXTRA_GENERATION, 0L)
                if (!PhoneMicrophoneForegroundDiagnostics.isCurrent(generation)) {
                    Log.d(TAG, "ignore stale microphone foreground generation=$generation")
                    return START_NOT_STICKY
                }
                val label =
                    intent.getStringExtra(EXTRA_LABEL)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: DEFAULT_LABEL
                val target =
                    intent.getStringExtra(EXTRA_TARGET)
                        ?.let { encoded ->
                            runCatching { PhoneMicrophoneProductTarget.valueOf(encoded) }.getOrNull()
                        }
                        ?: PhoneMicrophoneProductTarget.RECORDING
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(label, target),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                    PhoneMicrophoneForegroundDiagnostics.markActive(generation, label)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "microphone foreground start failed", error)
                    PhoneMicrophoneForegroundDiagnostics.markFailed(generation, label, error)
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf(startId)
                }
            }

            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PhoneMicrophoneForegroundDiagnostics.markIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        label: String,
        target: PhoneMicrophoneProductTarget,
    ): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wenzhi)
            .setContentTitle(
                when (target) {
                    PhoneMicrophoneProductTarget.RECORDING -> "闻知 · 手机录音"
                    PhoneMicrophoneProductTarget.REALTIME -> "闻知 · 手机实时转写"
                },
            )
            .setContentText(label)
            .setContentIntent(
                phoneMicrophoneOpenPendingIntent(
                    context = this,
                    target = target,
                    requestCode =
                        when (target) {
                            PhoneMicrophoneProductTarget.RECORDING -> REQUEST_CONTENT_RECORDING
                            PhoneMicrophoneProductTarget.REALTIME -> REQUEST_CONTENT_REALTIME
                        },
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "手机录音",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "手机麦克风录音期间保持后台采集"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "VoicaPhoneMicFGS"
        private const val CHANNEL_ID = "voica-phone-microphone"
        private const val NOTIFICATION_ID = 13061
        private const val REQUEST_CONTENT_RECORDING = 13061
        private const val REQUEST_CONTENT_REALTIME = 13062
        private const val ACTION_ACQUIRE = "io.github.ioannes78.voica.phone_mic.ACQUIRE"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_GENERATION = "generation"
        private const val EXTRA_TARGET = "target"
        private const val DEFAULT_LABEL = "正在使用手机麦克风录音"

        internal fun acquire(
            context: Context,
            label: String = DEFAULT_LABEL,
        ): PhoneMicrophoneForegroundAcquireResult {
            val safeLabel = label.trim().ifEmpty { DEFAULT_LABEL }
            val generation = PhoneMicrophoneForegroundDiagnostics.begin(safeLabel)
            val target =
                phoneMicrophoneProductTarget(
                    realtimeActiveRunId =
                        ((context.applicationContext as? VoicaApplication)
                            ?.runCatching { container.phoneMicrophoneRealtimeRuntime.snapshot().activeRunId }
                            ?.getOrNull()),
                )
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PhoneMicrophoneForegroundService::class.java)
                        .setAction(ACTION_ACQUIRE)
                        .putExtra(EXTRA_LABEL, safeLabel)
                        .putExtra(EXTRA_GENERATION, generation)
                        .putExtra(EXTRA_TARGET, target.name),
                )
                PhoneMicrophoneForegroundAcquireResult(
                    generation = generation,
                    requestAccepted = true,
                )
            } catch (error: RuntimeException) {
                Log.e(TAG, "microphone foreground request failed", error)
                PhoneMicrophoneForegroundDiagnostics.markFailed(
                    generation = generation,
                    label = safeLabel,
                    error = error,
                )
                PhoneMicrophoneForegroundAcquireResult(
                    generation = generation,
                    requestAccepted = false,
                )
            }
        }

        internal fun release(context: Context) {
            try {
                context.stopService(Intent(context, PhoneMicrophoneForegroundService::class.java))
            } catch (error: RuntimeException) {
                Log.e(TAG, "microphone foreground release failed", error)
            } finally {
                PhoneMicrophoneForegroundDiagnostics.markIdle()
            }
        }
    }
}
