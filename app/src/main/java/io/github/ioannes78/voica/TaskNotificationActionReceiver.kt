package io.github.ioannes78.voica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object TaskNotificationActions {
    const val CANCEL_TRANSCRIPTION = "io.github.ioannes78.voica.task.CANCEL_TRANSCRIPTION"
    const val CANCEL_DIARIZATION = "io.github.ioannes78.voica.task.CANCEL_DIARIZATION"
    const val CANCEL_AI_SUMMARY = "io.github.ioannes78.voica.task.CANCEL_AI_SUMMARY"
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
            TaskNotificationActions.CANCEL_AI_SUMMARY ->
                container.aiSummaryCoordinator.cancel()
        }
    }
}
