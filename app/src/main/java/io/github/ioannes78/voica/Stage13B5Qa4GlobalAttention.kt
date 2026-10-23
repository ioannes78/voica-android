package io.github.ioannes78.voica

import android.app.NotificationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class Qa4VisibleDetailContext(
    val recordingId: String,
    val destination: RecordingDetailDestination,
)

internal object Stage13B5Qa4PageVisibility {
    private val mutableCurrent = MutableStateFlow<Qa4VisibleDetailContext?>(null)
    val current: StateFlow<Qa4VisibleDetailContext?> = mutableCurrent.asStateFlow()

    fun update(recordingId: String, destination: RecordingDetailDestination?) {
        mutableCurrent.value =
            destination?.let { Qa4VisibleDetailContext(recordingId = recordingId, destination = it) }
    }

    fun clear(recordingId: String) {
        if (mutableCurrent.value?.recordingId == recordingId) {
            mutableCurrent.value = null
        }
    }
}

internal data class Qa4GlobalAttentionItem(
    val key: String,
    val recordingId: String,
    val recordingName: String,
    val label: String,
    val destination: RecordingDetailDestination,
    val completionKind: TaskCompletionKind? = null,
    val completionTaskId: String? = null,
)

/** Presentation-only bridge consumed by the single global status surface. */
internal data class Qa4GlobalAttentionProjection(
    val items: List<Qa4GlobalAttentionItem> = emptyList(),
    val onOpen: (Qa4GlobalAttentionItem) -> Unit = {},
)

internal val LocalQa4GlobalAttentionProjection =
    staticCompositionLocalOf { Qa4GlobalAttentionProjection() }

@Composable
internal fun Stage13B5Qa4GlobalAttentionHost(
    container: AppContainer,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val notificationManager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    val presentationStore = remember(context) { NotificationPresentationStore(context) }
    val transcriptionAttention by
        container.stage13B5Qa4Repository.observeTranscriptionAttention()
            .collectAsState(initial = emptyList())
    val transcriptionCandidates by
        container.stage13B5Qa4Repository.observeTranscriptionCandidates()
            .collectAsState(initial = emptyList())
    val completionNotices by container.taskCompletionNoticeStore.pending.collectAsState()
    val diarizationAttention by
        Stage13CDiarizationAttentionRuntime.repository.observeAll()
            .collectAsState(initial = emptyList())
    val recordings by
        container.recordingLibraryRepository.recordings
            .collectAsState(initial = emptyList())
    val visibleDetail by Stage13B5Qa4PageVisibility.current.collectAsState()

    val completionItems =
        buildCompletionAttentionItems(
            notices = completionNotices,
            recordings = recordings,
        )
    val speakerAttentionItems =
        buildDiarizationGlobalAttentionItems(
            attention = diarizationAttention,
            recordings = recordings,
        )
    val items =
        filterQa4GlobalAttentionItems(
            items =
                buildQa4GlobalAttentionItems(
                    transcriptionAttention = transcriptionAttention,
                    transcriptionCandidates = transcriptionCandidates,
                    recordings = recordings,
                ) + completionItems + speakerAttentionItems,
            visibleDetail = visibleDetail,
        )

    // A navigation request alone is not proof that the user actually saw the result. Acknowledge
    // ordinary completion attention only when the transcript result destination is visibly active.
    LaunchedEffect(
        visibleDetail,
        completionNotices.map { notice -> "${notice.kind.name}:${notice.taskId}" },
    ) {
        val visible = visibleDetail ?: return@LaunchedEffect
        container.taskCompletionNoticeStore.acknowledgeVisible(
            recordingId = visible.recordingId,
            includeTranscriptResults = visible.destination == RecordingDetailDestination.TRANSCRIPT,
        )
    }

    // Candidate business truth remains untouched. Visiting the transcript page only clears or
    // suppresses that candidate's Android system notification; the in-app candidate remains until
    // the existing candidate lifecycle accepts/dismisses it.
    LaunchedEffect(
        visibleDetail,
        transcriptionCandidates.map(TranscriptionEntity::id),
    ) {
        val visible = visibleDetail ?: return@LaunchedEffect
        if (visible.destination == RecordingDetailDestination.TRANSCRIPT) {
            transcriptionCandidates
                .filter { it.recordingId == visible.recordingId }
                .forEach { candidate ->
                    val logicalKey = transcriptionCandidatePresentationKey(candidate.id)
                    presentationStore.markSystemDismissed(logicalKey)
                    notificationManager.cancel(transcriptionCandidateNotificationId(candidate.id))
                }
        }
    }

    CompositionLocalProvider(
        LocalQa4GlobalAttentionProjection provides
            Qa4GlobalAttentionProjection(
                items = items,
                onOpen = { item ->
                    AppRecordingNavigation.publish(
                        recordingId = item.recordingId,
                        destination = item.destination,
                    )
                },
            ),
    ) {
        content()
    }
}

internal fun filterQa4GlobalAttentionItems(
    items: List<Qa4GlobalAttentionItem>,
    visibleDetail: Qa4VisibleDetailContext?,
): List<Qa4GlobalAttentionItem> =
    if (visibleDetail == null) {
        items
    } else {
        items.filterNot { item ->
            item.recordingId == visibleDetail.recordingId &&
                item.destination == visibleDetail.destination
        }
    }

internal fun buildCompletionAttentionItems(
    notices: List<TaskCompletionNotice>,
    recordings: List<RecordingLibraryItem>,
): List<Qa4GlobalAttentionItem> {
    fun recordingName(recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"

    return notices.map { notice ->
        val label =
            when (notice.kind) {
                TaskCompletionKind.TRANSCRIPTION -> "转写完成"
                TaskCompletionKind.DIARIZATION -> "说话人分离完成"
            }
        Qa4GlobalAttentionItem(
            key = "completion:${notice.kind.name}:${notice.taskId}",
            recordingId = notice.recordingId,
            recordingName = recordingName(notice.recordingId),
            label = label,
            destination = RecordingDetailDestination.TRANSCRIPT,
            completionKind = notice.kind,
            completionTaskId = notice.taskId,
        )
    }
}

internal fun buildQa4GlobalAttentionItems(
    transcriptionAttention: List<TranscriptionEntity>,
    recordings: List<RecordingLibraryItem>,
    transcriptionCandidates: List<TranscriptionEntity> = emptyList(),
): List<Qa4GlobalAttentionItem> {
    fun recordingName(recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"

    return buildList {
        transcriptionAttention.forEach { transcription ->
            add(
                Qa4GlobalAttentionItem(
                    key = "transcription-attention:${transcription.id}",
                    recordingId = transcription.recordingId,
                    recordingName = recordingName(transcription.recordingId),
                    label =
                        if (transcription.state == TranscriptionStateValue.INTERRUPTED) {
                            "转写已中断"
                        } else {
                            "转写失败"
                        },
                    destination = RecordingDetailDestination.TRANSCRIPT,
                ),
            )
        }
        transcriptionCandidates.forEach { transcription ->
            add(
                Qa4GlobalAttentionItem(
                    key = "transcription-candidate:${transcription.id}",
                    recordingId = transcription.recordingId,
                    recordingName = recordingName(transcription.recordingId),
                    label = "新的转写结果已生成",
                    destination = RecordingDetailDestination.TRANSCRIPT,
                ),
            )
        }
    }
}
