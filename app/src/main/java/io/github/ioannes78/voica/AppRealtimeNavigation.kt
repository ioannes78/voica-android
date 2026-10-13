package io.github.ioannes78.voica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AppRealtimeOpenRequest(
    val token: Long,
)

/** Process-local bridge from the connected-device notification into the live realtime reader. */
internal object AppRealtimeNavigation {
    private val sequence = AtomicLong(0L)
    private val mutableRequest = MutableStateFlow<AppRealtimeOpenRequest?>(null)
    val request: StateFlow<AppRealtimeOpenRequest?> = mutableRequest.asStateFlow()

    fun publish(): AppRealtimeOpenRequest =
        AppRealtimeOpenRequest(sequence.incrementAndGet()).also { mutableRequest.value = it }

    fun dismiss() {
        mutableRequest.value = null
    }

    fun consumeIntent(intent: Intent?): AppRealtimeOpenRequest? {
        if (!isRealtimeOpenAction(intent?.action)) return null
        intent?.action = null
        return publish()
    }
}

internal fun isRealtimeOpenAction(action: String?): Boolean = action == ACTION_OPEN_REALTIME

internal fun realtimeOpenIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_REALTIME)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun realtimeOpenPendingIntent(
    context: Context,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        realtimeOpenIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal const val ACTION_OPEN_REALTIME = "io.github.ioannes78.voica.OPEN_REALTIME"
