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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileListErrorCode
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DeviceFilesCard(
    state: DeviceFileListState,
    operationState: FileOperationState,
    localRecordings: List<LocalRecordingArtifact>,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onDownload: (RemoteDeviceFile) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteRemote: (RemoteDeviceFile) -> Unit,
    onRangeProbe: (RemoteDeviceFile) -> Unit,
) {
    val downloadedIds = localRecordings.mapTo(mutableSetOf()) { it.sourceRemoteIdentity }
    val active = operationState as? FileOperationState.Active
    var pendingDelete by remember { mutableStateOf<RemoteDeviceFile?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.device_files_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.lastUpdatedTimeMs != null) {
                    Text(
                        stringResource(R.string.device_files_count, state.files.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            FileListStatus(state)

            Button(
                onClick = onRefresh,
                enabled = canRefresh,
            ) {
                Text(stringResource(R.string.device_files_refresh))
            }

            if (state.files.isNotEmpty()) {
                HorizontalDivider()
                state.files.forEachIndexed { index, file ->
                    DeviceFileRow(
                        file = file,
                        isDownloaded = file.identity in downloadedIds,
                        activeOperation = active,
                        operationState = operationState,
                        onDownload = { onDownload(file) },
                        onCancelDownload = onCancelDownload,
                        onDeleteRemote = { pendingDelete = file },
                        onRangeProbe = { onRangeProbe(file) },
                    )
                    if (index != state.files.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.device_file_delete_remote_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(file.displayFilename)
                    Text(
                        stringResource(
                            R.string.device_file_delete_remote_message,
                        ),
                    )
                }
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
private fun FileListStatus(state: DeviceFileListState) {
    val text = when (state.freshness) {
        FileListFreshness.NOT_LOADED ->
            stringResource(R.string.device_files_not_loaded)
        FileListFreshness.LOADING ->
            stringResource(R.string.device_files_loading)
        FileListFreshness.FRESH ->
            stringResource(R.string.device_files_count, state.files.size)
        FileListFreshness.EMPTY ->
            stringResource(R.string.device_files_empty)
        FileListFreshness.STALE ->
            stringResource(R.string.device_files_stale)
        FileListFreshness.FAILED ->
            stringResource(R.string.device_files_failed)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)

    state.lastError?.let { error ->
        val errorText = when (error.code) {
            FileListErrorCode.RECORDING_ACTIVE ->
                stringResource(R.string.device_files_recording_blocked)
            FileListErrorCode.NOT_READY,
            FileListErrorCode.DISCONNECTED,
            ->
                stringResource(R.string.device_files_disconnected)
            else ->
                stringResource(R.string.device_files_failed)
        }
        Text(errorText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceFileRow(
    file: RemoteDeviceFile,
    isDownloaded: Boolean,
    activeOperation: FileOperationState.Active?,
    operationState: FileOperationState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteRemote: () -> Unit,
    onRangeProbe: () -> Unit,
) {
    val activeDownload =
        activeOperation?.operation == DeviceFileOperationType.DOWNLOAD &&
            activeOperation.remoteIdentity == file.identity
    val activeDelete =
        activeOperation?.operation == DeviceFileOperationType.DELETE_REMOTE &&
            activeOperation.remoteIdentity == file.identity

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(file.displayFilename, style = MaterialTheme.typography.titleSmall)
        FileInfoLine(
            stringResource(R.string.device_file_time),
            file.recordedAt?.format(DISPLAY_TIME)
                ?: stringResource(R.string.device_file_unknown),
        )
        FileInfoLine(
            stringResource(R.string.device_file_duration),
            file.durationSeconds?.let(::formatDuration)
                ?: stringResource(R.string.device_file_unknown),
        )
        FileInfoLine(
            stringResource(R.string.device_file_size),
            formatBytes(file.sizeBytes),
        )

        when {
            activeDownload -> {
                val progress = activeOperation.progress
                val percent = progress?.fraction?.let { (it * 100).toInt() }
                Text(
                    if (percent != null && progress != null) {
                        stringResource(
                            R.string.device_file_downloading_progress,
                            percent,
                            formatBytes(progress.receivedBytes),
                            formatBytes(progress.expectedBytes),
                        )
                    } else {
                        stringResource(R.string.device_file_downloading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.device_file_cancel_download))
                }
            }

            activeDelete -> {
                Text(
                    stringResource(R.string.device_file_deleting_remote),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                if (isDownloaded) {
                    Text(
                        stringResource(R.string.device_file_downloaded),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isDownloaded) {
                        Button(
                            onClick = onDownload,
                            enabled = activeOperation == null,
                        ) {
                            Text(stringResource(R.string.device_file_download))
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRangeProbe,
                            enabled = activeOperation == null,
                        ) {
                            Text(stringResource(R.string.device_file_range_probe))
                        }
                    }
                    OutlinedButton(
                        onClick = onDeleteRemote,
                        enabled = activeOperation == null,
                    ) {
                        Text(stringResource(R.string.device_file_delete_remote))
                    }
                }
            }
        }

        val failure = operationState as? FileOperationState.Failed
        if (failure?.remoteIdentity == file.identity) {
            Text(
                stringResource(
                    if (failure.operation == DeviceFileOperationType.DELETE_REMOTE) {
                        R.string.device_file_delete_remote_failed
                    } else {
                        R.string.device_file_download_failed
                    },
                    failure.error.code.name,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val unknown = operationState as? FileOperationState.OutcomeUnknown
        if (unknown?.remoteIdentity == file.identity) {
            Text(
                stringResource(R.string.device_file_delete_remote_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val cancelled = operationState as? FileOperationState.Cancelled
        if (cancelled?.remoteIdentity == file.identity) {
            Text(
                stringResource(R.string.device_file_download_cancelled),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FileInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
