package io.github.ioannes78.voica.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.FileListErrorCode
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DeviceFilesCard(
    state: DeviceFileListState,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
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
                    DeviceFileRow(file)
                    if (index != state.files.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
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
private fun DeviceFileRow(file: RemoteDeviceFile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
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
