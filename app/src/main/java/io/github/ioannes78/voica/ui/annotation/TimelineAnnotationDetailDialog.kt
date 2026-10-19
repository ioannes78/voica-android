package io.github.ioannes78.voica.ui.annotation

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
