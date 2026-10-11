package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DeviceSessionNotificationDisplaySnapshot(
    val leaseGeneration: Long? = null,
    val desiredRevision: Long = 0L,
    val desiredDeviceName: String? = null,
    val desiredLabel: String? = null,
    val renderedRevision: Long = 0L,
    val renderedDeviceName: String? = null,
    val renderedLabel: String? = null,
)

internal object DeviceSessionNotificationDisplayDiagnostics {
    private val revisionSequence = AtomicLong(0L)
    private val mutableState = MutableStateFlow(DeviceSessionNotificationDisplaySnapshot())
    val state: StateFlow<DeviceSessionNotificationDisplaySnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun recordDesired(
        leaseGeneration: Long,
        deviceName: String,
        label: String,
    ): Long {
        val revision = revisionSequence.incrementAndGet()
        val previous = mutableState.value
        mutableState.value =
            previous.copy(
                leaseGeneration = leaseGeneration,
                desiredRevision = revision,
                desiredDeviceName = deviceName,
                desiredLabel = label,
            )
        return revision
    }

    @Synchronized
    fun markRendered(
        leaseGeneration: Long,
        revision: Long,
        deviceName: String,
        label: String,
    ) {
        val current = mutableState.value
        if (current.leaseGeneration != leaseGeneration) return
        if (revision < current.renderedRevision) return
        mutableState.value =
            current.copy(
                renderedRevision = revision,
                renderedDeviceName = deviceName,
                renderedLabel = label,
            )
    }

    @Synchronized
    fun reset() {
        mutableState.value = DeviceSessionNotificationDisplaySnapshot()
    }
}

/**
 * Updates the already-running connected-device foreground notification without enqueueing another
 * startForegroundService command. The service owns the lease; this helper only updates display state.
 */
internal object DeviceSessionNotificationUpdater {
    private const val CHANNEL_ID = "voica-device-session"
    private const val NOTIFICATION_ID = 13031
    private const val REQUEST_CONTENT = 13031

    fun update(
        context: Context,
        deviceName: String,
        label: String,
    ) {
        val safeName = deviceName.trim().ifEmpty { "录音卡" }
        val safeLabel = label.trim().ifEmpty { "保持连接" }
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(safeName)
                .setContentText(safeLabel)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        REQUEST_CONTENT,
                        Intent(context, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
