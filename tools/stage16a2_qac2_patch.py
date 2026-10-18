from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


# 1) Compact transcript action bar: keep all controls on one row and make annotation count the toggle.
path = "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptProductContent.kt"
replace_once(
    path,
    '''            CompactTranscriptChip(
                label = "说话人·$speakerModeLabel",
                selected = false,
                onClick = onSpeakerModeClick,
            )
            Button(
                modifier = Modifier.heightIn(min = 40.dp),
                enabled = state.paragraphs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                onClick = { editorOpen = true },
            ) {
                Text("编辑", maxLines = 1)
            }
''',
    '''            CompactTranscriptChip(
                label = "说话人·$speakerModeLabel",
                selected = false,
                onClick = onSpeakerModeClick,
            )
            CompactTranscriptChip(
                label = "标注 $annotationCount",
                selected = showAnnotations,
                onClick = onToggleAnnotations,
            )
            CompactTranscriptChip(
                label = "编辑",
                selected = false,
                enabled = state.paragraphs.isNotEmpty(),
                onClick = { editorOpen = true },
            )
''',
)
replace_once(
    path,
    '''        if (annotationCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onToggleAnnotations) {
                    Text("标注 $annotationCount · " + if (showAnnotations) "隐藏" else "显示")
                }
            }
        }
        if (state.currentRevisionId != null) {
            Text(
                "已编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
''',
    '''''',
)
replace_once(
    path,
    '''private fun CompactTranscriptChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
''',
    '''private fun CompactTranscriptChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
''',
)
replace_once(
    path,
    '''                .heightIn(min = 40.dp)
                .clickable(onClick = onClick),
''',
    '''                .heightIn(min = 36.dp)
                .clickable(enabled = enabled, onClick = onClick),
''',
)
replace_once(
    path,
    '''            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
''',
    '''            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
''',
)
replace_once(
    path,
    '''                style = MaterialTheme.typography.labelLarge,
''',
    '''                style = MaterialTheme.typography.labelMedium,
''',
)

# 2) Make the speaker count itself the speaker editor entry and fold edited state into the document info line.
path = "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt"
replace_once(
    path,
    '''fun TranscriptDocumentHeader(
    document: TranscriptDocument,
    recordingName: String?,
    onRenameSpeaker: (speakerId: String, requestedName: String?) -> Unit,
) {
''',
    '''fun TranscriptDocumentHeader(
    document: TranscriptDocument,
    recordingName: String?,
    edited: Boolean = false,
    onRenameSpeaker: (speakerId: String, requestedName: String?) -> Unit,
) {
''',
)
replace_once(
    path,
    '''    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            buildString {
                append(sourceText)
                append(" · ")
                append(document.segments.size)
                append(" 段")
                if (document.speakers.isNotEmpty()) {
                    append(" · ")
                    append(document.speakers.size)
                    append(" 位说话人")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (document.speakers.isNotEmpty()) {
            TextButton(onClick = { speakerListOpen = true }) {
                Text("说话人")
            }
        }
    }
''',
    '''    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            "$sourceText · ${document.segments.size} 段",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (document.speakers.isNotEmpty()) {
            Text(
                " · ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${document.speakers.size}位说话人",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { speakerListOpen = true },
            )
        }
        if (edited) {
            Text(
                " · 已编辑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
''',
)
replace_once(path, '            title = { Text("说话人") },\n', '            title = { Text("编辑说话人") },\n')

# 3) Project multiple annotations as one horizontally scrollable compact strip.
path = "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptAnnotationProjection.kt"
replace_once(
    path,
    '''import androidx.compose.foundation.clickable
''',
    '''import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
''',
)
replace_once(
    path,
    '''@Composable
internal fun TranscriptAnnotationStrip(
    projections: List<TranscriptAnnotationProjection>,
    onClick: (TranscriptAnnotationProjection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projections.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        projections.forEach { projection ->
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick(projection) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        formatProjectionTime(projection.sampleIndex),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        annotationProjectionSummary(projection.item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
''',
    '''@Composable
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
''',
)
replace_once(
    path,
    '''                verticalArrangement = Arrangement.spacedBy(10.dp),
''',
    '''                verticalArrangement = Arrangement.spacedBy(7.dp),
''',
)

