package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.ioannes78.voica.database.FolderEntity
import io.github.ioannes78.voica.database.LibrarySort
import io.github.ioannes78.voica.database.RecordingLibraryRow
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.TagEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingLibraryScreen(
    padding: PaddingValues,
    state: RecordingLibraryUiState,
    onQueryChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onFavoriteFilterChange: (Boolean) -> Unit,
    onCompletedTranscriptionFilterChange: (Boolean) -> Unit,
    onCompletedSummaryFilterChange: (Boolean) -> Unit,
    onFolderFilter: (String?) -> Unit,
    onUncategorizedFilter: (Boolean) -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onToggleSourceType: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetSelectedFavorite: (Boolean) -> Unit,
    onMoveSelectedToFolder: (String?) -> Unit,
    onAddTagToSelected: (String) -> Unit,
    onRemoveTagFromSelected: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateTag: (String) -> Unit,
) {
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var folderMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var tagMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var batchFolderExpanded by rememberSaveable { mutableStateOf(false) }
    var batchTagExpanded by rememberSaveable { mutableStateOf(false) }
    var createFolderDialog by rememberSaveable { mutableStateOf(false) }
    var createTagDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (state.selectionMode) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已选 " + state.selectedCount + " 项",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSelectAll) { Text("全选") }
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消选择")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { onSetSelectedFavorite(true) }) {
                            Text("收藏")
                        }
                        TextButton(onClick = { onSetSelectedFavorite(false) }) {
                            Text("取消收藏")
                        }
                        Column {
                            TextButton(onClick = { batchFolderExpanded = true }) {
                                Text("文件夹")
                            }
                            DropdownMenu(
                                expanded = batchFolderExpanded,
                                onDismissRequest = { batchFolderExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("移出文件夹") },
                                    onClick = {
                                        onMoveSelectedToFolder(null)
                                        batchFolderExpanded = false
                                    },
                                )
                                state.folders.forEach { folder ->
                                    DropdownMenuItem(
                                        text = { Text(folder.name) },
                                        onClick = {
                                            onMoveSelectedToFolder(folder.folderId)
                                            batchFolderExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Column {
                            TextButton(onClick = { batchTagExpanded = true }) {
                                Text("标签")
                            }
                            DropdownMenu(
                                expanded = batchTagExpanded,
                                onDismissRequest = { batchTagExpanded = false },
                            ) {
                                state.tags.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text("添加 · " + tag.name) },
                                        onClick = {
                                            onAddTagToSelected(tag.tagId)
                                            batchTagExpanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("移除 · " + tag.name) },
                                        onClick = {
                                            onRemoveTagFromSelected(tag.tagId)
                                            batchTagExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
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
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (option == state.criteria.sort) "✓ " else "") +
                                                sortLabel(option),
                                        )
                                    },
                                    onClick = {
                                        onSortChange(option)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!state.selectionMode) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    placeholder = { Text("搜索录音、文件夹、标签") },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.criteria.favoriteOnly,
                        onClick = {
                            onFavoriteFilterChange(!state.criteria.favoriteOnly)
                        },
                        label = { Text("收藏") },
                    )
                    FilterChip(
                        selected = state.criteria.completedTranscriptionOnly,
                        onClick = {
                            onCompletedTranscriptionFilterChange(
                                !state.criteria.completedTranscriptionOnly,
                            )
                        },
                        label = { Text("已转写") },
                    )
                    FilterChip(
                        selected = state.criteria.completedSummaryOnly,
                        onClick = {
                            onCompletedSummaryFilterChange(
                                !state.criteria.completedSummaryOnly,
                            )
                        },
                        label = { Text("有总结") },
                    )
                    FilterChip(
                        selected =
                            RecordingSourceType.DEVICE_DOWNLOAD in state.criteria.sourceTypes,
                        onClick = {
                            onToggleSourceType(RecordingSourceType.DEVICE_DOWNLOAD)
                        },
                        label = { Text("录音卡") },
                    )
                    FilterChip(
                        selected =
                            RecordingSourceType.LOCAL_IMPORT in state.criteria.sourceTypes,
                        onClick = {
                            onToggleSourceType(RecordingSourceType.LOCAL_IMPORT)
                        },
                        label = { Text("手机导入") },
                    )
                    Column {
                        FilterChip(
                            selected =
                                state.criteria.folderId != null ||
                                    state.criteria.uncategorizedOnly,
                            onClick = { folderMenuExpanded = true },
                            label = {
                                Text(
                                    folderLabel(
                                        state.criteria.folderId,
                                        state.criteria.uncategorizedOnly,
                                        state.folders,
                                    ),
                                )
                            },
                        )
                        DropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部文件夹") },
                                onClick = {
                                    onFolderFilter(null)
                                    onUncategorizedFilter(false)
                                    folderMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("未分类") },
                                onClick = {
                                    onUncategorizedFilter(true)
                                    folderMenuExpanded = false
                                },
                            )
                            state.folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        onFolderFilter(folder.folderId)
                                        folderMenuExpanded = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("新建文件夹…") },
                                onClick = {
                                    folderMenuExpanded = false
                                    createFolderDialog = true
                                },
                            )
                        }
                    }
                    Column {
                        FilterChip(
                            selected = state.criteria.tagIds.isNotEmpty(),
                            onClick = { tagMenuExpanded = true },
                            label = {
                                Text(
                                    if (state.criteria.tagIds.isEmpty()) {
                                        "标签"
                                    } else {
                                        "标签 " + state.criteria.tagIds.size
                                    },
                                )
                            },
                        )
                        DropdownMenu(
                            expanded = tagMenuExpanded,
                            onDismissRequest = { tagMenuExpanded = false },
                        ) {
                            state.tags.forEach { tag ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (tag.tagId in state.criteria.tagIds) "✓ " else "") +
                                                tag.name,
                                        )
                                    },
                                    onClick = { onToggleTagFilter(tag.tagId) },
                                )
                            }
                            if (state.tags.isNotEmpty()) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("新建标签…") },
                                onClick = {
                                    tagMenuExpanded = false
                                    createTagDialog = true
                                },
                            )
                        }
                    }
                    if (hasActiveFilters(state)) {
                        TextButton(onClick = onClearFilters) {
                            Text("清除筛选")
                        }
                    }
                }
            }
        }

        if (state.recordings.isEmpty()) {
            item {
                EmptyLibrary(state)
            }
        } else {
            itemsIndexed(
                items = state.recordings,
                key = { _, item -> item.id },
            ) { index, recording ->
                RecordingRow(
                    recording = recording,
                    selectionMode = state.selectionMode,
                    selected = recording.id in state.selectedIds,
                    onOpen = { onOpenRecording(recording.id) },
                    onLongPress = { onEnterSelection(recording.id) },
                    onToggleSelection = { onToggleSelection(recording.id) },
                    onToggleFavorite = { onToggleFavorite(recording.id) },
                )
                if (index != state.recordings.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }

    if (createFolderDialog) {
        CreateNameDialog(
            title = "新建文件夹",
            onDismiss = { createFolderDialog = false },
            onConfirm = {
                onCreateFolder(it)
                createFolderDialog = false
            },
        )
    }
    if (createTagDialog) {
        CreateNameDialog(
            title = "新建标签",
            onDismiss = { createTagDialog = false },
            onConfirm = {
                onCreateTag(it)
                createTagDialog = false
            },
        )
    }
}

@Composable
private fun EmptyLibrary(state: RecordingLibraryUiState) {
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
            if (state.query.isNotBlank() || hasActiveFilters(state)) {
                "没有符合条件的录音"
            } else {
                stringResource(R.string.library_empty_title)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (state.query.isNotBlank() || hasActiveFilters(state)) {
                "调整搜索词或筛选条件后再试"
            } else {
                stringResource(R.string.library_empty_body)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    recording: RecordingLibraryRow,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val recordedAt =
        recording.recordedAtLocalIso
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?.format(DISPLAY_TIME)
            ?: "未记录时间"
    val duration = recording.mediaDurationMs ?: recording.deviceReportedDurationMs

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else onOpen()
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                recording.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recordedAt + " · " +
                    (duration?.let(::formatDurationMs) ?: "--") + " · " +
                    formatBytes(recording.physicalAudioBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metadataLine(recording).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selectionMode) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector =
                        if (recording.isFavorite) {
                            Icons.Outlined.Star
                        } else {
                            Icons.Outlined.StarBorder
                        },
                    contentDescription =
                        if (recording.isFavorite) "取消收藏" else "收藏",
                    tint =
                        if (recording.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun CreateNameDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun hasActiveFilters(state: RecordingLibraryUiState): Boolean =
    state.criteria.favoriteOnly ||
        state.criteria.completedTranscriptionOnly ||
        state.criteria.completedSummaryOnly ||
        state.criteria.folderId != null ||
        state.criteria.uncategorizedOnly ||
        state.criteria.tagIds.isNotEmpty() ||
        state.criteria.sourceTypes.isNotEmpty()

private fun folderLabel(
    folderId: String?,
    uncategorizedOnly: Boolean,
    folders: List<FolderEntity>,
): String {
    if (uncategorizedOnly) return "未分类"
    if (folderId == null) return "文件夹"
    return folders.firstOrNull { it.folderId == folderId }?.name ?: "文件夹"
}

private fun metadataLine(recording: RecordingLibraryRow): String {
    val parts = mutableListOf<String>()
    recording.folderName?.let(parts::add)
    if (recording.tagNames.isNotEmpty()) {
        parts += recording.tagNames.joinToString(" · ")
    }
    if (recording.hasCompletedTranscription) parts += "已转写"
    if (recording.hasCompletedSummary) parts += "有总结"
    if (recording.sourceType == RecordingSourceType.LOCAL_IMPORT) parts += "手机导入"
    return parts.joinToString(" · ")
}

private fun sortLabel(option: LibrarySort): String =
    when (option) {
        LibrarySort.RECORDED -> "按录制时间"
        LibrarySort.DOWNLOADED -> "按下载时间"
        LibrarySort.ADDED -> "按加入时间"
        LibrarySort.UPDATED -> "按最近修改"
        LibrarySort.NAME -> "按名称"
        LibrarySort.SIZE -> "按大小"
        LibrarySort.FAVORITE -> "收藏优先"
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
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
