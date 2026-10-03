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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
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
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                Column {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "录音库菜单",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(sortLabel(LibrarySort.RECORDED, sort)) },
                            onClick = {
                                sort = LibrarySort.RECORDED
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(sortLabel(LibrarySort.DOWNLOADED, sort)) },
                            onClick = {
                                sort = LibrarySort.DOWNLOADED
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(sortLabel(LibrarySort.NAME, sort)) },
                            onClick = {
                                sort = LibrarySort.NAME
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(sortLabel(LibrarySort.SIZE, sort)) },
                            onClick = {
                                sort = LibrarySort.SIZE
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.library_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.library_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = sorted,
                key = { _, item -> item.id },
            ) { index, recording ->
                RecordingLibraryRow(
                    recording = recording,
                    onClick = { onOpenRecording(recording.id) },
                )
                if (index != sorted.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingLibraryRow(
    recording: RecordingLibraryItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            recording.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(
                    recording.recordedAtLocalIso
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        ?.format(DISPLAY_TIME)
                        ?: stringResource(R.string.device_file_unknown),
                )
                append(" · ")
                append(recording.deviceReportedDurationMs?.let(::formatDurationMs) ?: "--")
                append(" · ")
                append(formatBytes(recording.assets.sumOf { it.sizeBytes }))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun sortLabel(
    option: LibrarySort,
    selected: LibrarySort,
): String {
    val label =
        when (option) {
            LibrarySort.RECORDED -> stringResource(R.string.local_sort_recorded)
            LibrarySort.DOWNLOADED -> stringResource(R.string.local_sort_downloaded)
            LibrarySort.NAME -> stringResource(R.string.local_sort_name)
            LibrarySort.SIZE -> stringResource(R.string.local_sort_size)
        }
    return if (option == selected) "✓ $label" else label
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
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