# 4) Transcript screen: reading mode is view-only, annotation taps open shared detail, speaker count carries edited state.
path = "app/src/main/java/io/github/ioannes78/voica/ui/library/RecordingDetailProductScreen.kt"
replace_once(
    path,
    '''import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationPanel
''',
    '''import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationDetailDialog
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationPanel
''',
)
replace_once(
    path,
    '''    var showTranscriptAnnotations by rememberSaveable(recording.id) { mutableStateOf(true) }
''',
    '''    var showTranscriptAnnotations by rememberSaveable(recording.id) { mutableStateOf(true) }
    var selectedTranscriptAnnotationId by rememberSaveable(recording.id) { mutableStateOf<String?>(null) }
''',
)
replace_once(
    path,
    '''    val annotationUiState by annotationViewModel.state.collectAsState()
''',
    '''    val annotationUiState by annotationViewModel.state.collectAsState()
    val selectedTranscriptAnnotation =
        selectedTranscriptAnnotationId?.let { selectedId ->
            annotationUiState.items.firstOrNull { it.annotation.id == selectedId }
        }
''',
)
replace_once(
    path,
    '''                                    TranscriptDocumentHeader(
                                        document = recordingDocument,
                                        recordingName = recording.displayName,
                                        onRenameSpeaker = transcriptionViewModel::renameSpeaker,
                                    )
''',
    '''                                    TranscriptDocumentHeader(
                                        document = recordingDocument,
                                        recordingName = recording.displayName,
                                        edited = transcriptContentState.currentRevisionId != null,
                                        onRenameSpeaker = transcriptionViewModel::renameSpeaker,
                                    )
''',
)
replace_once(
    path,
    '''                                        val assignment = assignTranscriptAnnotations(projectedAnnotations, ranges)
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                            ) {
                                                TimelineAnnotationComposerButton(
                                                    enabled =
                                                        playbackSnapshot.recordingId == recording.id &&
                                                            playbackSnapshot.durationSampleCount > 0L,
                                                    label = "＋标注",
                                                    compact = true,
                                                    viewModel = annotationViewModel,
                                                    onCreate = { noteText, onCreated ->
                                                        annotationViewModel.createAtPlayback(
                                                            recording = recording,
                                                            playback = playbackSnapshot,
                                                            noteText = noteText,
                                                            provenance =
                                                                TimelineAnnotationProvenance(
                                                                    sourceTranscriptionIdSnapshot = recordingDocument.transcriptionId,
                                                                    sourceRevisionIdSnapshot = transcriptContentState.currentRevisionId,
                                                                    sourceTimingQualitySnapshot = "PLAYBACK_FALLBACK",
                                                                ),
                                                            onCreated = onCreated,
                                                        )
                                                    },
                                                )
                                            }
                                            TranscriptAnnotatedReading(
                                                paragraphs = transcriptContentState.paragraphs,
                                                assignment = assignment,
                                                unresolved = unresolvedAnnotations,
                                                showAnnotations = showTranscriptAnnotations,
                                                onAnnotationClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                onUnresolvedClick = { item ->
                                                    annotationViewModel.seek(item.annotation)
                                                },
                                            )
                                        }
''',
    '''                                        val assignment = assignTranscriptAnnotations(projectedAnnotations, ranges)
                                        TranscriptAnnotatedReading(
                                            paragraphs = transcriptContentState.paragraphs,
                                            assignment = assignment,
                                            unresolved = unresolvedAnnotations,
                                            showAnnotations = showTranscriptAnnotations,
                                            onAnnotationClick = { projection ->
                                                selectedTranscriptAnnotationId = projection.item.annotation.id
                                            },
                                            onUnresolvedClick = { item ->
                                                selectedTranscriptAnnotationId = item.annotation.id
                                            },
                                        )
''',
)
p = Path(path)
text = p.read_text()
text = text.replace(
    'annotationViewModel.seek(projection.item.annotation)',
    'selectedTranscriptAnnotationId = projection.item.annotation.id',
)
text = text.replace(
    'annotationViewModel.seek(item.annotation)',
    'selectedTranscriptAnnotationId = item.annotation.id',
)
p.write_text(text)
replace_once(
    path,
    '''    if (speakerSheetOpen) {
''',
    '''    selectedTranscriptAnnotation?.let { item ->
        TimelineAnnotationDetailDialog(
            item = item,
            viewModel = annotationViewModel,
            onDismiss = { selectedTranscriptAnnotationId = null },
        )
    }

    if (speakerSheetOpen) {
''',
)

# 5) Compact the dedicated annotation management header while retaining the management tab.
path = "app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationPanel.kt"
replace_once(
    path,
    '''            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "时间标注",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "以音频位置为准，不依赖某个转写版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
''',
    '''            Text(
                "标注 ${state.items.size}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
''',
)

