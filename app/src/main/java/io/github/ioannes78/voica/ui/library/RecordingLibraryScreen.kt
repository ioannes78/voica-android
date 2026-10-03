package io.github.ioannes78.voica.ui.library

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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.database.RecordingLibraryItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LibrarySort {
    RECORDED,
    DOWNLOADED,
    NAME,
    SIZE,
}

@Composable
fun RecordingLibraryScreen(
    padding: PaddingValues,
    recordings: List<RecordingLibraryItem>,
    onOpenRecording: (String) -> Unit,
) {
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECORDED) }
    val sorted = remember(recordings, sort) {
        when (sort) {
            LibrarySort.RECORDED ->
                recordings.sortedWith(
                    compareByDescending<RecordingLibraryItem> {
                        it.recordedAtLocalIso ?: ""
                    }.thenByDescending { it.downloadedAtMs },
                )
            LibrarySort.DOWNLOADED -> recordings.sortedByDescending { it.downloadedAtMs }
            LibrarySort.NAME -> recordings.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            LibrarySort.SIZE -> recordings.sortedByDescending { item ->
                item.assets.sumOf { it.sizeBytes }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.library_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = sort == LibrarySort.RECORDED,
                    onClick = { sort = LibrarySort.RECORDED },
                    label = { Text(stringResource(R.string.local_sort_recorded)) },
                )
                FilterChip(
                    selected = sort == LibrarySort.DOWNLOADED,
                    onClick = { sort = LibrarySort.DOWNLOADED },
                    label = { Text(stringResource(R.string.local_sort_downloaded)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = sort == LibrarySort.NAME,
                    onClick = { sort = LibrarySort.NAME },
                    label = { Text(stringResource(R.string.local_sort_name)) },
                )
                FilterChip(
                    selected = sort == LibrarySort.SIZE,
                    onClick = { sort = LibrarySort.SIZE },
                    label = { Text(stringResource(R.string.local_sort_size)) },
                )
            }
        }

        if (sorted.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LibraryMusic,
                            contentDescription = null,
                        )
                        Text(
                            stringResource(R.string.library_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.library_empty_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(
                items = sorted,
                key = { it.id },
            ) { recording ->
                RecordingLibraryRow(
                    recording = recording,
                    onClick = { onOpenRecording(recording.id) },
                )
            }
        }
    }
}

@Composable
private fun RecordingLibraryRow(
    recording: RecordingLibraryItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    recording.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    recording.recordedAtLocalIso
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        ?.format(DISPLAY_TIME)
                        ?: stringResource(R.string.device_file_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        recording.deviceReportedDurationMs?.let(::formatDurationMs) ?: "--",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        formatBytes(recording.assets.sumOf { it.sizeBytes }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
            )
        }
    }
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
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
