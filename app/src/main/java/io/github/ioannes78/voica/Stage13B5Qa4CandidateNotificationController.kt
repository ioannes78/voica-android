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
    private val presentationStore = NotificationPresentationStore(context)
    private var transcriptionNotificationIds: Set<Int> = emptySet()

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
                        val logicalKey = transcriptionCandidatePresentationKey(candidate.id)
                        if (!presentationStore.isSystemDismissed(logicalKey)) {
                            nextIds += notificationId
                            notificationManager.notify(
                                notificationId,
                                buildNotification(
                                    notificationId = notificationId,
                                    logicalKey = logicalKey,
                                    title = "转写已完成",
                                    text = "新的转写结果已生成",
                                    recordingId = candidate.recordingId,
                                    recordingName = recordingName(recordings, candidate.recordingId),
                                    destination = RecordingDetailDestination.TRANSCRIPT,
                                ),
                            )
                        }
                    }
                    (transcriptionNotificationIds - nextIds).forEach(notificationManager::cancel)
                    transcriptionNotificationIds = nextIds
                }
        }
    }

    private fun buildNotification(
        notificationId: Int,
        logicalKey: String,
        title: String,
        text: String,
        recordingId: String,
        recordingName: String,
        destination: RecordingDetailDestination,
    ): Notification =
        Notification.Builder(context, VoicaNotificationChannels.TASK_RESULT)
            .setSmallIcon(R.drawable.ic_stat_wenzhi)
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
            .setDeleteIntent(
                notificationDismissPendingIntent(
                    context = context,
                    logicalKey = logicalKey,
                    requestCode = notificationId,
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
                VoicaNotificationChannels.TASK_RESULT,
                "任务结果",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "转写生成新结果时提醒"
                setShowBadge(true)
            },
        )
    }

    private fun recordingName(recordings: List<RecordingLibraryItem>, recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"
}

internal fun transcriptionCandidatePresentationKey(id: String): String =
    "candidate:transcription:$id"

internal fun transcriptionCandidateNotificationId(id: String): Int =
    TRANSCRIPTION_CANDIDATE_BASE + candidateStableOffset(id)

private fun candidateStableOffset(id: String): Int = id.hashCode() and 0x000f_ffff

private const val TRANSCRIPTION_CANDIDATE_BASE = 160_000_000
