package io.github.ioannes78.voica

import android.app.NotificationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val summaryExecutionController =
        remember(context.applicationContext, container) {
            OfflineSummaryExecutionControllerRegistry.get(context.applicationContext, container)
        }
    val summaryPresentation by summaryExecutionController.presentation.collectAsState()
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

    // Stage 17.2 summary presentation is process-local, while Room remains the business truth.
    // A running task is merely hidden on its own Summary page; a terminal presentation is consumed
    // only after the corresponding Summary page is actually visible.
    LaunchedEffect(
        visibleDetail,
        summaryPresentation?.summaryId,
        summaryPresentation?.phase,
    ) {
        val summary = summaryPresentation ?: return@LaunchedEffect
        val visible = visibleDetail ?: return@LaunchedEffect
        if (
            summary.terminal &&
            summary.recordingId == visible.recordingId &&
            visible.destination == RecordingDetailDestination.SUMMARY
        ) {
            summaryExecutionController.acknowledge(summary.summaryId)
        }
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

    val visibleSummaryPresentation =
        summaryPresentation?.takeUnless { summary ->
            visibleDetail?.recordingId == summary.recordingId &&
                visibleDetail?.destination == RecordingDetailDestination.SUMMARY
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
        Column(modifier = Modifier.fillMaxSize()) {
            visibleSummaryPresentation?.let { summary ->
                OfflineSummaryGlobalTaskStatusBar(
                    presentation = summary,
                    recordingName =
                        recordings.firstOrNull { it.id == summary.recordingId }?.displayName
                            ?: "录音",
                    onOpen = {
                        AppRecordingNavigation.publish(
                            recordingId = summary.recordingId,
                            destination = RecordingDetailDestination.SUMMARY,
                        )
                    },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

@Composable
private fun OfflineSummaryGlobalTaskStatusBar(
    presentation: OfflineSummaryTaskPresentation,
    recordingName: String,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                if (presentation.terminal) "任务通知" else "正在处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    presentation.label + " · " + recordingName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                presentation.trailingLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
        }
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
