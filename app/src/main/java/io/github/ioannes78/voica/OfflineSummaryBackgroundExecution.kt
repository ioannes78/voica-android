package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class OfflineSummaryTaskPresentation(
    val recordingId: String,
    val summaryId: String,
    val phase: OfflineSummaryRunPhase,
    val message: String? = null,
) {
    val terminal: Boolean
        get() = phase.isOfflineSummaryTerminal()

    val label: String
        get() {
            val base =
                when (phase) {
                    OfflineSummaryRunPhase.PREPARING -> "本地总结 · 正在准备"
                    OfflineSummaryRunPhase.ANALYZING -> "本地总结 · 正在总结"
                    OfflineSummaryRunPhase.VALIDATING -> "本地总结 · 正在校验"
                    OfflineSummaryRunPhase.COMPLETED -> "本地总结完成"
                    OfflineSummaryRunPhase.FAILED -> "本地总结失败"
                    OfflineSummaryRunPhase.CANCELLED -> "本地总结已取消"
                }
            return if (!terminal && !message.isNullOrBlank()) "$base · $message" else base
        }

    val trailingLabel: String?
        get() =
            when (phase) {
                OfflineSummaryRunPhase.COMPLETED -> "完成"
                OfflineSummaryRunPhase.FAILED -> "查看"
                OfflineSummaryRunPhase.CANCELLED -> "已取消"
                else -> null
            }
}

/**
 * Process-level Android execution owner for Stage 17.2 local summary.
 *
 * Business truth remains in OfflineSummaryCoordinator/Room. This controller only projects that
 * truth into Android execution leases and presentation. It deliberately does not add WorkManager or
 * resumable checkpoints: process death still reconciles active Room rows to INTERRUPTED.
 */
