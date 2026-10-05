package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingSourceType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecordingPlaybackProductActions(
    recording: RecordingLibraryItem,
    canonicalReady: Boolean,
    onRename: () -> Unit,
    onShareCanonical: () -> Unit,
    onShareOriginal: () -> Unit,
    onExportCanonical: () -> Unit,
    onExportOriginal: () -> Unit,
    onDelete: () -> Unit,
) {
    var sharePickerOpen by remember(recording.id) { mutableStateOf(false) }
    var exportPickerOpen by remember(recording.id) { mutableStateOf(false) }

    val original =
        recording.assets.firstOrNull { it.role == AudioAssetRole.IMPORTED_ORIGINAL }
            ?: recording.assets.firstOrNull { it.role == AudioAssetRole.DEVICE_OPUS }
            ?: recording.assets.firstOrNull { it.role == AudioAssetRole.DEVICE_WAV }
            ?: recording.assets.firstOrNull()
    val recordedAt =
        recording.recordedAtLocalIso?.takeIf { it.isNotBlank() }
            ?: recording.createdAtMs
                .takeIf { it > 0L }
                ?.let { epochMs ->
                    Instant.ofEpochMilli(epochMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DISPLAY_TIME)
                }
            ?: "--"
    val source =
        when (recording.sourceType) {
            RecordingSourceType.LOCAL_IMPORT -> "本地导入"
            RecordingSourceType.DEVICE_DOWNLOAD -> "录音卡"
            else -> recording.sourceType
        }
    val format = original?.container?.takeIf { it.isNotBlank() } ?: "--"
    val size = original?.sizeBytes?.let(::formatInfoBytes) ?: "--"

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider()
        Text("录音信息", style = MaterialTheme.typography.titleMedium)
        ProductInfoLine("录制时间", recordedAt)
        ProductInfoLine("文件大小", size)
        ProductInfoLine("来源 / 格式", "$source · $format")
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onRename) { Text("重命名") }
            TextButton(onClick = { sharePickerOpen = true }) { Text("分享") }
            TextButton(onClick = { exportPickerOpen = true }) { Text("导出") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }

    if (sharePickerOpen) {
        AudioVariantDialog(
            title = "分享录音",
            canonicalReady = canonicalReady,
            canonicalLabel = "标准 WAV",
            originalLabel = "原始文件",
            onDismiss = { sharePickerOpen = false },
            onCanonical = {
                sharePickerOpen = false
                onShareCanonical()
            },
            onOriginal = {
                sharePickerOpen = false
                onShareOriginal()
            },
        )
    }

    if (exportPickerOpen) {
        AudioVariantDialog(
            title = "导出录音",
            canonicalReady = canonicalReady,
            canonicalLabel = "标准 WAV",
            originalLabel = "原始文件",
            onDismiss = { exportPickerOpen = false },
            onCanonical = {
                exportPickerOpen = false
                onExportCanonical()
            },
            onOriginal = {
                exportPickerOpen = false
                onExportOriginal()
            },
        )
    }
}

@Composable
private fun ProductInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AudioVariantDialog(
    title: String,
    canonicalReady: Boolean,
    canonicalLabel: String,
    originalLabel: String,
    onDismiss: () -> Unit,
    onCanonical: () -> Unit,
    onOriginal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onCanonical,
                    enabled = canonicalReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(canonicalLabel)
                }
                TextButton(
                    onClick = onOriginal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(originalLabel)
                }
                if (!canonicalReady) {
                    Text(
                        "标准 WAV 尚未生成，可先使用原始文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatInfoBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
