package io.github.ioannes78.voica.ui.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationUiItem
import java.util.Locale

internal data class TranscriptAnnotationProjection(
    val item: TimelineAnnotationUiItem,
    val sampleIndex: Long,
)

internal data class TranscriptAnnotationRange(
    val id: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
)

internal data class TranscriptAnnotationAssignment(
    val byRangeId: Map<String, List<TranscriptAnnotationProjection>>,
    val unassigned: List<TranscriptAnnotationProjection>,
)

internal fun assignTranscriptAnnotations(
    projections: List<TranscriptAnnotationProjection>,
    ranges: List<TranscriptAnnotationRange>,
): TranscriptAnnotationAssignment {
    val validRanges =
        ranges.filter { range ->
            range.id.isNotBlank() && range.startSampleIndex >= 0L &&
                range.endSampleIndexExclusive > range.startSampleIndex
        }
    val assigned = linkedMapOf<String, MutableList<TranscriptAnnotationProjection>>()
    val unassigned = mutableListOf<TranscriptAnnotationProjection>()
    projections.sortedBy { it.sampleIndex }.forEach { projection ->
        val range =
            validRanges.firstOrNull { candidate ->
                projection.sampleIndex >= candidate.startSampleIndex &&
                    projection.sampleIndex < candidate.endSampleIndexExclusive
            }
        if (range == null) {
            unassigned += projection
        } else {
            assigned.getOrPut(range.id) { mutableListOf() } += projection
        }
    }
    return TranscriptAnnotationAssignment(
        byRangeId = assigned.mapValues { it.value.toList() },
        unassigned = unassigned,
    )
}

@Composable
internal fun TranscriptAnnotationStrip(
    projections: List<TranscriptAnnotationProjection>,
    onClick: (TranscriptAnnotationProjection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projections.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        projections.forEach { projection ->
            Surface(
                modifier = Modifier.clickable { onClick(projection) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
            ) {
                Text(
                    text = formatProjectionTime(projection.sampleIndex) + " " + annotationProjectionSummary(projection.item),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TranscriptUnprojectedAnnotationPanel(
    projectedOutsideText: List<TranscriptAnnotationProjection>,
    unresolved: List<TimelineAnnotationUiItem>,
    onProjectedClick: (TranscriptAnnotationProjection) -> Unit,
    onUnresolvedClick: (TimelineAnnotationUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projectedOutsideText.isEmpty() && unresolved.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "其他标注",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (projectedOutsideText.isNotEmpty()) {
                TranscriptAnnotationStrip(projectedOutsideText, onProjectedClick)
            }
            unresolved.forEach { item ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUnresolvedClick(item) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "未定位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        annotationProjectionSummary(item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unresolved.isNotEmpty()) {
                Text(
                    "以上标注缺少可验证的当前转写位置；未按文字长度或断连空档猜测位置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TranscriptAnnotatedReading(
    paragraphs: List<TranscriptReadingParagraph>,
    assignment: TranscriptAnnotationAssignment,
    unresolved: List<TimelineAnnotationUiItem>,
    showAnnotations: Boolean,
    onAnnotationClick: (TranscriptAnnotationProjection) -> Unit,
    onUnresolvedClick: (TimelineAnnotationUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                paragraphs.forEach { paragraph ->
                    Text(
                        paragraph.text.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (showAnnotations) {
                        DisableSelection {
                            TranscriptAnnotationStrip(
                                projections = assignment.byRangeId[paragraph.stableId].orEmpty(),
                                onClick = onAnnotationClick,
                            )
                        }
                    }
                }
                if (showAnnotations) {
                    DisableSelection {
                        TranscriptUnprojectedAnnotationPanel(
                            projectedOutsideText = assignment.unassigned,
                            unresolved = unresolved,
                            onProjectedClick = onAnnotationClick,
                            onUnresolvedClick = onUnresolvedClick,
                        )
                    }
                }
            }
        }
    }
}

internal fun annotationProjectionSummary(item: TimelineAnnotationUiItem): String {
    val note = item.annotation.noteText?.trim().orEmpty()
    return buildString {
        if (note.isNotBlank()) append(note) else append("快速标记")
        if (item.photoCount > 0) {
            if (isNotEmpty()) append(" · ")
            append("图片 ")
            append(item.photoCount)
            append(" 张")
        }
    }
}

private fun formatProjectionTime(sampleIndex: Long): String {
    val seconds = sampleIndex.coerceAtLeast(0L) / 16_000L
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}
