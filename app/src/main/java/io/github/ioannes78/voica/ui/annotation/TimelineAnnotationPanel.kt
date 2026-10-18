package io.github.ioannes78.voica.ui.annotation

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentEntity
import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PendingCamera(
    val target: TimelineAnnotationCameraCaptureTarget,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class PendingGallery(
    val annotationId: String,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class NoteEditor(
    val annotation: TimelineAnnotationEntity?,
    val initialText: String,
)

@Composable
fun TimelineAnnotationPanel(
    recording: RecordingLibraryItem,
    playback: PlaybackSnapshot,
    viewModel: TimelineAnnotationViewModel,
) {
    LaunchedEffect(recording.id) { viewModel.bind(recording.id) }
    val state by viewModel.state.collectAsState()
    var addMenuOpen by remember { mutableStateOf(false) }
    var noteEditor by remember { mutableStateOf<NoteEditor?>(null) }
    var deleteTarget by remember { mutableStateOf<TimelineAnnotationEntity?>(null) }
    var viewedAttachment by remember { mutableStateOf<TimelineAnnotationAttachmentEntity?>(null) }
    var pendingCamera by remember { mutableStateOf<PendingCamera?>(null) }
    var pendingGallery by remember { mutableStateOf<PendingGallery?>(null) }

    val canCreateAtPlayhead =
        playback.recordingId == recording.id &&
            !playback.sourceAssetId.isNullOrBlank() &&
            playback.durationSampleCount > 0L

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
            val pending = pendingCamera ?: return@rememberLauncherForActivityResult
            pendingCamera = null
            viewModel.finishCamera(
                target = pending.target,
                accepted = accepted,
                deleteAnnotationIfCancelled = pending.deleteAnnotationIfCancelled,
            )
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val pending = pendingGallery ?: return@rememberLauncherForActivityResult
            pendingGallery = null
            if (uri != null) {
                viewModel.importGallery(pending.annotationId, uri)
            } else if (pending.deleteAnnotationIfCancelled) {
                viewModel.deleteAnnotation(pending.annotationId, silent = true)
            }
        }

    fun openCamera(annotationId: String, cleanupIfCancelled: Boolean) {
        viewModel.prepareCamera(annotationId) { target ->
            pendingCamera = PendingCamera(target, cleanupIfCancelled)
            cameraLauncher.launch(target.uri)
        }
    }

    fun openGallery(annotationId: String, cleanupIfCancelled: Boolean) {
        pendingGallery = PendingGallery(annotationId, cleanupIfCancelled)
        galleryLauncher.launch("image/*")
    }

    fun createForCamera() {
        viewModel.createAtPlayback(recording, playback) { created ->
            openCamera(created.id, cleanupIfCancelled = true)
        }
    }

    fun createForGallery() {
        viewModel.createAtPlayback(recording, playback) { created ->
            openGallery(created.id, cleanupIfCancelled = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "标注 ${state.items.size}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Box {
                Button(
                    onClick = { addMenuOpen = true },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("标注", modifier = Modifier.padding(start = 4.dp))
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("快速标记当前时间") },
                        enabled = canCreateAtPlayhead,
                        onClick = {
                            addMenuOpen = false
                            viewModel.createAtPlayback(recording, playback)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("文字笔记") },
                        enabled = canCreateAtPlayhead,
                        onClick = {
                            addMenuOpen = false
                            noteEditor = NoteEditor(annotation = null, initialText = "")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("拍照") },
                        enabled = canCreateAtPlayhead,
                        onClick = {
                            addMenuOpen = false
                            createForCamera()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("从相册添加") },
                        enabled = canCreateAtPlayhead,
                        onClick = {
                            addMenuOpen = false
                            createForGallery()
                        },
                    )
                }
            }
        }

        if (!canCreateAtPlayhead) {
            Text(
                "播放或加载当前录音后，可按当前时间快速添加标注。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnnotationFilterChip("全部", TimelineAnnotationFilter.ALL, state, viewModel)
            AnnotationFilterChip("笔记", TimelineAnnotationFilter.NOTE, state, viewModel)
            AnnotationFilterChip("图片", TimelineAnnotationFilter.PHOTO, state, viewModel)
            AnnotationFilterChip("快速标记", TimelineAnnotationFilter.QUICK, state, viewModel)
        }

        state.notice?.let { notice ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.dismissNotice() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (state.visibleItems.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (state.items.isEmpty()) "还没有标注" else "当前筛选下没有标注",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "快速标记只保存时间点；需要时再补笔记或图片。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = state.visibleItems,
                    key = { it.annotation.id },
                ) { item ->
                    TimelineAnnotationRow(
                        item = item,
                        canMoveToPlayhead = canCreateAtPlayhead,
                        onSeek = { viewModel.seek(item.annotation) },
                        onEdit = {
                            noteEditor =
                                NoteEditor(
                                    annotation = item.annotation,
                                    initialText = item.annotation.noteText.orEmpty(),
                                )
                        },
                        onMove = {
                            viewModel.moveToPlaybackPosition(item.annotation.id, recording, playback)
                        },
                        onCamera = { openCamera(item.annotation.id, cleanupIfCancelled = false) },
                        onGallery = { openGallery(item.annotation.id, cleanupIfCancelled = false) },
                        onDelete = { deleteTarget = item.annotation },
                        onOpenAttachment = { viewedAttachment = it },
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    noteEditor?.let { editor ->
        AnnotationNoteDialog(
            editor = editor,
            onDismiss = { noteEditor = null },
            onSave = { text ->
                if (editor.annotation == null) {
                    viewModel.createAtPlayback(recording, playback, noteText = text)
                } else {
                    viewModel.updateNote(editor.annotation.id, text)
                }
                noteEditor = null
            },
        )
    }

    deleteTarget?.let { annotation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除标注？") },
            text = { Text("将同时删除这个标注关联的本地图片，不影响录音、转写或总结。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAnnotation(annotation.id)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    viewedAttachment?.let { attachment ->
        AnnotationPhotoDialog(
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
private fun AnnotationFilterChip(
    label: String,
    filter: TimelineAnnotationFilter,
    state: TimelineAnnotationUiState,
    viewModel: TimelineAnnotationViewModel,
) {
    FilterChip(
        selected = state.filter == filter,
        onClick = { viewModel.setFilter(filter) },
        label = { Text(label) },
    )
}

@Composable
private fun TimelineAnnotationRow(
    item: TimelineAnnotationUiItem,
    canMoveToPlayhead: Boolean,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDelete: () -> Unit,
    onOpenAttachment: (TimelineAnnotationAttachmentEntity) -> Unit,
) {
    var menuOpen by remember(item.annotation.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSeek),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    annotationTimeLabel(item.annotation),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    when {
                        item.isQuickMarker -> "快速标记"
                        !item.annotation.noteText.isNullOrBlank() -> "笔记"
                        else -> "图片"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.photoCount > 0) {
                    Text(
                        "图片 ${item.photoCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑笔记") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("移动到当前播放位置") },
                            enabled = canMoveToPlayhead,
                            onClick = { menuOpen = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            onClick = { menuOpen = false; onCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text("从相册添加") },
                            onClick = { menuOpen = false; onGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除标注") },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            item.annotation.noteText?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.attachments.take(3).forEach { attachment ->
                        AnnotationThumbnail(
                            attachment = attachment,
                            onClick = { onOpenAttachment(attachment) },
                        )
                    }
                    if (item.attachments.size > 3) {
                        Surface(
                            modifier = Modifier.size(58.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+${item.attachments.size - 3}")
                            }
                        }
                    }
                }
            }

            item.annotation.sourceTextSnapshot?.takeIf { it.isNotBlank() }?.let { source ->
                Text(
                    "来源：$source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AnnotationNoteDialog(
    editor: NoteEditor,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(editor.annotation?.id, editor.initialText) {
        mutableStateOf(editor.initialText)
    }
    val creating = editor.annotation == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "添加文字笔记" else "编辑笔记") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(4_000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("记录此刻的重点、想法或待处理事项") },
            )
        },
        confirmButton = {
            Button(
                enabled = !creating || text.isNotBlank(),
                onClick = { onSave(text.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AnnotationThumbnail(
    attachment: TimelineAnnotationAttachmentEntity,
    onClick: () -> Unit,
) {
    val bitmap = rememberAttachmentBitmap(attachment)
    Surface(
        modifier = Modifier.size(58.dp).clickable(onClick = onClick),
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
private fun AnnotationPhotoDialog(
    attachment: TimelineAnnotationAttachmentEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = rememberAttachmentBitmap(attachment)
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
                ) {
                    Text("图片暂不可读取")
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDelete) { Text("删除图片") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun rememberAttachmentBitmap(
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

private fun annotationTimeLabel(annotation: TimelineAnnotationEntity): String =
    if (annotation.anchorDomain == TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT) {
        val segment = (annotation.anchorRealtimeSegmentIndexSnapshot ?: 0) + 1
        "片段 $segment · ${formatAnnotationSampleTime(annotation.anchorSampleIndex)}"
    } else {
        formatAnnotationSampleTime(annotation.anchorSampleIndex)
    }

private fun formatAnnotationSampleTime(sampleIndex: Long): String {
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
