package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.Stage12CContentRepository
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
    private val stage12CContentRepository: Stage12CContentRepository,
    private val stage13B5Qa4Repository: Stage13B5Qa4Repository,
    private val taskCompletionNoticeStore: TaskCompletionNoticeStore,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val presentationStore = NotificationPresentationStore(context)
    private var transcriptionTaskId: String? = null
    private var diarizationTaskId: String? = null
    private var transcriptionAttentionNotificationIds: Set<Int> = emptySet()
    private var completionNotificationIds: Set<Int> = emptySet()

    init {
        createAttentionChannel()
        createResultChannel()
        observeTranscription()
        observeDiarization()
        observeTranscriptionAttention()
        observeCompletionResults()
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
                        if (state is TranscriptionRunState.Completed) {
                            val currentId =
                                stage12CContentRepository.resolveCurrentTranscriptionId(
                                    state.recordingId,
                                )
                            if (currentId == state.transcriptionId) {
                                taskCompletionNoticeStore.publish(
                                    kind = TaskCompletionKind.TRANSCRIPTION,
                                    taskId = state.transcriptionId,
                                    recordingId = state.recordingId,
                                )
                            }
                        }
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
                        if (state is DiarizationRunState.Completed) {
                            taskCompletionNoticeStore.publish(
                                kind = TaskCompletionKind.DIARIZATION,
                                taskId = state.runId,
                                recordingId = state.recordingId,
                            )
                        }
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
                        val logicalKey = transcriptionAttentionPresentationKey(task)
                        if (!presentationStore.isSystemDismissed(logicalKey)) {
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
                                    logicalKey = logicalKey,
                                ),
                            )
                        }
                    }
                    (transcriptionAttentionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    transcriptionAttentionNotificationIds = nextIds
                }
        }
    }

    private fun observeCompletionResults() {
        scope.launch {
            combine(
                taskCompletionNoticeStore.pending,
                recordingRepository.recordings,
            ) { notices, recordings -> notices to recordings }
                .collect { (notices, recordings) ->
                    val nextIds = mutableSetOf<Int>()
                    notices.forEach { notice ->
                        val notificationId = completionNotificationId(notice)
                        val logicalKey = taskCompletionPresentationKey(notice)
                        if (!presentationStore.isSystemDismissed(logicalKey)) {
                            nextIds += notificationId
                            notificationManager.notify(
                                notificationId,
                                buildCompletionNotification(
                                    notice = notice,
                                    recordingName = recordingName(recordings, notice.recordingId),
                                    notificationId = notificationId,
                                    logicalKey = logicalKey,
                                ),
                            )
                        }
                    }
                    (completionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    completionNotificationIds = nextIds
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
        logicalKey: String,
    ): Notification =
        Notification.Builder(context, VoicaNotificationChannels.TASK_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_voica)
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
            .setDeleteIntent(
                notificationDismissPendingIntent(
                    context = context,
                    logicalKey = logicalKey,
                    requestCode = requestCode,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

    private fun buildCompletionNotification(
        notice: TaskCompletionNotice,
        recordingName: String,
        notificationId: Int,
        logicalKey: String,
    ): Notification {
        val destination =
            when (notice.kind) {
                TaskCompletionKind.TRANSCRIPTION,
                TaskCompletionKind.DIARIZATION,
                -> RecordingDetailDestination.TRANSCRIPT
                TaskCompletionKind.AI_SUMMARY -> RecordingDetailDestination.SUMMARY
            }
        val title =
            when (notice.kind) {
                TaskCompletionKind.TRANSCRIPTION -> "转写已完成"
                TaskCompletionKind.DIARIZATION -> "说话人分离已完成"
                TaskCompletionKind.AI_SUMMARY -> "总结已完成"
            }
        val text =
            when (notice.kind) {
                TaskCompletionKind.TRANSCRIPTION -> "转写结果已生成"
                TaskCompletionKind.DIARIZATION -> "说话人识别结果已生成"
                TaskCompletionKind.AI_SUMMARY -> "总结结果已生成"
            }
        return Notification.Builder(context, VoicaNotificationChannels.TASK_RESULT)
            .setSmallIcon(R.drawable.ic_stat_voica)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(recordingName)
            .setContentIntent(
                recordingOpenPendingIntent(
                    context = context,
                    requestCode = notificationId,
                    recordingId = notice.recordingId,
                    destination = destination,
                    completionKind = notice.kind,
                    completionTaskId = notice.taskId,
                ),
            )
            .setDeleteIntent(
                notificationDismissPendingIntent(
                    context = context,
                    logicalKey = logicalKey,
                    requestCode = notificationId,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    private fun createAttentionChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                VoicaNotificationChannels.TASK_ATTENTION,
                "任务需要处理",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "转写中断或失败时提醒"
                setShowBadge(true)
            },
        )
    }

    private fun createResultChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                VoicaNotificationChannels.TASK_RESULT,
                "任务结果",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "转写或说话人分离完成时提醒"
                setShowBadge(true)
            },
        )
    }

    companion object {
        internal const val TRANSCRIPTION_ATTENTION_BASE = 140_000_000
        internal const val TRANSCRIPTION_COMPLETION_BASE = 180_000_000
        internal const val DIARIZATION_COMPLETION_BASE = 190_000_000
        internal const val AI_SUMMARY_COMPLETION_BASE = 200_000_000
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

internal fun transcriptionAttentionPresentationKey(entity: TranscriptionEntity): String =
    "attention:transcription:${entity.id}:${entity.state}"

internal fun taskCompletionPresentationKey(notice: TaskCompletionNotice): String =
    "completion:${notice.kind.name}:${notice.taskId}"

private fun transcriptionAttentionNotificationId(id: String): Int =
    LongTaskNotificationController.TRANSCRIPTION_ATTENTION_BASE + stableNotificationOffset(id)

private fun completionNotificationId(notice: TaskCompletionNotice): Int =
    when (notice.kind) {
        TaskCompletionKind.TRANSCRIPTION -> LongTaskNotificationController.TRANSCRIPTION_COMPLETION_BASE
        TaskCompletionKind.DIARIZATION -> LongTaskNotificationController.DIARIZATION_COMPLETION_BASE
        TaskCompletionKind.AI_SUMMARY -> LongTaskNotificationController.AI_SUMMARY_COMPLETION_BASE
    } + stableNotificationOffset(notice.taskId)

private fun stableNotificationOffset(id: String): Int =
    (id.hashCode() and Int.MAX_VALUE) % 9_000_000

private fun Double.toPercent(): Int =
    (this * 100.0).roundToInt().coerceIn(0, 100)

private fun recordingName(
    recordings: List<RecordingLibraryItem>,
    recordingId: String,
): String =
    recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"