internal class OfflineSummaryExecutionController(
    context: Context,
    container: AppContainer,
) {
    private data class ObservedState(
        val summaryId: String,
        val phase: OfflineSummaryRunPhase,
    )

    private data class ActiveForegroundTask(
        val recordingId: String,
        val summaryId: String,
    )

    private val appContext = context.applicationContext
    private val coordinator = container.offlineSummaryCoordinator
    private val contentRepository = container.stage12CContentRepository
    private val localLlmRuntime = container.localLlmRuntime(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val mutablePresentation = MutableStateFlow<OfflineSummaryTaskPresentation?>(null)
    private val observed = mutableMapOf<String, ObservedState>()
    private val terminalNotified = mutableSetOf<String>()
    private val foregroundFailures = mutableMapOf<String, String>()
    private var activeForegroundTask: ActiveForegroundTask? = null

    val presentation: StateFlow<OfflineSummaryTaskPresentation?> = mutablePresentation.asStateFlow()

    init {
        createResultChannel()
        scope.launch {
            coordinator.state.collect { states -> reconcile(states.values.toList()) }
        }
        scope.launch {
            while (isActive) {
                delay(GENERATION_WATCHDOG_INTERVAL_MS)
                updateGenerationStallPresentation()
            }
        }
    }

    fun cancel(recordingId: String) {
        if (recordingId.isBlank()) return
        scope.launch { coordinator.cancel(recordingId) }
    }

    fun acknowledge(summaryId: String) {
        if (summaryId.isBlank()) return
        val current = mutablePresentation.value
        if (current?.summaryId == summaryId && current.terminal) {
            mutablePresentation.value = null
        }
        notificationManager.cancel(resultNotificationId(summaryId))
    }

    fun reportForegroundFailure(
        recordingId: String,
        summaryId: String,
        failure: Throwable,
    ) {
        val message =
            "无法建立锁屏后台执行保护：" +
                (failure.message ?: failure::class.java.simpleName)
                    .replace(Regex("\\s+"), " ")
                    .take(160)
        foregroundFailures[summaryId] = message
        activeForegroundTask = null
        mutablePresentation.value =
            OfflineSummaryTaskPresentation(
                recordingId = recordingId,
                summaryId = summaryId,
                phase = OfflineSummaryRunPhase.FAILED,
                message = message,
            )
        postTerminalNotification(
            recordingId = recordingId,
            summaryId = summaryId,
            title = "本地总结后台执行失败",
            text = message,
        )
        cancel(recordingId)
    }

    private suspend fun reconcile(states: List<OfflineSummaryRunState>) {
        val changedTerminal = mutableListOf<OfflineSummaryRunState>()
        states.forEach { state ->
            val previous = observed[state.recordingId]
            val changed =
                previous == null ||
                    previous.summaryId != state.summaryId ||
                    previous.phase != state.phase
            if (changed && state.phase.isOfflineSummaryTerminal()) changedTerminal += state
            observed[state.recordingId] = ObservedState(state.summaryId, state.phase)
        }

        val running = states.firstOrNull { it.phase.isOfflineSummaryRunning() }
        if (running != null) {
            ensureForeground(running)
            if (!foregroundFailures.containsKey(running.summaryId)) {
                mutablePresentation.value = running.toPresentation()
            }
            return
        }

        releaseForeground()
        val terminal = changedTerminal.lastOrNull() ?: return
        val foregroundFailure = foregroundFailures[terminal.summaryId]
        val presentation =
            if (foregroundFailure != null && terminal.phase == OfflineSummaryRunPhase.CANCELLED) {
                OfflineSummaryTaskPresentation(
                    recordingId = terminal.recordingId,
                    summaryId = terminal.summaryId,
                    phase = OfflineSummaryRunPhase.FAILED,
                    message = foregroundFailure,
                )
            } else {
                terminal.toPresentation()
            }

        if (presentation.phase == OfflineSummaryRunPhase.COMPLETED) {
            val currentSummaryId =
                runCatching { contentRepository.resolveCurrentAiSummaryId(presentation.recordingId) }
                    .getOrNull()
            val completedAsCandidate =
                currentSummaryId != null && currentSummaryId != presentation.summaryId
            if (completedAsCandidate) {
                // Regeneration keeps the existing Current. Durable candidate attention/notification
                // owns the completion surface so we do not emit a duplicate terminal notification.
                mutablePresentation.value = null
                notificationManager.cancel(resultNotificationId(presentation.summaryId))
                terminalNotified.add(presentation.summaryId)
                return
            }
        }

        mutablePresentation.value = presentation
        if (terminalNotified.add(terminal.summaryId)) {
            when (presentation.phase) {
                OfflineSummaryRunPhase.COMPLETED ->
                    postTerminalNotification(
                        recordingId = presentation.recordingId,
                        summaryId = presentation.summaryId,
                        title = "本地总结已完成",
                        text = "总结结果已生成",
                    )
                OfflineSummaryRunPhase.FAILED ->
                    postTerminalNotification(
                        recordingId = presentation.recordingId,
                        summaryId = presentation.summaryId,
                        title = "本地总结失败",
                        text = presentation.message ?: "本地总结没有完成，请返回闻知查看",
                    )
                else -> Unit
            }
        }
    }

    private fun updateGenerationStallPresentation() {
        val current = mutablePresentation.value ?: return
        if (current.terminal || current.phase != OfflineSummaryRunPhase.ANALYZING) return
        if (activeForegroundTask?.summaryId != current.summaryId) return

        val progress = localLlmRuntime.generationProgress.value
        if (!progress.active) return
        val startedAt = progress.startedAtNanos ?: return
        val now = System.nanoTime()
        val stallMessage =
            if (progress.firstEmissionAtNanos == null) {
                if (now - startedAt >= FIRST_EMISSION_STALL_NANOS) {
                    FIRST_EMISSION_STALL_MESSAGE
                } else {
                    null
                }
            } else {
                val lastEmissionAt = progress.lastEmissionAtNanos ?: progress.firstEmissionAtNanos
                if (lastEmissionAt != null && now - lastEmissionAt >= NO_PROGRESS_STALL_NANOS) {
                    NO_PROGRESS_STALL_MESSAGE
                } else {
                    null
                }
            }

        val currentIsStall = current.message == FIRST_EMISSION_STALL_MESSAGE ||
            current.message == NO_PROGRESS_STALL_MESSAGE
        if (stallMessage == null && !currentIsStall) return
        if (stallMessage == current.message) return

        val updated = current.copy(message = stallMessage)
        mutablePresentation.value = updated
        LocalAiForegroundService.update(
            context = appContext,
            recordingId = updated.recordingId,
            summaryId = updated.summaryId,
            label = updated.label,
        )
    }

    private fun ensureForeground(state: OfflineSummaryRunState) {
        val desired = ActiveForegroundTask(state.recordingId, state.summaryId)
        val label = state.toPresentation().label
        if (activeForegroundTask == desired) {
            LocalAiForegroundService.update(
                context = appContext,
                recordingId = state.recordingId,
                summaryId = state.summaryId,
                label = label,
            )
            return
        }

        releaseForeground()
        val accepted =
            LocalAiForegroundService.acquire(
                context = appContext,
                recordingId = state.recordingId,
                summaryId = state.summaryId,
                label = label,
            )
        if (accepted) {
            activeForegroundTask = desired
        } else {
            reportForegroundFailure(
                recordingId = state.recordingId,
                summaryId = state.summaryId,
                failure = IllegalStateException("foreground-service request rejected"),
            )
        }
    }

    private fun releaseForeground() {
        if (activeForegroundTask == null) return
        LocalAiForegroundService.release(appContext)
        activeForegroundTask = null
    }

    private fun postTerminalNotification(
        recordingId: String,
        summaryId: String,
        title: String,
        text: String,
    ) {
        val notificationId = resultNotificationId(summaryId)
        notificationManager.notify(
            notificationId,
            Notification.Builder(appContext, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wenzhi)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(
                    recordingOpenPendingIntent(
                        context = appContext,
                        requestCode = notificationId,
                        recordingId = recordingId,
                        destination = RecordingDetailDestination.SUMMARY,
                    ),
                )
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build(),
        )
    }

    private fun createResultChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "本地 AI 总结结果",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "本地 AI 总结完成或失败提醒"
                setShowBadge(true)
            },
        )
    }

    private fun OfflineSummaryRunState.toPresentation(): OfflineSummaryTaskPresentation =
        OfflineSummaryTaskPresentation(
            recordingId = recordingId,
            summaryId = summaryId,
            phase = phase,
            message = message,
        )

    private fun resultNotificationId(summaryId: String): Int =
        RESULT_NOTIFICATION_BASE + ((summaryId.hashCode() and Int.MAX_VALUE) % RESULT_NOTIFICATION_RANGE)

    private companion object {
        const val RESULT_CHANNEL_ID = "voica-offline-summary-result"
        const val RESULT_NOTIFICATION_BASE = 210_000_000
        const val RESULT_NOTIFICATION_RANGE = 9_000_000
        const val GENERATION_WATCHDOG_INTERVAL_MS = 5_000L
        const val FIRST_EMISSION_STALL_NANOS = 120_000_000_000L
        const val NO_PROGRESS_STALL_NANOS = 90_000_000_000L
        const val FIRST_EMISSION_STALL_MESSAGE = "本地模型长时间未返回首个输出"
        const val NO_PROGRESS_STALL_MESSAGE = "本地模型长时间无输出进展"
    }
}

