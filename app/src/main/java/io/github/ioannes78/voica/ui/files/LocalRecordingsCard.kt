package io.github.ioannes78.voica.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.RecordingLibraryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LocalSortMode {
    RECORDED_AT,
    DOWNLOADED_AT,
    NAME,
    SIZE,
}

@Composable
fun LocalRecordingsCard(
    recordings: List<RecordingLibraryItem>,
    onRename: (String, String) -> Unit,
    onDeleteLocal: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RecordingLibraryItem?>(null) }
    var pendingRename by remember { mutableStateOf<RecordingLibraryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(LocalSortMode.RECORDED_AT) }

    val sorted = remember(recordings, sortMode) {
        when (sortMode) {
            LocalSortMode.RECORDED_AT ->
                recordings.sortedWith(
                    compareByDescending<RecordingLibraryItem> { it.recordedAtLocalIso ?: "" }
                        .thenByDescending { it.downloadedAtMs },
                )
            LocalSortMode.DOWNLOADED_AT ->
                recordings.sortedByDescending { it.downloadedAtMs }
            LocalSortMode.NAME ->
                recordings.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            LocalSortMode.SIZE ->
                recordings.sortedByDescending { item ->
                    item.assets.sumOf { it.sizeBytes }
                }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.local_files_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortButton("录音时间", sortMode == LocalSortMode.RECORDED_AT) {
                    sortMode = LocalSortMode.RECORDED_AT
                }
                SortButton("下载时间", sortMode == LocalSortMode.DOWNLOADED_AT) {
                    sortMode = LocalSortMode.DOWNLOADED_AT
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortButton("名称", sortMode == LocalSortMode.NAME) {
                    sortMode = LocalSortMode.NAME
                }
                SortButton("大小", sortMode == LocalSortMode.SIZE) {
                    sortMode = LocalSortMode.SIZE
                }
            }

            if (sorted.isEmpty()) {
                Text(stringResource(R.string.local_files_empty))
            } else {
                Text(
                    stringResource(R.string.local_files_count, sorted.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                sorted.forEachIndexed { index, item ->
                    RecordingRow(
                        item = item,
                        onRename = {
                            renameText = item.displayName
                            pendingRename = item
                        },
                        onDelete = { pendingDelete = item },
                    )
                    if (index != sorted.lastIndex) HorizontalDivider()
                }
            }
        }
    }

    pendingRename?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("显示名称") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val value = renameText
                        pendingRename = null
                        onRename(item.id, value)
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.local_file_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayName)
                    Text("仅删除手机本地录音及其派生音频，不会删除录音卡中的文件。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteLocal(item.id)
                    },
                ) {
                    Text(stringResource(R.string.local_file_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingLibraryItem,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val opus = item.assets.firstOrNull { it.role == AudioAssetRole.DEVICE_OPUS }
    val deviceWav = item.assets.firstOrNull { it.role == AudioAssetRole.DEVICE_WAV }
    val canonicalWav = item.assets.firstOrNull { it.role == AudioAssetRole.CANONICAL_WAV }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.displayName, style = MaterialTheme.typography.titleSmall)
        LocalInfoLine(
            "录音时间",
            item.recordedAtLocalIso?.replace('T', ' ') ?: "未知",
        )
        LocalInfoLine(
            "下载时间",
            Instant.ofEpochMilli(item.downloadedAtMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DISPLAY_TIME),
        )
        item.deviceReportedDurationMs?.let {
            LocalInfoLine("设备时长", formatDuration(it))
        }
        LocalInfoLine(
            "OPUS 原始音频",
            opus?.let { assetStatus(it.sizeBytes, it.formatValidationState) } ?: "未下载",
        )
        LocalInfoLine(
            "设备 WAV",
            deviceWav?.let { assetStatus(it.sizeBytes, it.formatValidationState) } ?: "未下载",
        )
        LocalInfoLine(
            "标准 WAV",
            canonicalWav?.let {
                "WAV · 16 kHz · 单声道 · PCM16 · " + formatBytes(it.sizeBytes)
            } ?: "尚未生成",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRename) {
                Text("重命名")
            }
            OutlinedButton(onClick = onDelete) {
                Text(stringResource(R.string.local_file_delete))
            }
        }
    }
}

@Composable
private fun SortButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun LocalInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun assetStatus(sizeBytes: Long, validationState: String): String =
    when (validationState) {
        "VALID" -> "已验证 · " + formatBytes(sizeBytes)
        "VALIDATING" -> "验证中 · " + formatBytes(sizeBytes)
        "CORRUPTED", "INVALID" -> "文件异常 · " + formatBytes(sizeBytes)
        "UNSUPPORTED" -> "格式暂不支持 · " + formatBytes(sizeBytes)
        "LEGACY_HINT" -> "待格式验证 · " + formatBytes(sizeBytes)
        else -> "已下载 · " + formatBytes(sizeBytes)
    }

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
