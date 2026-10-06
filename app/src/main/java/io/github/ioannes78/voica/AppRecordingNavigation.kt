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
)

internal data class AppRecordingOpenRequest(
    val token: Long,
    val recordingId: String,
    val destination: RecordingDetailDestination,
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
    ): AppRecordingOpenRequest? {
        val safeRecordingId = recordingId.trim().takeIf { it.isNotEmpty() } ?: return null
        return AppRecordingOpenRequest(
            token = sequence.incrementAndGet(),
            recordingId = safeRecordingId,
            destination = destination,
        ).also { mutableRequest.value = it }
    }

    fun consumeIntent(intent: Intent?): AppRecordingOpenRequest? {
        val target = parseRecordingOpenIntent(intent) ?: return null
        intent?.removeExtra(EXTRA_RECORDING_ID)
        intent?.removeExtra(EXTRA_DESTINATION)
        return publish(target.recordingId, target.destination)
    }
}

internal fun recordingOpenIntent(
    context: Context,
    recordingId: String,
    destination: RecordingDetailDestination,
): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_RECORDING)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_RECORDING_ID, recordingId)
        .putExtra(EXTRA_DESTINATION, destination.name)

internal fun parseRecordingOpenIntent(intent: Intent?): RecordingOpenTarget? {
    if (intent?.action != ACTION_OPEN_RECORDING) return null
    val recordingId =
        intent.getStringExtra(EXTRA_RECORDING_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    val destinationName = intent.getStringExtra(EXTRA_DESTINATION) ?: return null
    val destination =
        RecordingDetailDestination.entries.firstOrNull { it.name == destinationName }
            ?: return null
    return RecordingOpenTarget(recordingId, destination)
}

internal fun recordingOpenPendingIntent(
    context: Context,
    requestCode: Int,
    recordingId: String,
    destination: RecordingDetailDestination,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        recordingOpenIntent(context, recordingId, destination),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private const val ACTION_OPEN_RECORDING = "io.github.ioannes78.voica.OPEN_RECORDING"
private const val EXTRA_RECORDING_ID = "io.github.ioannes78.voica.extra.RECORDING_ID"
private const val EXTRA_DESTINATION = "io.github.ioannes78.voica.extra.DESTINATION"
