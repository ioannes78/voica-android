package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.Stage13B5Qa4Repository
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LongTaskNotificationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordingRepository: RecordingLibraryRepository,
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val diarizationCoordinator: DiarizationCoordinator,
    private val aiSummaryCoordinator: AiSummaryCoordinator,
    private val aiSummaryRepository: AiSummaryRepository,
    private val stage13B5Qa4Repository: Stage13B5Qa4Repository,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var transcriptionTaskId: String? = null
    private var diarizationTaskId: String? = null
    private var transcriptionAttentionNotificationIds: Set<Int> = emptySet()
    private var aiAttentionNotificationIds: Set<Int> = emptySet()

    init {
        createAiSummaryChannel()
        createAttentionChannel()
        observeTranscription()
        observeDiarization()
        observeAiSummary()
        observeTranscriptionAttention()
        observeAiSummaryAttention()
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
                            recordingId = running.recordingId,
                            destination = RecordingDetailDestination.TRANSCRIPT,
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
                            recordingId = running.recordingId,
                            destination = RecordingDetailDestination.TRANSCRIPT,
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
                            recordingId = running.recordingId,
                            destination = RecordingDetailDestination.TRANSCRIPT,
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
                            recordingId = running.recordingId,
                            destination = RecordingDetailDestination.TRANSCRIPT,
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

                    val cancelIntent =
                        running.summaryId?.let { summaryId ->
                            val durable = aiSummaryRepository.find(summaryId)
                            if (
                                durable == null ||
                                durable.status !in AiSummaryStateValue.ACTIVE ||
                                durable.executionGeneration < 1L
                            ) {
                                notificationManager.cancel(AI_SUMMARY_NOTIFICATION_ID)
                                return@collect
                            }
                            PendingIntent.getBroadcast(
                                context,
                                AI_SUMMARY_REQUEST_CANCEL,
                                aiSummaryCancelIntent(
                                    context = context,
                                    summaryId = summaryId,
                                    executionGeneration = durable.executionGeneration,
                                ),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        }

                    val title = recordingName(recordings, recordingId)
                    notificationManager.notify(
                        AI_SUMMARY_NOTIFICATION_ID,
                        buildAiSummaryNotification(
                            title = title,
                            running = running,
                            recordingId = recordingId,
                            cancelIntent = cancelIntent,
                        ),
                    )
                }
        }
    }

    private fun observeTranscriptionAttention() {
        scope.launch {
            combine(
                stage13B5Qa4Repository.observeTranscriptionAttention(),
                recordingRepository.recordings,
            ) { tasks, recordings -> tasks to recordings }
                .collect { (tasks, recordings) ->
                    val nextIds = mutableSetOf<Int>()
                    tasks.forEach { task ->
                        val notificationId = transcriptionAttentionNotificationId(task.id)
                        nextIds += notificationId
                        notificationManager.notify(
                            notificationId,
                            buildAttentionNotification(
                                title = transcriptionAttentionTitle(task),
                                text = transcriptionAttentionText(task),
                                recordingId = task.recordingId,
                                recordingName = recordingName(recordings, task.recordingId),
                                destination = RecordingDetailDestination.TRANSCRIPT,
                                requestCode = notificationId,
                            ),
                        )
                    }
                    (transcriptionAttentionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    transcriptionAttentionNotificationIds = nextIds
                }
        }
    }

    private fun observeAiSummaryAttention() {
        scope.launch {
            combine(
                stage13B5Qa4Repository.observeAiSummaryAttention(),
                recordingRepository.recordings,
            ) { tasks, recordings -> tasks to recordings }
                .collect { (tasks, recordings) ->
                    val nextIds = mutableSetOf<Int>()
                    tasks.forEach { task ->
                        val notificationId = aiAttentionNotificationId(task.id)
                        nextIds += notificationId
                        notificationManager.notify(
                            notificationId,
                            buildAttentionNotification(
                                title = aiAttentionTitle(task),
                                text = aiAttentionText(task),
                                recordingId = task.recordingId,
                                recordingName = recordingName(recordings, task.recordingId),
                                destination = RecordingDetailDestination.SUMMARY,
                                requestCode = notificationId,
                            ),
                        )
                    }
                    (aiAttentionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    aiAttentionNotificationIds = nextIds
                }
        }
    }

    private fun buildAttentionNotification(
        title: String,
        text: String,
        recordingId: String,
        recordingName: String,
        destination: RecordingDetailDestination,
        requestCode: Int,
    ): Notification =
        Notification.Builder(context, ATTENTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(recordingName)
            .setContentIntent(
                recordingOpenPendingIntent(
                    context = context,
                    requestCode = requestCode,
                    recordingId = recordingId,
                    destination = destination,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

    private fun buildAiSummaryNotification(
        title: String,
        running: AiSummaryRunState.Running,
        recordingId: String,
        cancelIntent: PendingIntent?,
    ): Notification {
        val progress =
            if (running.totalUnits > 0) {
                ((running.completedUnits.toDouble() / running.totalUnits.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                null
            }
        val builder =
            Notification.Builder(context, AI_SUMMARY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(buildAiSummaryLabel(running))
                .setContentIntent(
                    recordingOpenPendingIntent(
                        context = context,
                        requestCode = AI_SUMMARY_REQUEST_CONTENT,
                        recordingId = recordingId,
                        destination = RecordingDetailDestination.SUMMARY,
                    ),
                )
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, progress ?: 0, progress == null)
        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消生成",
                cancelIntent,
            )
        }
        return builder.build()
    }

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

    private fun createAttentionChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ATTENTION_CHANNEL_ID,
                "任务需要处理",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "转写或 AI 总结中断、失败或状态无法确认时提醒"
                setShowBadge(true)
            },
        )
    }

    companion object {
        private const val AI_SUMMARY_CHANNEL_ID = "voica-ai-summary"
        private const val ATTENTION_CHANNEL_ID = "voica-task-attention"
        private const val AI_SUMMARY_NOTIFICATION_ID = 13051
        private const val AI_SUMMARY_REQUEST_CONTENT = 13051
        private const val AI_SUMMARY_REQUEST_CANCEL = 13052
        private const val TRANSCRIPTION_ATTENTION_BASE = 140_000_000
        private const val AI_ATTENTION_BASE = 150_000_000
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
            DiarizationPhase.ALIGNMENT -> "正在对齐转写"
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

internal fun transcriptionAttentionTitle(entity: TranscriptionEntity): String =
    when (entity.state) {
        TranscriptionStateValue.INTERRUPTED -> "转写已中断"
        else -> "转写失败"
    }

internal fun transcriptionAttentionText(entity: TranscriptionEntity): String =
    when (entity.state) {
        TranscriptionStateValue.INTERRUPTED -> "上一次转写未完成，请重新进行转写"
        else -> "上一次转写没有完成，请返回 Voica 重新转写"
    }

internal fun aiAttentionTitle(entity: AiSummaryEntity): String =
    when (entity.status) {
        AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT -> "AI 总结需要处理"
        AiSummaryStateValue.INTERRUPTED -> "AI 总结已中断"
        else -> "AI 总结生成失败"
    }

internal fun aiAttentionText(entity: AiSummaryEntity): String =
    when (entity.status) {
        AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT -> "上一次总结请求状态无法确认"
        AiSummaryStateValue.INTERRUPTED -> "上一次总结未完成，请返回 Voica 处理"
        else -> "AI 服务请求失败，请选择 AI 服务或模型重新生成"
    }

private fun transcriptionAttentionNotificationId(id: String): Int =
    TRANSCRIPTION_ATTENTION_BASE + stableNotificationOffset(id)

private fun aiAttentionNotificationId(id: String): Int =
    AI_ATTENTION_BASE + stableNotificationOffset(id)

private fun stableNotificationOffset(id: String): Int =
    (id.hashCode() and Int.MAX_VALUE) % 9_000_000

private fun Double.toPercent(): Int =
    (this * 100.0).roundToInt().coerceIn(0, 100)

private fun recordingName(
    recordings: List<RecordingLibraryItem>,
    recordingId: String,
): String =
    recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"
