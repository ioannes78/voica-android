package io.github.ioannes78.voica.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.database.SearchDocumentTypeValue
import io.github.ioannes78.voica.database.SearchIndexStatusValue
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun UnifiedSearchScreen(
    padding: PaddingValues,
    viewModel: UnifiedSearchViewModel,
    onBack: () -> Unit,
    onOpen: (SearchDocumentEntity) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = state.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
        )
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    DisposableEffect(listState) {
        onDispose {
            viewModel.rememberListPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回录音库",
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
            ) {
                Text(
                    "搜索全部内容",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "录音 · 转写 · AI 总结 · 文件夹 · 标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { value ->
                viewModel.setQuery(value)
                scope.launch { listState.scrollToItem(0) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            viewModel.setQuery("")
                            scope.launch { listState.scrollToItem(0) }
                        },
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                }
            },
            placeholder = { Text("搜索转写、总结或录音内容") },
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnifiedSearchFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = {
                        viewModel.setFilter(filter)
                        scope.launch { listState.scrollToItem(0) }
                    },
                    label = { Text(filter.label) },
                )
            }
        }

        val indexStatus = state.indexState?.status
        if (
            indexStatus != null &&
            indexStatus != SearchIndexStatusValue.READY
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when (indexStatus) {
                            SearchIndexStatusValue.REBUILDING -> "正在建立搜索索引…"
                            SearchIndexStatusValue.FAILED -> "搜索索引建立失败"
                            else -> "搜索索引需要建立"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (indexStatus != SearchIndexStatusValue.REBUILDING) {
                        OutlinedButton(onClick = viewModel::rebuildIndex) {
                            Text("重建")
                        }
                    }
                }
            }
        }

        if (state.searching) {
            Text(
                "正在搜索…",
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.message?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.filter == UnifiedSearchFilter.ALL) {
                items(
                    items = state.recordingGroups,
                    key = { "recording-group:" + it.recordingId },
                ) { group ->
                    UnifiedSearchRecordingGroupRow(
                        group = group,
                        query = state.query,
                        expanded = group.recordingId in state.expandedRecordingIds,
                        onToggleExpanded = { expanded ->
                            viewModel.setRecordingExpanded(group.recordingId, expanded)
                        },
                        onOpenRecording = {
                            SearchReturnRuntime.markDetailOpen()
                            onOpen(recordingOpenTarget(group))
                        },
                        onOpen = onOpen,
                    )
                }
                items(
                    items = state.unlinkedResults,
                    key = { "unlinked:" + it.documentId },
                ) { result ->
                    UnifiedSearchResultRow(
                        result = result,
                        query = state.query,
                        onClick = { onOpen(result) },
                    )
                }
            } else {
                items(
                    items = state.results,
                    key = { it.documentId },
                ) { result ->
                    UnifiedSearchResultRow(
                        result = result,
                        query = state.query,
                        onClick = { onOpen(result) },
                    )
                }
            }
        }
    }
}

private fun recordingOpenTarget(group: UnifiedSearchRecordingGroup): SearchDocumentEntity {
    val source = group.hits.first()
    return source.copy(
        rowId = 0L,
        documentId = "recording-open:" + group.recordingId,
        documentType = SearchDocumentTypeValue.RECORDING,
        recordingId = group.recordingId,
        transcriptionId = null,
        revisionId = null,
        sourceAnchorId = null,
        aiSummaryId = null,
        sectionId = null,
        itemId = null,
        folderId = null,
        tagId = null,
        displayTitle = group.recordingTitle,
        displayText = "",
        indexTitle = "",
        indexBody = "",
    )
}

@Composable
private fun UnifiedSearchRecordingGroupRow(
    group: UnifiedSearchRecordingGroup,
    query: String,
    expanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    onOpenRecording: () -> Unit,
    onOpen: (SearchDocumentEntity) -> Unit,
) {
    val transcriptHits = if (expanded) group.transcriptHits else group.transcriptHits.take(2)
    val summaryHits = if (expanded) group.summaryHits else group.summaryHits.take(2)
    val visibleHits = transcriptHits + summaryHits

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenRecording)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HighlightedSearchText(
                    text = group.recordingTitle,
                    query = query,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                )
                val summary = groupMatchSummary(group)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            visibleHits.forEach { hit ->
                HorizontalDivider()
                UnifiedSearchResultRow(
                    result = hit,
                    query = query,
                    onClick = { onOpen(hit) },
                    compact = true,
                )
            }

            if (group.contentHitCount > 4 || (expanded && group.contentHitCount > 0)) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    onClick = { onToggleExpanded(!expanded) },
                ) {
                    Text(
                        if (expanded) {
                            "收起"
                        } else {
                            "查看全部 ${group.contentHitCount} 个内容命中"
                        },
                    )
                }
            }
        }
    }
}

private fun groupMatchSummary(group: UnifiedSearchRecordingGroup): String =
    buildList {
        if (group.fileNameMatched) add("文件名命中")
        if (group.transcriptHits.isNotEmpty()) add("转写 ${group.transcriptHits.size} 处")
        if (group.summaryHits.isNotEmpty()) add("AI 总结 ${group.summaryHits.size} 处")
    }.joinToString(" · ")

@Composable
private fun UnifiedSearchResultRow(
    result: SearchDocumentEntity,
    query: String,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (result.opensRecordingDetail()) {
                        SearchReturnRuntime.markDetailOpen()
                    }
                    onClick()
                }
                .padding(
                    horizontal = if (compact) 12.dp else 6.dp,
                    vertical = if (compact) 8.dp else 10.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                resultTypeLabel(result.documentType),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            HighlightedSearchText(
                text = result.displayTitle,
                query = query,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
        }
        if (result.displayText.isNotBlank()) {
            HighlightedSearchText(
                text = result.displayText,
                query = query,
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

private fun SearchDocumentEntity.opensRecordingDetail(): Boolean =
    recordingId != null &&
        documentType in
        setOf(
            SearchDocumentTypeValue.RECORDING,
            SearchDocumentTypeValue.TRANSCRIPT_UNIT,
            SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
            SearchDocumentTypeValue.SUMMARY_ITEM,
        )

@Composable
private fun HighlightedSearchText(
    text: String,
    query: String,
    modifier: Modifier,
    fontWeight: FontWeight,
) {
    val highlightStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.secondaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    val terms =
        query
            .trim()
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }

    val annotated =
        buildAnnotatedString {
            append(text)
            val lower = text.lowercase(Locale.ROOT)
            terms.forEach { term ->
                val needle = term.lowercase(Locale.ROOT)
                var start = lower.indexOf(needle)
                while (start >= 0) {
                    addStyle(
                        highlightStyle,
                        start = start,
                        end = start + needle.length,
                    )
                    start = lower.indexOf(needle, start + needle.length)
                }
            }
        }

    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = fontWeight),
        maxLines = 3,
    )
}

private fun resultTypeLabel(type: String): String =
    when (type) {
        SearchDocumentTypeValue.RECORDING -> "录音"
        SearchDocumentTypeValue.TRANSCRIPT_UNIT -> "转写"
        SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
        SearchDocumentTypeValue.SUMMARY_ITEM,
        -> "AI 总结"
        SearchDocumentTypeValue.FOLDER -> "文件夹"
        SearchDocumentTypeValue.TAG -> "标签"
        else -> "内容"
    }
