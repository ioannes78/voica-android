package io.github.ioannes78.voica.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LocalRecordingsCard(
    recordings: List<LocalRecordingArtifact>,
    onDeleteLocal: (LocalRecordingArtifact) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<LocalRecordingArtifact?>(null) }

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
                    stringResource(R.string.local_files_count, recordings.size),
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
                        Text(item.displayFilename, style = MaterialTheme.typography.titleSmall)
                        LocalInfoLine(
                            stringResource(R.string.local_file_recorded_at),
                            item.recordedAt?.format(DISPLAY_TIME)
                                ?: stringResource(R.string.device_file_unknown),
                        )
                        LocalInfoLine(
                            stringResource(R.string.local_file_downloaded_at),
                            Instant.ofEpochMilli(item.downloadedAtMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime()
                                .format(DISPLAY_TIME),
                        )
                        LocalInfoLine(
                            stringResource(R.string.local_file_size),
                            formatBytes(item.sizeBytes),
                        )
                        LocalInfoLine(
                            stringResource(R.string.local_file_format),
                            item.container.name,
                        )
                        OutlinedButton(onClick = { pendingDelete = item }) {
                            Text(stringResource(R.string.local_file_delete))
                        }
                    }
                    if (index != recordings.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.local_file_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayFilename)
                    Text(stringResource(R.string.local_file_delete_message))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteLocal(item)
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
