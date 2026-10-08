package io.github.ioannes78.voica

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryStateValue
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
)

@Composable
internal fun Stage13B5Qa4GlobalAttentionHost(
    container: AppContainer,
    content: @Composable () -> Unit,
) {
    val transcriptionAttention by
        container.stage13B5Qa4Repository.observeTranscriptionAttention()
            .collectAsState(initial = emptyList())
    val aiSummaryAttention by
        container.stage13B5Qa4Repository.observeAiSummaryAttention()
            .collectAsState(initial = emptyList())
    val transcriptionCandidates by
        container.stage13B5Qa4Repository.observeTranscriptionCandidates()
            .collectAsState(initial = emptyList())
    val aiSummaryCandidates by
        container.stage13B5Qa4Repository.observeAiSummaryCandidates()
            .collectAsState(initial = emptyList())
    val recordings by
        container.recordingLibraryRepository.recordings
            .collectAsState(initial = emptyList())
    val visibleDetail by Stage13B5Qa4PageVisibility.current.collectAsState()
    val items =
        filterQa4GlobalAttentionItems(
            items =
                buildQa4GlobalAttentionItems(
                    transcriptionAttention = transcriptionAttention,
                    aiSummaryAttention = aiSummaryAttention,
                    transcriptionCandidates = transcriptionCandidates,
                    aiSummaryCandidates = aiSummaryCandidates,
                    recordings = recordings,
                ),
            visibleDetail = visibleDetail,
        )

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isNotEmpty()) {
            Qa4GlobalAttentionBar(
                items = items,
                onOpen = { item ->
                    AppRecordingNavigation.publish(
                        recordingId = item.recordingId,
                        destination = item.destination,
                    )
                },
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            content()
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

internal fun buildQa4GlobalAttentionItems(
    transcriptionAttention: List<TranscriptionEntity>,
    aiSummaryAttention: List<AiSummaryEntity>,
    recordings: List<RecordingLibraryItem>,
    transcriptionCandidates: List<TranscriptionEntity> = emptyList(),
    aiSummaryCandidates: List<AiSummaryEntity> = emptyList(),
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
        aiSummaryAttention.forEach { summary ->
            add(
                Qa4GlobalAttentionItem(
                    key = "summary-attention:${summary.id}",
                    recordingId = summary.recordingId,
                    recordingName = recordingName(summary.recordingId),
                    label =
                        when (summary.status) {
                            AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT -> "总结状态待确认"
                            AiSummaryStateValue.INTERRUPTED -> "总结已中断"
                            else -> "总结失败"
                        },
                    destination = RecordingDetailDestination.SUMMARY,
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
        aiSummaryCandidates.forEach { summary ->
            add(
                Qa4GlobalAttentionItem(
                    key = "summary-candidate:${summary.id}",
                    recordingId = summary.recordingId,
                    recordingName = recordingName(summary.recordingId),
                    label = "新的总结结果已生成",
                    destination = RecordingDetailDestination.SUMMARY,
                ),
            )
        }
    }
}

@Composable
private fun Qa4GlobalAttentionBar(
    items: List<Qa4GlobalAttentionItem>,
    onOpen: (Qa4GlobalAttentionItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                if (items.size == 1) "任务通知" else "${items.size} 条任务通知",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { item ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item) }
                            .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        item.label + " · " + item.recordingName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Text(
                        "查看 ›",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
