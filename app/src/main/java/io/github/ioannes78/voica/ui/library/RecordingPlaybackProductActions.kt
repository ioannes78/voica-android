package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
    val format =
        original?.container
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
            ?: "--"
    val size = original?.sizeBytes?.let(::formatInfoBytes) ?: "--"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "录音信息",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ProductInfoLine(
                icon = Icons.Outlined.CalendarMonth,
                label = "录制时间",
                value = recordedAt,
            )
            ProductInfoLine(
                icon = Icons.Outlined.Description,
                label = "文件大小",
                value = size,
            )
            ProductInfoLine(
                icon = Icons.Outlined.MicNone,
                label = "来源 / 格式",
                value = "$source · $format",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProductActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Edit,
                    label = "重命名",
                    onClick = onRename,
                )
                ProductActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Share,
                    label = "分享",
                    onClick = { sharePickerOpen = true },
                )
                ProductActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.FileUpload,
                    label = "导出",
                    onClick = { exportPickerOpen = true },
                )
                ProductActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.DeleteOutline,
                    label = "删除",
                    destructive = true,
                    onClick = onDelete,
                )
            }
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
private fun ProductInfoLine(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            modifier = Modifier.width(86.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProductActionTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor =
        if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val containerColor =
        if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(27.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
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
