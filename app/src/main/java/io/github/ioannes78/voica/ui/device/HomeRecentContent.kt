package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.ui.library.RecordingDisplayTimeFormatter
import io.github.ioannes78.voica.ui.library.localMediaAvailability
import java.util.Locale

@Composable
internal fun HomeRecentContent(
    recordings: List<RecordingLibraryItem>,
    deviceFiles: List<RemoteDeviceFile>,
    operationState: FileOperationState,
    onOpenRecording: (String) -> Unit,
    onDownload: (RemoteDeviceFile, DeviceAudioFormat) -> Unit,
    onViewAll: () -> Unit,
    maxItems: Int = 5,
    showHeader: Boolean = true,
    showViewAll: Boolean = true,
) {
    val recent = remember(recordings, maxItems) {
        recordings
            .sortedByDescending { item -> maxOf(item.updatedAtMs, item.createdAtMs) }
            .take(maxItems.coerceAtLeast(0))
    }
    var pendingSheet by remember { mutableStateOf<RecordingLibraryItem?>(null) }
    var openAfterDownloadId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recordings, openAfterDownloadId) {
        val id = openAfterDownloadId ?: return@LaunchedEffect
        val item = recordings.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val available = item.localMediaAvailability()
        if (available.originalReady || available.canonicalReady) {
            openAfterDownloadId = null
            onOpenRecording(id)
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (showViewAll) TextButton(onClick = onViewAll) { Text("查看全部") }
            }
        }
        if (recent.isEmpty()) {
            Text(
                "暂无录音",
                modifier = Modifier.padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            recent.forEach { item ->
                val availability = item.localMediaAvailability()
                val remoteOnly = !availability.originalReady && !availability.canonicalReady && availability.remoteOriginalAvailable
                val remote = item.sourceRemoteIdentity?.let { id -> deviceFiles.firstOrNull { it.identity == id } }
                val active = (operationState as? FileOperationState.Active)
                    ?.takeIf { it.operation == DeviceFileOperationType.DOWNLOAD && it.remoteIdentity == item.sourceRemoteIdentity }
                RecentRecordingRow(
                    recording = item,
                    remoteOnly = remoteOnly,
                    active = active,
                    onClick = {
                        if (remoteOnly) pendingSheet = item else onOpenRecording(item.id)
                    },
                    onDownload = if (remoteOnly && remote != null && active == null) {
                        { onDownload(remote, DeviceAudioFormat.OPUS) }
                    } else null,
                )
            }
        }
    }

    pendingSheet?.let { item ->
        val remote = item.sourceRemoteIdentity?.let { id -> deviceFiles.firstOrNull { it.identity == id } }
        ModalBottomSheet(onDismissRequest = { pendingSheet = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("该录音尚未下载到手机", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "原始 OPUS 仍保留在录音卡。下载完成后会继续使用现有校验与录音库注册链路。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (remote != null) {
                            openAfterDownloadId = item.id
                            pendingSheet = null
                            onDownload(remote, DeviceAudioFormat.OPUS)
                        }
                    },
                    enabled = remote != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("下载并打开") }
                OutlinedButton(
                    onClick = {
                        if (remote != null) {
                            pendingSheet = null
                            onDownload(remote, DeviceAudioFormat.OPUS)
                        }
                    },
                    enabled = remote != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("仅下载") }
            }
        }
    }
}

@Composable
private fun RecentRecordingRow(
    recording: RecordingLibraryItem,
    remoteOnly: Boolean,
    active: FileOperationState.Active?,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val source = when (recording.sourceType) {
        RecordingSourceType.PHONE_MICROPHONE -> "手机"
        RecordingSourceType.DEVICE_DOWNLOAD -> "录音卡"
        RecordingSourceType.LOCAL_IMPORT -> "本地导入"
        else -> recording.sourceType
    }
    val duration = (recording.mediaDurationMs ?: recording.deviceReportedDurationMs)?.let(::formatRecentDuration)
    val recordedAt = RecordingDisplayTimeFormatter.format(recording.recordedAtLocalIso, recording.createdAtMs)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(recording.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(source, duration, recordedAt).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (remoteOnly) Text("仅在录音卡", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                active?.progress?.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            when {
                active != null -> Text(active.progress?.fraction?.let { "${(it * 100).toInt()}%" } ?: "下载中", style = MaterialTheme.typography.labelMedium)
                onDownload != null -> IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, contentDescription = "下载") }
                else -> Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }
}

private fun formatRecentDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = seconds / 60L
    val remain = seconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, remain)
}
