package io.github.ioannes78.voica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object TaskNotificationActions {
    const val CANCEL_TRANSCRIPTION = "io.github.ioannes78.voica.task.CANCEL_TRANSCRIPTION"
    const val CANCEL_DIARIZATION = "io.github.ioannes78.voica.task.CANCEL_DIARIZATION"
    const val CANCEL_AI_SUMMARY = "io.github.ioannes78.voica.task.CANCEL_AI_SUMMARY"

    const val EXTRA_AI_SUMMARY_ID = "io.github.ioannes78.voica.task.extra.AI_SUMMARY_ID"
    const val EXTRA_AI_SUMMARY_GENERATION =
        "io.github.ioannes78.voica.task.extra.AI_SUMMARY_GENERATION"
}

internal data class AiSummaryCancelTarget(
    val summaryId: String,
    val executionGeneration: Long,
)

internal fun aiSummaryCancelIntent(
    context: Context,
    summaryId: String,
    executionGeneration: Long,
): Intent {
    require(summaryId.isNotBlank())
    require(executionGeneration >= 1L)
    return Intent(context, TaskNotificationActionReceiver::class.java)
        .setAction(TaskNotificationActions.CANCEL_AI_SUMMARY)
        // PendingIntent identity does not include extras. Keep generation in the data URI so an
        // old notification can never be updated into a newer generation's cancel action.
        .setData(
            Uri.Builder()
                .scheme("voica")
                .authority("task")
                .appendPath("ai-summary")
                .appendPath(summaryId)
                .appendPath(executionGeneration.toString())
                .appendPath("cancel")
                .build(),
        )
        .putExtra(TaskNotificationActions.EXTRA_AI_SUMMARY_ID, summaryId)
        .putExtra(TaskNotificationActions.EXTRA_AI_SUMMARY_GENERATION, executionGeneration)
}

internal fun parseAiSummaryCancelTarget(intent: Intent?): AiSummaryCancelTarget? {
    if (intent?.action != TaskNotificationActions.CANCEL_AI_SUMMARY) return null
    val summaryId =
        intent.getStringExtra(TaskNotificationActions.EXTRA_AI_SUMMARY_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
    val generation =
        intent.getLongExtra(TaskNotificationActions.EXTRA_AI_SUMMARY_GENERATION, -1L)
            .takeIf { it >= 1L }
            ?: return null
    return AiSummaryCancelTarget(summaryId, generation)
}

class TaskNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? VoicaApplication ?: return
        val container = runCatching { app.container }.getOrNull() ?: return
        when (intent?.action) {
            TaskNotificationActions.CANCEL_TRANSCRIPTION ->
                container.transcriptionCoordinator.cancel()
            TaskNotificationActions.CANCEL_DIARIZATION ->
                container.diarizationCoordinator.cancel()
            TaskNotificationActions.CANCEL_AI_SUMMARY -> {
                val target = parseAiSummaryCancelTarget(intent) ?: return
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // Room is the business truth. Terminal CAS must win before platform work is
                        // cancelled, so stale callbacks/workers cannot revive the task.
                        val cancelled =
                            runCatching {
                                container.aiSummaryRepository.cancelForGeneration(
                                    summaryId = target.summaryId,
                                    generation = target.executionGeneration,
                                )
                            }.getOrDefault(false)
                        if (cancelled) {
                            WorkManager.getInstance(context.applicationContext)
                                .cancelUniqueWork(AiSummaryWorkNames.unique(target.summaryId))
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