# 6) Shared transcript annotation detail dialog: photo viewing, edit note, delete annotation/photo, seek.
detail = Path("app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationDetailDialog.kt")
detail.write_text(r'''package io.github.ioannes78.voica.ui.annotation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentEntity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TimelineAnnotationDetailDialog(
    item: TimelineAnnotationUiItem,
    viewModel: TimelineAnnotationViewModel,
    onDismiss: () -> Unit,
) {
    var editing by remember(item.annotation.id) { mutableStateOf(false) }
    var deleteConfirm by remember(item.annotation.id) { mutableStateOf(false) }
    var viewedAttachment by remember(item.annotation.id) {
        mutableStateOf<TimelineAnnotationAttachmentEntity?>(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                detailAnnotationTimeLabel(item),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    item.annotation.noteText?.takeIf { it.isNotBlank() } ?: "快速标记",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (item.attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.attachments.forEach { attachment ->
                            DetailAttachmentThumbnail(
                                attachment = attachment,
                                onClick = { viewedAttachment = attachment },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editing = true }) { Text("编辑") }
                    TextButton(onClick = { deleteConfirm = true }) { Text("删除") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.seek(item.annotation)
                    onDismiss()
                },
            ) { Text("跳转播放") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (editing) {
        var note by remember(item.annotation.id, item.annotation.noteText) {
            mutableStateOf(item.annotation.noteText.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (item.annotation.noteText.isNullOrBlank()) "添加笔记" else "编辑笔记") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(4_000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text("记录此刻的重点、想法或待处理事项") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateNote(item.annotation.id, note.trim())
                        editing = false
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除标注？") },
            text = { Text("将同时删除这个标注关联的本地图片，不影响录音、转写或总结。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAnnotation(item.annotation.id)
                        deleteConfirm = false
                        onDismiss()
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } },
        )
    }

    viewedAttachment?.let { attachment ->
        DetailAnnotationPhotoDialog(
            attachment = attachment,
            onDismiss = { viewedAttachment = null },
            onDelete = {
                viewModel.deleteAttachment(attachment)
                viewedAttachment = null
            },
        )
    }
}

@Composable
private fun DetailAttachmentThumbnail(
    attachment: TimelineAnnotationAttachmentEntity,
    onClick: () -> Unit,
) {
    val bitmap = rememberDetailAttachmentBitmap(attachment)
    Surface(
        modifier = Modifier.size(64.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "标注图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Image, contentDescription = null)
            }
        }
    }
}

@Composable
private fun DetailAnnotationPhotoDialog(
    attachment: TimelineAnnotationAttachmentEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = rememberDetailAttachmentBitmap(attachment)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标注图片") },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "标注图片",
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("图片暂不可读取") }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDelete) { Text("删除图片") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun rememberDetailAttachmentBitmap(
    attachment: TimelineAnnotationAttachmentEntity,
): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by
        produceState<ImageBitmap?>(
            initialValue = null,
            key1 = attachment.id,
            key2 = attachment.updatedAtMs,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    val normalized = attachment.relativePath.replace('\\', '/').trimStart('/')
                    if (!normalized.startsWith("timeline_annotations/")) {
                        null
                    } else {
                        runCatching {
                            BitmapFactory.decodeFile(File(context.filesDir, normalized).absolutePath)
                                ?.asImageBitmap()
                        }.getOrNull()
                    }
                }
        }
    return bitmap
}

private fun detailAnnotationTimeLabel(item: TimelineAnnotationUiItem): String {
    val annotation = item.annotation
    val base = detailSampleTime(annotation.anchorSampleIndex)
    return if (annotation.anchorDomain == TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT) {
        val segment = (annotation.anchorRealtimeSegmentIndexSnapshot ?: 0) + 1
        "片段 $segment · $base"
    } else {
        base
    }
}

private fun detailSampleTime(sampleIndex: Long): String {
    val totalSeconds = sampleIndex.coerceAtLeast(0L) / 16_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
''')

# 7) Strengthen the existing source contract around the QA-C2 UX decisions.
path = "app/src/test/java/io/github/ioannes78/voica/ui/annotation/Stage16A2TranscriptAnnotationContractTest.kt"
replace_once(
    path,
    '''        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")
''',
    '''        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")
        val productContent = appProductionSource("ui/transcript/TranscriptProductContent.kt")
        val detailDialog = appProductionSource("ui/annotation/TimelineAnnotationDetailDialog.kt")
''',
)
replace_once(
    path,
    '''        assertTrue(
            "Current transcript projection is sample-based and never inferred from text length",
            projection.contains("projection.sampleIndex >= candidate.startSampleIndex") &&
                projection.contains("projection.sampleIndex < candidate.endSampleIndexExclusive"),
        )
''',
    '''        assertTrue(
            "Current transcript projection is sample-based and never inferred from text length",
            projection.contains("projection.sampleIndex >= candidate.startSampleIndex") &&
                projection.contains("projection.sampleIndex < candidate.endSampleIndexExclusive"),
        )
        assertTrue(
            "Projected annotations stay in one compact horizontal strip",
            projection.contains("horizontalScroll(rememberScrollState())"),
        )
        assertTrue(
            "Annotation visibility is controlled by the compact count chip",
            productContent.contains("label = \\\"标注 $annotationCount\\\"") &&
                !productContent.contains("标注 $annotationCount · "),
        )
        assertTrue(
            "Transcript annotation details expose photo viewing, edit, delete, and seek",
            detailDialog.contains("DetailAnnotationPhotoDialog(") &&
                detailDialog.contains("Text(\\\"编辑\\\")") &&
                detailDialog.contains("Text(\\\"删除\\\")") &&
                detailDialog.contains("Text(\\\"跳转播放\\\")"),
        )
''',
)