internal object OfflineSummaryExecutionControllerRegistry {
    private val controllers = WeakHashMap<AppContainer, OfflineSummaryExecutionController>()

    @Synchronized
    fun get(
        context: Context,
        container: AppContainer,
    ): OfflineSummaryExecutionController =
        controllers.getOrPut(container) {
            OfflineSummaryExecutionController(context.applicationContext, container)
        }

    fun cancel(context: Context, recordingId: String) {
        val app = context.applicationContext as? VoicaApplication ?: return
        val container = runCatching { app.container }.getOrNull() ?: return
        get(context, container).cancel(recordingId)
    }

    fun reportForegroundFailure(
        context: Context,
        recordingId: String,
        summaryId: String,
        failure: Throwable,
    ) {
        val app = context.applicationContext as? VoicaApplication ?: return
        val container = runCatching { app.container }.getOrNull() ?: return
        get(context, container).reportForegroundFailure(recordingId, summaryId, failure)
    }
}

class LocalAiForegroundService : Service() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE,
            ACTION_UPDATE,
            -> {
                val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)?.takeIf { it.isNotBlank() }
                val summaryId = intent.getStringExtra(EXTRA_SUMMARY_ID)?.takeIf { it.isNotBlank() }
                if (recordingId == null || summaryId == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }
                    ?: "本地总结 · 正在处理"
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(recordingId, summaryId, label),
                        if (Build.VERSION.SDK_INT >= 34) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        } else {
                            0
                        },
                    )
                    if (!wakeLock.isHeld) wakeLock.acquire()
                } catch (failure: RuntimeException) {
                    Log.e(TAG, "local AI foreground execution failed", failure)
                    OfflineSummaryExecutionControllerRegistry.reportForegroundFailure(
                        context = this,
                        recordingId = recordingId,
                        summaryId = summaryId,
                        failure = failure,
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        recordingId: String,
        summaryId: String,
        label: String,
    ): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wenzhi)
            .setContentTitle("闻知 · 本地 AI 总结")
            .setContentText(label)
            .setContentIntent(
                recordingOpenPendingIntent(
                    context = this,
                    requestCode = NOTIFICATION_ID,
                    recordingId = recordingId,
                    destination = RecordingDetailDestination.SUMMARY,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelPendingIntent(recordingId, summaryId),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setProgress(0, 0, true)
            .build()

    private fun cancelPendingIntent(recordingId: String, summaryId: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            summaryId.hashCode(),
            Intent(this, OfflineSummaryNotificationActionReceiver::class.java)
                .setAction(OfflineSummaryNotificationActionReceiver.ACTION_CANCEL)
                .putExtra(OfflineSummaryNotificationActionReceiver.EXTRA_RECORDING_ID, recordingId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本地 AI 总结",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "本地 AI 总结期间保持锁屏和后台执行"
                setShowBadge(false)
            },
        )
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    companion object {
        private const val TAG = "WenzhiLocalAiFGS"
        private const val CHANNEL_ID = "voica-local-ai"
        private const val NOTIFICATION_ID = 13071
        private const val WAKE_LOCK_TAG = "Voica:OfflineSummary"
        private const val ACTION_ACQUIRE = "io.github.ioannes78.voica.local_ai.ACQUIRE"
        private const val ACTION_UPDATE = "io.github.ioannes78.voica.local_ai.UPDATE"
        private const val EXTRA_RECORDING_ID = "recordingId"
        private const val EXTRA_SUMMARY_ID = "summaryId"
        private const val EXTRA_LABEL = "label"

        fun acquire(
            context: Context,
            recordingId: String,
            summaryId: String,
            label: String,
        ): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    taskIntent(context, ACTION_ACQUIRE, recordingId, summaryId, label),
                )
                true
            }.getOrElse { false }

        fun update(
            context: Context,
            recordingId: String,
            summaryId: String,
            label: String,
        ) {
            runCatching {
                context.startService(taskIntent(context, ACTION_UPDATE, recordingId, summaryId, label))
            }.onFailure { failure ->
                OfflineSummaryExecutionControllerRegistry.reportForegroundFailure(
                    context = context,
                    recordingId = recordingId,
                    summaryId = summaryId,
                    failure = failure,
                )
            }
        }

        fun release(context: Context) {
            runCatching { context.stopService(Intent(context, LocalAiForegroundService::class.java)) }
        }

        private fun taskIntent(
            context: Context,
            action: String,
            recordingId: String,
            summaryId: String,
            label: String,
        ): Intent =
            Intent(context, LocalAiForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
                .putExtra(EXTRA_SUMMARY_ID, summaryId)
                .putExtra(EXTRA_LABEL, label)
    }
}

class OfflineSummaryNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL) return
        val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)?.takeIf { it.isNotBlank() } ?: return
        OfflineSummaryExecutionControllerRegistry.cancel(context, recordingId)
    }

    companion object {
        const val ACTION_CANCEL = "io.github.ioannes78.voica.local_ai.CANCEL"
        const val EXTRA_RECORDING_ID = "recordingId"
    }
}

private fun OfflineSummaryRunPhase.isOfflineSummaryRunning(): Boolean =
    this == OfflineSummaryRunPhase.PREPARING ||
        this == OfflineSummaryRunPhase.ANALYZING ||
        this == OfflineSummaryRunPhase.VALIDATING

private fun OfflineSummaryRunPhase.isOfflineSummaryTerminal(): Boolean =
    this == OfflineSummaryRunPhase.COMPLETED ||
        this == OfflineSummaryRunPhase.FAILED ||
        this == OfflineSummaryRunPhase.CANCELLED
