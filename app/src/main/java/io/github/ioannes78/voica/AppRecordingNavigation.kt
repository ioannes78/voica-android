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

/** Process-local bridge between Android notification intents and the single Compose navigation owner. */
internal object AppRecordingNavigation {
    private val sequence = AtomicLong(0L)
    private val suppressNextPlainPlaybackUntilMs = AtomicLong(0L)
    private val mutableRequest = MutableStateFlow<AppRecordingOpenRequest?>(null)
    val request: StateFlow<AppRecordingOpenRequest?> = mutableRequest.asStateFlow()

    /**
     * Recorder-card realtime finalization historically published a plain PLAYBACK request after Stop.
     * V4 keeps the durable reconciliation but suppresses only that next automatic navigation command.
     */
    fun suppressNextRecorderRealtimePlaybackOpen(windowMs: Long = 30_000L) {
        suppressNextPlainPlaybackUntilMs.set(System.currentTimeMillis() + windowMs.coerceAtLeast(1L))
    }

    fun clearRecorderRealtimePlaybackSuppression() {
        suppressNextPlainPlaybackUntilMs.set(0L)
    }

    fun publish(
        recordingId: String,
        destination: RecordingDetailDestination,
        completionKind: TaskCompletionKind? = null,
        completionTaskId: String? = null,
    ): AppRecordingOpenRequest? {
        val safeRecordingId = recordingId.trim().takeIf { it.isNotEmpty() } ?: return null
        val safeCompletionTaskId = completionTaskId?.trim()?.takeIf { it.isNotEmpty() }
        if (
            destination == RecordingDetailDestination.PLAYBACK &&
            completionKind == null &&
            safeCompletionTaskId == null &&
            consumeRealtimePlaybackSuppression()
        ) {
            return null
        }
        return AppRecordingOpenRequest(
            token = sequence.incrementAndGet(),
            recordingId = safeRecordingId,
            destination = destination,
            completionKind = completionKind.takeIf { safeCompletionTaskId != null },
            completionTaskId = safeCompletionTaskId.takeIf { completionKind != null },
        ).also { mutableRequest.value = it }
    }

    /**
     * Stage 16A.1.5 keeps the existing single navigation owner in VoicaApp and must not introduce a
     * second recording-library surface. A library-root request therefore hands control to that owner
     * with a reserved, non-recording id. The library owner switches to the canonical library tab; the
     * unresolved id intentionally leaves no detail selected, so the existing full library route is
     * shown. TRANSCRIPT is used only as a non-playback handoff marker so realtime-stop playback
     * suppression can never consume this navigation request.
     */
    fun publishLibraryRoot(): AppRecordingOpenRequest? =
        publish(
            recordingId = LIBRARY_ROOT_NAVIGATION_ID,
            destination = RecordingDetailDestination.TRANSCRIPT,
        )

    private fun consumeRealtimePlaybackSuppression(): Boolean {
        while (true) {
            val until = suppressNextPlainPlaybackUntilMs.get()
            if (until <= 0L) return false
            if (System.currentTimeMillis() > until) {
                if (suppressNextPlainPlaybackUntilMs.compareAndSet(until, 0L)) return false
                continue
            }
            if (suppressNextPlainPlaybackUntilMs.compareAndSet(until, 0L)) return true
        }
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
    val destination = destinationName?.let { name ->
        RecordingDetailDestination.entries.firstOrNull { it.name == name }
    } ?: return null
    val safeCompletionTaskId = completionTaskId?.trim()?.takeIf { it.isNotEmpty() }
    val completionKind = completionKindName
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
): PendingIntent = PendingIntent.getActivity(
    context,
    requestCode,
    recordingOpenIntent(context, recordingId, destination, completionKind, completionTaskId),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

internal const val ACTION_OPEN_RECORDING = "io.github.ioannes78.voica.OPEN_RECORDING"
internal const val LIBRARY_ROOT_NAVIGATION_ID = "__voica_library_root__"
private const val EXTRA_RECORDING_ID = "io.github.ioannes78.voica.extra.RECORDING_ID"
private const val EXTRA_DESTINATION = "io.github.ioannes78.voica.extra.DESTINATION"
private const val EXTRA_COMPLETION_KIND = "io.github.ioannes78.voica.extra.COMPLETION_KIND"
private const val EXTRA_COMPLETION_TASK_ID = "io.github.ioannes78.voica.extra.COMPLETION_TASK_ID"
