package io.github.ioannes78.voica.ui.files

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CompactDeviceFilesScreen(
    padding: PaddingValues,
    state: DeviceFileListState,
    operationState: FileOperationState,
    localRecordings: List<RecordingLibraryItem>,
    canRefresh: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (RemoteDeviceFile, DeviceAudioFormat) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteRemote: (RemoteDeviceFile) -> Unit,
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
    var selectedFile by remember { mutableStateOf<RemoteDeviceFile?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteDeviceFile?>(null) }

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
                IconButton(
                    onClick = onRefresh,
                    enabled = canRefresh,
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.device_files_refresh),
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
                    onClick = { selectedFile = file },
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
                        enabled = active == null,
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
                        enabled = active == null,
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
                        enabled = active == null,
                    ) {
                        Text(stringResource(R.string.device_file_range_probe))
                    }
                }

                TextButton(
                    onClick = {
                        selectedFile = null
                        pendingDelete = file
                    },
                    enabled = active == null,
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
}

@Composable
private fun CompactDeviceFileRow(
    file: RemoteDeviceFile,
    opusDownloaded: Boolean,
    wavDownloaded: Boolean,
    activeOperation: FileOperationState.Active?,
    operationState: FileOperationState,
    onClick: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
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
                stringResource(R.string.device_file_download_failed, failure.error.code.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
