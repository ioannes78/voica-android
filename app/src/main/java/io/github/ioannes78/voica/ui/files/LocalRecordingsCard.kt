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
import androidx.compose.material3.OutlinedTextField
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
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LocalRecordingsCard(
    recordings: List<RecordingLibraryItem>,
    onRename: (String, String) -> Unit,
    onDeleteLocal: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RecordingLibraryItem?>(null) }
    var pendingRename by remember { mutableStateOf<RecordingLibraryItem?>(null) }
    var renameValue by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.local_files_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (recordings.isEmpty()) {
                Text(stringResource(R.string.local_files_empty))
            } else {
                Text(
                    stringResource(R.string.local_recordings_count, recordings.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                recordings.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                        LocalInfoLine(
                            stringResource(R.string.local_file_recorded_at),
                            item.recordedAtLocalIso
                                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                                ?.format(DISPLAY_TIME)
                                ?: stringResource(R.string.device_file_unknown),
                        )
                        LocalInfoLine(
                            stringResource(R.string.local_file_duration),
                            item.deviceReportedDurationMs
                                ?.let(::formatDurationMs)
                                ?: stringResource(R.string.device_file_unknown),
                        )
                        LocalInfoLine(
                            stringResource(R.string.local_file_downloaded_at),
                            Instant.ofEpochMilli(item.downloadedAtMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime()
                                .format(DISPLAY_TIME),
                        )

                        item.assets
                            .sortedBy(::assetSortKey)
                            .forEach { asset ->
                                LocalInfoLine(
                                    assetLabel(asset),
                                    formatBytes(asset.sizeBytes),
                                )
                            }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pendingRename = item
                                    renameValue = item.displayName
                                },
                            ) {
                                Text(stringResource(R.string.local_file_rename))
                            }
                            OutlinedButton(onClick = { pendingDelete = item }) {
                                Text(stringResource(R.string.local_file_delete))
                            }
                        }
                    }
                    if (index != recordings.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingRename?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.local_file_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.local_file_rename_label)) },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val value = renameValue
                        pendingRename = null
                        onRename(item.id, value)
                    },
                    enabled = renameValue.isNotBlank(),
                ) {
                    Text(stringResource(R.string.local_file_rename_confirm))
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
                    Text(stringResource(R.string.local_recording_delete_message))
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

@Composable
private fun assetLabel(asset: RecordingAsset): String =
    when (asset.role) {
        AudioAssetRole.DEVICE_OPUS ->
            stringResource(R.string.local_asset_device_opus)
        AudioAssetRole.DEVICE_WAV ->
            stringResource(R.string.local_asset_device_wav)
        AudioAssetRole.CANONICAL_WAV ->
            stringResource(R.string.local_asset_canonical_wav)
        else ->
            stringResource(R.string.local_asset_other, asset.role)
    }

private fun assetSortKey(asset: RecordingAsset): Int =
    when (asset.role) {
        AudioAssetRole.DEVICE_OPUS -> 0
        AudioAssetRole.DEVICE_WAV -> 1
        AudioAssetRole.CANONICAL_WAV -> 2
        else -> 3
    }

private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
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
