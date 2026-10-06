package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Android notification/execution projection for the existing coordinator state machines.
 * Coordinators and Room remain authoritative; this controller never invents task state.
 */
class LongTaskNotificationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordingRepository: RecordingLibraryRepository,
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val diarizationCoordinator: DiarizationCoordinator,
    private val aiSummaryCoordinator: AiSummaryCoordinator,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var transcriptionTaskId: String? = null
    private var diarizationTaskId: String? = null

    init {
        createAiSummaryChannel()
        observeTranscription()
        observeDiarization()
        observeAiSummary()
    }

    private fun observeTranscription() {
        scope.launch {
            combine(
                transcriptionCoordinator.state,
                recordingRepository.recordings,
            ) { state, recordings -> state to recordings }
                .collect { (state, recordings) ->
                    val running = state as? TranscriptionRunState.Running
                    if (running == null) {
                        transcriptionTaskId?.let {
                            MediaProcessingForegroundService.release(context, it)
                        }
                        transcriptionTaskId = null
                        return@collect
                    }

                    val taskId = "transcription:${running.recordingId}"
                    val title = recordingName(recordings, running.recordingId)
                    val progress = running.progress.fraction?.toPercent()
                    val label = buildTranscriptionLabel(running, progress)
                    if (transcriptionTaskId == taskId) {
                        MediaProcessingForegroundService.update(
                            context = context,
                            taskId = taskId,
                            title = title,
                            label = label,
                            progressPercent = progress,
                            cancelAction = TaskNotificationActions.CANCEL_TRANSCRIPTION,
                        )
                    } else {
                        transcriptionTaskId?.let {
                            MediaProcessingForegroundService.release(context, it)
                        }
                        MediaProcessingForegroundService.acquire(
                            context = context,
                            taskId = taskId,
                            title = title,
                            label = label,
                            progressPercent = progress,
                            cancelAction = TaskNotificationActions.CANCEL_TRANSCRIPTION,
                        )
                        transcriptionTaskId = taskId
                    }
                }
        }
    }

    private fun observeDiarization() {
        scope.launch {
            combine(
                diarizationCoordinator.state,
                recordingRepository.recordings,
            ) { state, recordings -> state to recordings }
                .collect { (state, recordings) ->
                    val running = state as? DiarizationRunState.Running
                    if (running == null) {
                        diarizationTaskId?.let {
                            MediaProcessingForegroundService.release(context, it)
                        }
                        diarizationTaskId = null
                        return@collect
                    }

                    val taskId = "diarization:${running.recordingId}"
                    val title = recordingName(recordings, running.recordingId)
                    val progress = running.progress.fraction?.toPercent()
                    val label = buildDiarizationLabel(running, progress)
                    if (diarizationTaskId == taskId) {
                        MediaProcessingForegroundService.update(
                            context = context,
                            taskId = taskId,
                            title = title,
                            label = label,
                            progressPercent = progress,
                            cancelAction = TaskNotificationActions.CANCEL_DIARIZATION,
                        )
                    } else {
                        diarizationTaskId?.let {
                            MediaProcessingForegroundService.release(context, it)
                        }
                        MediaProcessingForegroundService.acquire(
                            context = context,
                            taskId = taskId,
                            title = title,
                            label = label,
                            progressPercent = progress,
                            cancelAction = TaskNotificationActions.CANCEL_DIARIZATION,
                        )
                        diarizationTaskId = taskId
                    }
                }
        }
    }

    private fun observeAiSummary() {
        scope.launch {
            combine(
                aiSummaryCoordinator.state,
                recordingRepository.recordings,
            ) { state, recordings -> state to recordings }
                .collect { (state, recordings) ->
                    val running = state as? AiSummaryRunState.Running
                    val recordingId = running?.recordingId
                    if (running == null || recordingId == null) {
                        notificationManager.cancel(AI_SUMMARY_NOTIFICATION_ID)
                        return@collect
                    }
                    val title = recordingName(recordings, recordingId)
                    notificationManager.notify(
                        AI_SUMMARY_NOTIFICATION_ID,
                        buildAiSummaryNotification(title, running),
                    )
                }
        }
    }

    private fun buildAiSummaryNotification(
        title: String,
        running: AiSummaryRunState.Running,
    ): Notification {
        val progress =
            if (running.totalUnits > 0) {
                ((running.completedUnits.toDouble() / running.totalUnits.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                null
            }
        return Notification.Builder(context, AI_SUMMARY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(buildAiSummaryLabel(running))
            .setContentIntent(mainActivityPendingIntent(context, AI_SUMMARY_REQUEST_CONTENT))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消生成",
                cancelPendingIntent(
                    TaskNotificationActions.CANCEL_AI_SUMMARY,
                    AI_SUMMARY_REQUEST_CANCEL,
                ),
            )
            .build()
    }

    private fun cancelPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TaskNotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createAiSummaryChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                AI_SUMMARY_CHANNEL_ID,
                "AI 总结",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "AI 总结生成进度"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val AI_SUMMARY_CHANNEL_ID = "voica-ai-summary"
        private const val AI_SUMMARY_NOTIFICATION_ID = 13051
        private const val AI_SUMMARY_REQUEST_CONTENT = 13051
        private const val AI_SUMMARY_REQUEST_CANCEL = 13052
    }
}

