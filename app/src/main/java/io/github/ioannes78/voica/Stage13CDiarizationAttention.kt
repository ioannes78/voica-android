package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.ioannes78.voica.database.DiarizationAlignmentAttentionRow
import io.github.ioannes78.voica.database.DiarizationAttentionItem
import io.github.ioannes78.voica.database.DiarizationAttentionKind
import io.github.ioannes78.voica.database.DiarizationAttentionRepository
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun DiarizationAttentionItem.productLabel(): String =
    when (kind) {
        DiarizationAttentionKind.DIARIZATION ->
            if (state == DiarizationStateValue.INTERRUPTED) {
                "说话人识别已中断"
            } else {
                "说话人识别失败"
            }
        DiarizationAttentionKind.ALIGNMENT ->
            if (state == TranscriptSpeakerAlignmentStateValue.INTERRUPTED) {
                "说话人处理已中断"
            } else {
                "说话人处理失败"
            }
    }

internal fun DiarizationAttentionItem.productMessage(): String =
    when (kind) {
        DiarizationAttentionKind.DIARIZATION ->
            if (state == DiarizationStateValue.INTERRUPTED) {
                "说话人识别在 App 退出或进程终止后中断。转写正文已保留，可重新识别或忽略。"
            } else {
                "说话人识别未完成。转写正文已保留，可重新识别或忽略。"
            }
        DiarizationAttentionKind.ALIGNMENT ->
            if (state == TranscriptSpeakerAlignmentStateValue.INTERRUPTED) {
                "说话人结果应用过程已中断。转写正文已保留，可重新应用或忽略。"
            } else {
                "说话人结果应用未完成。转写正文已保留，可重新应用或忽略。"
            }
    }

internal fun buildDiarizationGlobalAttentionItems(
    attention: List<DiarizationAttentionItem>,
    recordings: List<RecordingLibraryItem>,
): List<Qa4GlobalAttentionItem> {
    fun recordingName(recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"

    return attention.map { item ->
        Qa4GlobalAttentionItem(
            key = "diarization-attention:${item.kind.name}:${item.id}",
            recordingId = item.recordingId,
            recordingName = recordingName(item.recordingId),
            label = item.productLabel(),
            destination = RecordingDetailDestination.TRANSCRIPT,
        )
    }
}

/**
 * Resolves an older durable speaker attention only after a replacement durable row exists.
 */
class Stage13CDiarizationAttentionReconciler(
    scope: CoroutineScope,
    coordinator: DiarizationCoordinator,
    private val repository: DiarizationAttentionRepository,
) {
    init {
        scope.launch {
            coordinator.state.collect { state ->
                val running = state as? DiarizationRunState.Running ?: return@collect
                val runId = running.runId ?: return@collect
                repository.acknowledgeSupersededRun(
                    recordingId = running.recordingId,
                    replacementRunId = runId,
                )
            }
        }
        scope.launch {
            coordinator.alignmentState.collect { state ->
                val running = state as? SpeakerAlignmentRunState.Running ?: return@collect
                val alignmentId = running.alignmentId ?: return@collect
                repository.acknowledgeSupersededAlignment(
                    transcriptionId = running.transcriptionId,
                    diarizationRunId = running.diarizationRunId,
                    replacementAlignmentId = alignmentId,
                )
            }
        }
    }
}

/** Android-system projection of durable diarization/alignment attention. */
class Stage13CDiarizationAttentionNotificationController(
    context: Context,
    scope: CoroutineScope,
    recordingRepository: RecordingLibraryRepository,
    attentionRepository: DiarizationAttentionRepository,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var activeNotificationIds: Set<Int> = emptySet()

    init {
        createChannel()
        scope.launch {
            combine(
                attentionRepository.observeAll(),
                recordingRepository.recordings,
            ) { attention, recordings -> attention to recordings }
                .collect { (attention, recordings) ->
                    val next = mutableSetOf<Int>()
                    attention.forEach { item ->
                        val id = notificationId(item)
                        next += id
                        val recordingName =
                            recordings.firstOrNull { it.id == item.recordingId }?.displayName ?: "录音"
                        notificationManager.notify(id, buildNotification(item, recordingName, id))
                    }
                    (activeNotificationIds - next).forEach(notificationManager::cancel)
                    activeNotificationIds = next
                }
        }
    }

    private fun buildNotification(
        item: DiarizationAttentionItem,
        recordingName: String,
        notificationId: Int,
    ): Notification =
        Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(item.productLabel())
            .setContentText(item.productMessage())
            .setSubText(recordingName)
            .setContentIntent(
                recordingOpenPendingIntent(
                    context = appContext,
                    requestCode = notificationId,
                    recordingId = item.recordingId,
                    destination = RecordingDetailDestination.TRANSCRIPT,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "说话人任务状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "说话人识别或说话人处理被中断、失败时提醒"
                setShowBadge(true)
            },
        )
    }

    private fun notificationId(item: DiarizationAttentionItem): Int {
        val base =
            when (item.kind) {
                DiarizationAttentionKind.DIARIZATION -> DIARIZATION_ATTENTION_BASE
                DiarizationAttentionKind.ALIGNMENT -> ALIGNMENT_ATTENTION_BASE
            }
        return base + ((item.id.hashCode() and Int.MAX_VALUE) % 9_000_000)
    }

    private companion object {
        const val CHANNEL_ID = "voica-diarization-attention"
        const val DIARIZATION_ATTENTION_BASE = 210_000_000
        const val ALIGNMENT_ATTENTION_BASE = 220_000_000
    }
}
