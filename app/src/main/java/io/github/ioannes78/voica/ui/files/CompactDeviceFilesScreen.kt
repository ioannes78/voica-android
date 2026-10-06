package io.github.ioannes78.voica.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.ble.WavSizeProbeState
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.RemoteDeleteBatchState
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDeviceFilesScreen(
    padding: PaddingValues,
    state: DeviceFileListState,
    operationState: FileOperationState,
    batchDeleteState: RemoteDeleteBatchState,
    localRecordings: List<RecordingLibraryItem>,
    canRefresh: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (RemoteDeviceFile, DeviceAudioFormat) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteRemote: (RemoteDeviceFile) -> Unit,
    onDeleteSelected: (List<RemoteDeviceFile>) -> Unit,
    onDismissBatchResult: () -> Unit,
    onRangeProbe: (RemoteDeviceFile) -> Unit,
) {
    val downloadedRoles =
        remember(localRecordings) {
            localRecordings.associate { recording ->
                recording.sourceRemoteIdentity to
                    recording.assets.mapTo(mutableSetOf()) { it.role }
            }
        }
    val active = operationState as? FileOperationState.Active
    val batchBusy = batchDeleteState is RemoteDeleteBatchState.Running
    var selectedFile by remember { mutableStateOf<RemoteDeviceFile?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteDeviceFile?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    var headerMenuExpanded by remember { mutableStateOf(false) }

    fun leaveSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    IconButton(
                        onClick = { leaveSelectionMode() },
                        enabled = !batchBusy,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出选择")
                    }
                    Text(
                        "已选择 " + selectedIds.size + " 项",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            selectedIds =
                                if (selectedIds.size == state.files.size) {
                                    emptySet()
                                } else {
                                    state.files.mapTo(mutableSetOf()) { it.identity }
                                }
                        },
                        enabled = !batchBusy,
                    ) {
                        Text(
                            if (selectedIds.size == state.files.size) {
                                "取消全选"
                            } else {
                                "全选"
                            },
                        )
                    }
                    IconButton(
                        onClick = { batchDeleteConfirm = true },
                        enabled = selectedIds.isNotEmpty() && !batchBusy && active == null && canRefresh,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除所选",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Text(
                        "设备录音",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        state.files.size.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        IconButton(onClick = { headerMenuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = headerMenuExpanded,
                            onDismissRequest = { headerMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("选择文件") },
                                onClick = {
                                    headerMenuExpanded = false
                                    selectionMode = true
                                },
                                enabled = state.files.isNotEmpty() && !batchBusy,
                            )
                        }
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = canRefresh && !batchBusy,
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.device_files_refresh),
                        )
                    }
                }
            }
        }

        if (batchDeleteState is RemoteDeleteBatchState.Running) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "正在删除 " +
                            (batchDeleteState.completed + 1) + " / " +
                            batchDeleteState.total,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (batchDeleteState.total > 0) {
                                batchDeleteState.completed.toFloat() /
                                    batchDeleteState.total.toFloat()
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.files.isEmpty()) {
            item {
                Text(
                    fileListStatusText(state),
                    modifier = Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = state.files,
                key = { it.identity },
            ) { file ->
                val roles = downloadedRoles[file.identity].orEmpty()
                CompactDeviceFileRow(
                    file = file,
                    opusDownloaded = AudioAssetRole.DEVICE_OPUS in roles,
                    wavDownloaded = AudioAssetRole.DEVICE_WAV in roles,
                    activeOperation = active?.takeIf { it.remoteIdentity == file.identity },
                    operationState = operationState,
                    selectionMode = selectionMode,
                    selected = file.identity in selectedIds,
                    onTap = {
                        if (selectionMode) {
                            selectedIds =
                                if (file.identity in selectedIds) {
                                    selectedIds - file.identity
                                } else {
                                    selectedIds + file.identity
                                }
                        } else {
                            selectedFile = file
                        }
                    },
                    onLongPress = {
                        if (!batchBusy) {
                            selectionMode = true
                            selectedIds = selectedIds + file.identity
                        }
                    },
                    onCancelDownload = onCancelDownload,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                )
            }
        }
    }

    selectedFile?.let { file ->
        val roles = downloadedRoles[file.identity].orEmpty()
        ModalBottomSheet(
            onDismissRequest = { selectedFile = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    file.displayFilename,
                    style = MaterialTheme.typography.headlineSmall,
                )
                val unknownText = stringResource(R.string.device_file_unknown)
                val recordedAtText = file.recordedAt?.format(DISPLAY_TIME) ?: unknownText
                val durationText = file.durationSeconds?.let(::formatDuration) ?: unknownText
                Text(
                    recordedAtText + " · " + durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FileDetailLine(
                    label = "OPUS",
                    value =
                        formatBytes(file.sizeBytes) +
                            if (AudioAssetRole.DEVICE_OPUS in roles) " · 已下载" else "",
                )
                FileDetailLine(
                    label = "WAV",
                    value =
                        when (file.wavSizeProbeState) {
                            WavSizeProbeState.AVAILABLE ->
                                (file.wavSizeBytes?.let(::formatBytes) ?: "--") +
                                    if (AudioAssetRole.DEVICE_WAV in roles) " · 已下载" else ""
                            WavSizeProbeState.PROBING -> "正在读取大小…"
                            WavSizeProbeState.NOT_PROBED,
                            WavSizeProbeState.UNAVAILABLE,
                            -> "大小未知"
                        },
                )

                if (file.downloadFilename(DeviceAudioFormat.OPUS) != null &&
                    AudioAssetRole.DEVICE_OPUS !in roles
                ) {
                    Button(
                        onClick = {
                            selectedFile = null
                            onDownload(file, DeviceAudioFormat.OPUS)
                        },
                        enabled = active == null && !batchBusy && canRefresh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.device_file_download_opus))
                    }
                }

                if (file.downloadFilename(DeviceAudioFormat.WAV) != null &&
                    AudioAssetRole.DEVICE_WAV !in roles
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedFile = null
                            onDownload(file, DeviceAudioFormat.WAV)
                        },
                        enabled = active == null && !batchBusy && canRefresh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.device_file_download_wav))
                    }
                }

                if (AudioAssetRole.DEVICE_OPUS in roles) {
                    TextButton(
                        onClick = {
                            selectedFile = null
                            onRangeProbe(file)
                        },
                        enabled = active == null && !batchBusy && canRefresh,
                    ) {
                        Text(stringResource(R.string.device_file_range_probe))
                    }
                }

                TextButton(
                    onClick = {
                        selectedFile = null
                        pendingDelete = file
                    },
                    enabled = active == null && !batchBusy && canRefresh,
                ) {
                    Text(
                        stringResource(R.string.device_file_delete_remote),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    "删除设备录音不会删除手机本地已经下载的录音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.device_file_delete_remote_title)) },
            text = {
                Text(stringResource(R.string.device_file_delete_remote_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteRemote(file)
                    },
                ) {
                    Text(stringResource(R.string.device_file_delete_remote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (batchDeleteConfirm) {
        val selectedFiles = state.files.filter { it.identity in selectedIds }
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text("删除所选设备录音？") },
            text = {
                Text(
                    "将从录音卡顺序删除所选 " + selectedFiles.size +
                        " 条录音对应的 OPUS + WAV，并逐项刷新验证。" +
                        " 已下载到手机录音库的文件不会被删除。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        batchDeleteConfirm = false
                        onDeleteSelected(selectedFiles)
                    },
                    enabled = selectedFiles.isNotEmpty(),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    when (val batch = batchDeleteState) {
        is RemoteDeleteBatchState.Completed -> {
            AlertDialog(
                onDismissRequest = {
                    leaveSelectionMode()
                    onDismissBatchResult()
                },
                title = { Text("批量删除完成") },
                text = {
                    Text(
                        "成功 " + batch.succeeded + " 项，失败 " +
                            batch.failedIdentities.size + " 项。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            leaveSelectionMode()
                            onDismissBatchResult()
                        },
                    ) {
                        Text("完成")
                    }
                },
            )
        }

        is RemoteDeleteBatchState.StoppedUnknown -> {
            AlertDialog(
                onDismissRequest = {
                    leaveSelectionMode()
                    onDismissBatchResult()
                    onRefresh()
                },
                title = { Text("删除结果无法确认") },
                text = {
                    Text(
                        "批量删除已停止。已确认成功 " + batch.succeeded +
                            " 项，当前设备返回结果无法确认。将刷新设备列表后再处理剩余项目。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            leaveSelectionMode()
                            onDismissBatchResult()
                            onRefresh()
                        },
                    ) {
                        Text("刷新列表")
                    }
                },
            )
        }

        RemoteDeleteBatchState.Idle,
        is RemoteDeleteBatchState.Running,
        -> Unit
    }
}

@Composable
private fun CompactDeviceFileRow(
    file: RemoteDeviceFile,
    opusDownloaded: Boolean,
    wavDownloaded: Boolean,
    activeOperation: FileOperationState.Active?,
    operationState: FileOperationState,
    selectionMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .pointerInput(file.identity, selectionMode, selected) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onTap() },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                file.displayFilename,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val unknownText = stringResource(R.string.device_file_unknown)
            val recordedAtText = file.recordedAt?.format(DISPLAY_TIME) ?: unknownText
            val durationText = file.durationSeconds?.let(::formatDuration) ?: unknownText
            val downloadedText = if (opusDownloaded || wavDownloaded) " · 已下载" else ""
            Text(
                recordedAtText + " · " + durationText + " · " +
                    formatBytes(file.sizeBytes) + downloadedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (activeOperation?.operation == DeviceFileOperationType.DOWNLOAD) {
                val progress = activeOperation.progress
                val fraction = progress?.fraction
                if (fraction != null && progress != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            ((fraction * 100).toInt()).toString() + "% · " +
                                formatBytes(progress.receivedBytes) + " / " +
                                formatBytes(progress.expectedBytes),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onCancelDownload) {
                            Text("取消")
                        }
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            val failure = operationState as? FileOperationState.Failed
            if (failure?.remoteIdentity == file.identity) {
                Text(
                    stringResource(
                        R.string.device_file_download_failed,
                        failure.error.code.toUserMessage(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FileDetailLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun fileListStatusText(state: DeviceFileListState): String =
    when (state.freshness) {
        io.github.ioannes78.voica.ble.FileListFreshness.NOT_LOADED ->
            stringResource(R.string.device_files_not_loaded)
        io.github.ioannes78.voica.ble.FileListFreshness.LOADING ->
            stringResource(R.string.device_files_loading)
        io.github.ioannes78.voica.ble.FileListFreshness.FRESH ->
            stringResource(R.string.device_files_count, state.files.size)
        io.github.ioannes78.voica.ble.FileListFreshness.EMPTY ->
            stringResource(R.string.device_files_empty)
        io.github.ioannes78.voica.ble.FileListFreshness.STALE ->
            stringResource(R.string.device_files_stale)
        io.github.ioannes78.voica.ble.FileListFreshness.FAILED ->
            stringResource(R.string.device_files_failed)
    }

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, remainingSeconds)
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            "%.2f GB".format(bytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L ->
            "%.1f MB".format(bytes.toDouble() / 1024.0 / 1024.0)
        bytes >= 1024L ->
            "%.1f KB".format(bytes.toDouble() / 1024.0)
        else -> bytes.toString() + " B"
    }

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