internal fun buildTranscriptionLabel(
    running: TranscriptionRunState.Running,
    progressPercent: Int?,
): String {
    val model =
        when {
            running.modelId?.contains("qwen", ignoreCase = true) == true -> "Qwen3-ASR"
            running.modelId?.contains("sensevoice", ignoreCase = true) == true -> "SenseVoice"
            else -> "本地模型"
        }
    val phase =
        when (running.progress.phase) {
            TranscriptionPhase.PREPARING -> "正在准备"
            TranscriptionPhase.VAD -> "正在分析语音"
            TranscriptionPhase.FIRST_PASS,
            TranscriptionPhase.SECOND_PASS,
            -> "正在识别"
            TranscriptionPhase.PUNCTUATION -> "正在处理标点"
            TranscriptionPhase.PERSISTING -> "正在保存结果"
        }
    return buildString {
        append("转写中 · ")
        append(model)
        append(" · ")
        append(phase)
        progressPercent?.let {
            append(" · ")
            append(it)
            append('%')
        }
    }
}

internal fun buildDiarizationLabel(
    running: DiarizationRunState.Running,
    progressPercent: Int?,
): String {
    val phase =
        when (running.progress.phase) {
            DiarizationPhase.PREPARING -> "正在准备"
            DiarizationPhase.VAD -> "正在分析语音"
            DiarizationPhase.DIARIZATION -> "正在分析说话人"
            DiarizationPhase.STITCHING -> "正在合并说话人片段"
            DiarizationPhase.PERSISTING -> "正在保存结果"
        }
    return buildString {
        append("说话人分离 · ")
        append(phase)
        progressPercent?.let {
            append(" · ")
            append(it)
            append('%')
        }
    }
}

internal fun buildAiSummaryLabel(running: AiSummaryRunState.Running): String =
    when (running.phase) {
        AiSummaryEnginePhase.PREPARING -> "AI 总结 · 正在准备当前转写"
        AiSummaryEnginePhase.ANALYZING -> "AI 总结 · 正在分析内容"
        AiSummaryEnginePhase.MAPPING ->
            if (running.totalUnits > 0) {
                "AI 总结 · 正在生成 ${running.completedUnits}/${running.totalUnits}"
            } else {
                "AI 总结 · 正在生成"
            }
        AiSummaryEnginePhase.REDUCING ->
            if (running.totalUnits > 0) {
                "AI 总结 · 正在合并 ${running.completedUnits}/${running.totalUnits}"
            } else {
                "AI 总结 · 正在合并"
            }
        AiSummaryEnginePhase.VALIDATING -> "AI 总结 · 正在校验结果"
    }

private fun Double.toPercent(): Int =
    (this * 100.0).roundToInt().coerceIn(0, 100)

private fun recordingName(
    recordings: List<RecordingLibraryItem>,
    recordingId: String,
): String =
    recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"
