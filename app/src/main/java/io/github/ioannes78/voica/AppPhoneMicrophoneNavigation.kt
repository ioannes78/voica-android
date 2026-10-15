package io.github.ioannes78.voica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PhoneMicrophoneProductTarget {
    RECORDING,
    REALTIME,
}

internal data class AppPhoneMicrophoneOpenRequest(
    val token: Long,
    val target: PhoneMicrophoneProductTarget,
)

/** Process-local bridge from the phone microphone FGS notification into the active live product. */
internal object AppPhoneMicrophoneNavigation {
    private val sequence = AtomicLong(0L)
    private val mutableRequest = MutableStateFlow<AppPhoneMicrophoneOpenRequest?>(null)
    val request: StateFlow<AppPhoneMicrophoneOpenRequest?> = mutableRequest.asStateFlow()

    fun publish(target: PhoneMicrophoneProductTarget): AppPhoneMicrophoneOpenRequest =
        AppPhoneMicrophoneOpenRequest(
            token = sequence.incrementAndGet(),
            target = target,
        ).also { mutableRequest.value = it }

    fun dismiss() {
        mutableRequest.value = null
    }

    fun consumeIntent(intent: Intent?): AppPhoneMicrophoneOpenRequest? {
        val target = phoneMicrophoneTargetForAction(intent?.action) ?: return null
        intent?.action = null
        return publish(target)
    }
}

internal fun phoneMicrophoneTargetForAction(action: String?): PhoneMicrophoneProductTarget? =
    when (action) {
        ACTION_OPEN_PHONE_RECORDING -> PhoneMicrophoneProductTarget.RECORDING
        ACTION_OPEN_PHONE_REALTIME -> PhoneMicrophoneProductTarget.REALTIME
        else -> null
    }

internal fun phoneMicrophoneProductTarget(realtimeActiveRunId: String?): PhoneMicrophoneProductTarget =
    if (realtimeActiveRunId.isNullOrBlank()) {
        PhoneMicrophoneProductTarget.RECORDING
    } else {
        PhoneMicrophoneProductTarget.REALTIME
    }

internal fun phoneMicrophoneOpenIntent(
    context: Context,
    target: PhoneMicrophoneProductTarget,
): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(
            when (target) {
                PhoneMicrophoneProductTarget.RECORDING -> ACTION_OPEN_PHONE_RECORDING
                PhoneMicrophoneProductTarget.REALTIME -> ACTION_OPEN_PHONE_REALTIME
            },
        )
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun phoneMicrophoneOpenPendingIntent(
    context: Context,
    target: PhoneMicrophoneProductTarget,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        phoneMicrophoneOpenIntent(context, target),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal const val ACTION_OPEN_PHONE_RECORDING =
    "io.github.ioannes78.voica.OPEN_PHONE_RECORDING"
internal const val ACTION_OPEN_PHONE_REALTIME =
    "io.github.ioannes78.voica.OPEN_PHONE_REALTIME"
