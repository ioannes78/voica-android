package io.github.ioannes78.voica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordingOpenTarget(
    val recordingId: String,
    val destination: RecordingDetailDestination,
    val completionKind: TaskCompletionKind? = null,
    val completionTaskId: String? = null,
)

internal data class AppRecordingOpenRequest(
    val token: Long,
    val recordingId: String,
    val destination: RecordingDetailDestination,
    val completionKind: TaskCompletionKind? = null,
    val completionTaskId: String? = null,
)

/**
 * Process-local bridge between Android notification intents and the single Compose navigation owner.
 * Every publish receives a fresh token so repeated requests for the same recording/destination remain
 * commands rather than being collapsed as identical state.
 */
internal object AppRecordingNavigation {
    private val sequence = AtomicLong(0L)
    private val mutableRequest = MutableStateFlow<AppRecordingOpenRequest?>(null)
    val request: StateFlow<AppRecordingOpenRequest?> = mutableRequest.asStateFlow()

    fun publish(
        recordingId: String,
        destination: RecordingDetailDestination,
        completionKind: TaskCompletionKind? = null,
        completionTaskId: String? = null,
    ): AppRecordingOpenRequest? {
        val safeRecordingId = recordingId.trim().takeIf { it.isNotEmpty() } ?: return null
        val safeCompletionTaskId = completionTaskId?.trim()?.takeIf { it.isNotEmpty() }
        return AppRecordingOpenRequest(
            token = sequence.incrementAndGet(),
            recordingId = safeRecordingId,
            destination = destination,
            completionKind = completionKind.takeIf { safeCompletionTaskId != null },
            completionTaskId = safeCompletionTaskId.takeIf { completionKind != null },
        ).also { mutableRequest.value = it }
    }

    fun consumeIntent(intent: Intent?): AppRecordingOpenRequest? {
        val target = parseRecordingOpenIntent(intent) ?: return null
        intent?.removeExtra(EXTRA_RECORDING_ID)
        intent?.removeExtra(EXTRA_DESTINATION)
        intent?.removeExtra(EXTRA_COMPLETION_KIND)
        intent?.removeExtra(EXTRA_COMPLETION_TASK_ID)
        return publish(
            recordingId = target.recordingId,
            destination = target.destination,
            completionKind = target.completionKind,
            completionTaskId = target.completionTaskId,
        )
    }
}

internal fun recordingOpenIntent(
    context: Context,
    recordingId: String,
    destination: RecordingDetailDestination,
    completionKind: TaskCompletionKind? = null,
    completionTaskId: String? = null,
): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_RECORDING)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_RECORDING_ID, recordingId)
        .putExtra(EXTRA_DESTINATION, destination.name)
        .apply {
            if (completionKind != null && !completionTaskId.isNullOrBlank()) {
                putExtra(EXTRA_COMPLETION_KIND, completionKind.name)
                putExtra(EXTRA_COMPLETION_TASK_ID, completionTaskId)
            }
        }

internal fun parseRecordingOpenIntent(intent: Intent?): RecordingOpenTarget? =
    parseRecordingOpenTarget(
        action = intent?.action,
        recordingId = intent?.getStringExtra(EXTRA_RECORDING_ID),
        destinationName = intent?.getStringExtra(EXTRA_DESTINATION),
        completionKindName = intent?.getStringExtra(EXTRA_COMPLETION_KIND),
        completionTaskId = intent?.getStringExtra(EXTRA_COMPLETION_TASK_ID),
    )

internal fun parseRecordingOpenTarget(
    action: String?,
    recordingId: String?,
    destinationName: String?,
    completionKindName: String? = null,
    completionTaskId: String? = null,
): RecordingOpenTarget? {
    if (action != ACTION_OPEN_RECORDING) return null
    val safeRecordingId = recordingId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val destination =
        destinationName?.let { name ->
            RecordingDetailDestination.entries.firstOrNull { it.name == name }
        } ?: return null
    val safeCompletionTaskId = completionTaskId?.trim()?.takeIf { it.isNotEmpty() }
    val completionKind =
        completionKindName
            ?.let { name -> TaskCompletionKind.entries.firstOrNull { it.name == name } }
            ?.takeIf { safeCompletionTaskId != null }
    return RecordingOpenTarget(
        recordingId = safeRecordingId,
        destination = destination,
        completionKind = completionKind,
        completionTaskId = safeCompletionTaskId.takeIf { completionKind != null },
    )
}

internal fun recordingOpenPendingIntent(
    context: Context,
    requestCode: Int,
    recordingId: String,
    destination: RecordingDetailDestination,
    completionKind: TaskCompletionKind? = null,
    completionTaskId: String? = null,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        recordingOpenIntent(
            context = context,
            recordingId = recordingId,
            destination = destination,
            completionKind = completionKind,
            completionTaskId = completionTaskId,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal const val ACTION_OPEN_RECORDING = "io.github.ioannes78.voica.OPEN_RECORDING"
private const val EXTRA_RECORDING_ID = "io.github.ioannes78.voica.extra.RECORDING_ID"
private const val EXTRA_DESTINATION = "io.github.ioannes78.voica.extra.DESTINATION"
private const val EXTRA_COMPLETION_KIND = "io.github.ioannes78.voica.extra.COMPLETION_KIND"
private const val EXTRA_COMPLETION_TASK_ID = "io.github.ioannes78.voica.extra.COMPLETION_TASK_ID"
