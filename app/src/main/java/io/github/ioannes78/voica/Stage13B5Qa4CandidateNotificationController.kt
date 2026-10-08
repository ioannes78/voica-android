package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.Stage13B5Qa4Repository
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class Stage13B5Qa4CandidateNotificationController(
    private val context: Context,
    scope: CoroutineScope,
    private val recordingRepository: RecordingLibraryRepository,
    private val qa4Repository: Stage13B5Qa4Repository,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var transcriptionNotificationIds: Set<Int> = emptySet()
    private var aiSummaryNotificationIds: Set<Int> = emptySet()

    init {
        createChannel()
        scope.launch {
            combine(
                qa4Repository.observeTranscriptionCandidates(),
                recordingRepository.recordings,
            ) { candidates, recordings -> candidates to recordings }
                .collect { (candidates, recordings) ->
                    val nextIds = mutableSetOf<Int>()
                    candidates.forEach { candidate ->
                        val notificationId = transcriptionCandidateNotificationId(candidate.id)
                        nextIds += notificationId
                        notificationManager.notify(
                            notificationId,
                            buildNotification(
                                notificationId = notificationId,
                                title = "转写已完成",
                                text = "新的转写结果已生成",
                                recordingId = candidate.recordingId,
                                recordingName = recordingName(recordings, candidate.recordingId),
                                destination = RecordingDetailDestination.TRANSCRIPT,
                            ),
                        )
                    }
                    (transcriptionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    transcriptionNotificationIds = nextIds
                }
        }
        scope.launch {
            combine(
                qa4Repository.observeAiSummaryCandidates(),
                recordingRepository.recordings,
            ) { candidates, recordings -> candidates to recordings }
                .collect { (candidates, recordings) ->
                    val nextIds = mutableSetOf<Int>()
                    candidates.forEach { candidate ->
                        val notificationId = aiSummaryCandidateNotificationId(candidate.id)
                        nextIds += notificationId
                        notificationManager.notify(
                            notificationId,
                            buildNotification(
                                notificationId = notificationId,
                                title = "AI 总结已完成",
                                text = "新的总结结果已生成",
                                recordingId = candidate.recordingId,
                                recordingName = recordingName(recordings, candidate.recordingId),
                                destination = RecordingDetailDestination.SUMMARY,
                            ),
                        )
                    }
                    (aiSummaryNotificationIds - nextIds).forEach(notificationManager::cancel)
                    aiSummaryNotificationIds = nextIds
                }
        }
    }

    private fun buildNotification(
        notificationId: Int,
        title: String,
        text: String,
        recordingId: String,
        recordingName: String,
        destination: RecordingDetailDestination,
    ): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(recordingName)
            .setContentIntent(
                recordingOpenPendingIntent(
                    context = context,
                    requestCode = notificationId,
                    recordingId = recordingId,
                    destination = destination,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "任务结果",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "转写或 AI 总结生成新结果时提醒"
                setShowBadge(true)
            },
        )
    }

    private fun recordingName(recordings: List<RecordingLibraryItem>, recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"

    private fun transcriptionCandidateNotificationId(id: String): Int =
        TRANSCRIPTION_CANDIDATE_BASE + stableOffset(id)

    private fun aiSummaryCandidateNotificationId(id: String): Int =
        AI_SUMMARY_CANDIDATE_BASE + stableOffset(id)

    private fun stableOffset(id: String): Int = id.hashCode() and 0x000f_ffff

    private companion object {
        const val CHANNEL_ID = "voica-task-result"
        const val TRANSCRIPTION_CANDIDATE_BASE = 160_000_000
        const val AI_SUMMARY_CANDIDATE_BASE = 170_000_000
    }
}
