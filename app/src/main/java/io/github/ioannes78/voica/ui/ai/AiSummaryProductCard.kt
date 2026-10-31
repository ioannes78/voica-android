package io.github.ioannes78.voica.ui.ai

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ContentTextExportCoordinator
import io.github.ioannes78.voica.TextContentDocument
import io.github.ioannes78.voica.TextExportFormat
import io.github.ioannes78.voica.TextShareOutcome
import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryStateValue
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun AiSummaryProductCard(
    transcriptionId: String,
    recordingName: String,
    viewModel: AiSummaryViewModel,
    contentViewModel: AiSummaryContentViewModel,
    onSeekEvidence: (Long) -> Unit,
    showTransientHeader: Boolean = true,
    dismissedAiSummaryCandidateId: String? = null,
    onIgnoreCandidate: (String) -> Unit = {},
    dismissedStaleSummaryFingerprint: String? = null,
    onIgnoreStale: (String) -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    regenerateEnabled: Boolean = false,
) {
    val history by viewModel.history.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val currentSummaryId by viewModel.currentSummaryId.collectAsState()
    val candidateId by viewModel.candidateId.collectAsState()
    val stale by viewModel.stale.collectAsState()
    val staleFingerprint by viewModel.staleFingerprint.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val contentState by contentViewModel.state.collectAsState()

    var historyExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var revisionHistoryOpen by remember { mutableStateOf(false) }
    var deleteVersionOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textExport = remember(context) { ContentTextExportCoordinator(context) }
    var pendingTxt by remember { mutableStateOf<TextContentDocument?>(null) }
    var pendingMarkdown by remember { mutableStateOf<TextContentDocument?>(null) }

    LaunchedEffect(transcriptionId) {
        viewModel.bind(transcriptionId)
    }
    LaunchedEffect(selected?.entity?.id, selected?.result) {
        contentViewModel.bind(selected?.entity, selected?.result)
    }

    val txtLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(TextExportFormat.TXT.mimeType),
        ) { uri ->
            val document = pendingTxt
            pendingTxt = null
            if (uri != null && document != null) {
                scope.launch {
                    val result = textExport.exportToUri(document, TextExportFormat.TXT, uri)
                    Toast.makeText(
                        context,
                        if (result.exported) "TXT 已导出" else result.error ?: "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    val markdownLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(TextExportFormat.MARKDOWN.mimeType),
        ) { uri ->
            val document = pendingMarkdown
            pendingMarkdown = null
            if (uri != null && document != null) {
                scope.launch {
                    val result = textExport.exportToUri(document, TextExportFormat.MARKDOWN, uri)
                    Toast.makeText(
                        context,
                        if (result.exported) "Markdown 已导出" else result.error ?: "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    val exportDocument =
        contentState.document
            ?.takeIf { contentState.summaryId == selected?.entity?.id }
            ?.let { document ->
                AiSummaryContentExportFormatter.build(
                    recordingName = recordingName,
                    document = document,
                    modelName = selected?.entity?.model,
                )
            }

    val visibleCandidateId = candidateId?.takeUnless { it == dismissedAiSummaryCandidateId }
    val selectedId = selected?.entity?.id
    val selectedIsCurrent = selectedId != null && selectedId == currentSummaryId
    val selectedIsCandidate = selectedId != null && selectedId == visibleCandidateId
    val selectedIsHistorical = selectedId != null && !selectedIsCurrent && !selectedIsCandidate

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val visibleStaleFingerprint =
            staleFingerprint?.takeUnless { it == dismissedStaleSummaryFingerprint }
        if (
            stale &&
            visibleStaleFingerprint != null &&
            selected?.entity?.status == AiSummaryStateValue.COMPLETED
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "当前总结基于较早的转写内容",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onIgnoreStale(visibleStaleFingerprint) }) {
                        Text("忽略")
                    }
                }
            }
        }

        if (showTransientHeader) {
            visibleCandidateId?.let { newSummaryId ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "已生成新的总结",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { viewModel.previewCandidate(newSummaryId) }) {
                            Text("查看")
                        }
                        Button(onClick = { viewModel.adoptCandidate(newSummaryId) }) {
                            Text("使用")
                        }
                        TextButton(
                            onClick = {
                                onIgnoreCandidate(newSummaryId)
                                if (selectedId == newSummaryId) viewModel.showCurrent()
                            },
                        ) {
                            Text("忽略")
                        }
                    }
                }
            }
        }

        if (selectedIsCandidate || selectedIsHistorical) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (selectedIsCandidate) {
                            "正在查看新结果 · 当前总结尚未切换"
                        } else {
                            "正在查看历史版本 · 当前总结未改变"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = viewModel::showCurrent) {
                        Text("返回当前")
                    }
                    selectedId?.let { id ->
                        Button(onClick = { viewModel.adoptSummaryResult(id) }) {
                            Text("使用此版本")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contentState.document
                        ?.takeIf { contentState.summaryId == selected?.entity?.id }
                        ?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: "总结",
                    style = MaterialTheme.typography.titleLarge,
                )
                selected?.entity?.model
                    ?.takeIf { it.isNotBlank() }
                    ?.let { model ->
                        Text(
                            model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                if (contentState.currentRevisionId != null) {
                    Text(
                        "已编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                enabled = history.isNotEmpty(),
                onClick = { historyExpanded = true },
            ) {
                Text("总结版本")
            }

            Button(
                enabled =
                    contentState.document != null &&
                        selected?.entity?.status == AiSummaryStateValue.COMPLETED,
                onClick = { editorOpen = true },
            ) {
                Text("编辑")
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多总结操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    onRegenerate?.let { regenerate ->
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            enabled = regenerateEnabled,
                            onClick = {
                                menuExpanded = false
                                regenerate()
                            },
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("复制") },
                        enabled = exportDocument?.plainText?.isNotBlank() == true,
                        onClick = {
                            menuExpanded = false
                            val document = exportDocument
                            val copied =
                                document != null &&
                                    textExport.copyToClipboard(document.baseName, document.plainText)
                            Toast.makeText(
                                context,
                                if (copied) "已复制总结" else "没有可复制的内容",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        enabled = exportDocument?.plainText?.isNotBlank() == true,
                        onClick = {
                            menuExpanded = false
                            exportDocument?.let { document ->
                                scope.launch {
                                    when (
                                        val result =
                                            textExport.prepareShare(
                                                document,
                                                TextExportFormat.TXT,
                                            )
                                    ) {
                                        is TextShareOutcome.Ready -> context.startActivity(result.intent)
                                        is TextShareOutcome.Failed ->
                                            Toast.makeText(
                                                context,
                                                result.reason,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 TXT") },
                        enabled = exportDocument?.plainText?.isNotBlank() == true,
                        onClick = {
                            menuExpanded = false
                            exportDocument?.let { document ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    scope.launch {
                                        val result =
                                            textExport.exportToDownloads(
                                                document,
                                                TextExportFormat.TXT,
                                            )
                                        Toast.makeText(
                                            context,
                                            if (result.exported) {
                                                "已导出到 Downloads/WENZHI"
                                            } else {
                                                result.error ?: "导出失败"
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                } else {
                                    pendingTxt = document
                                    txtLauncher.launch(
                                        document.baseName + TextExportFormat.TXT.extension,
                                    )
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        enabled = exportDocument?.markdownText?.isNotBlank() == true,
                        onClick = {
                            menuExpanded = false
                            exportDocument?.let { document ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    scope.launch {
                                        val result =
                                            textExport.exportToDownloads(
                                                document,
                                                TextExportFormat.MARKDOWN,
                                            )
                                        Toast.makeText(
                                            context,
                                            if (result.exported) {
                                                "已导出到 Downloads/WENZHI"
                                            } else {
                                                result.error ?: "导出失败"
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                } else {
                                    pendingMarkdown = document
                                    markdownLauncher.launch(
                                        document.baseName + TextExportFormat.MARKDOWN.extension,
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("修订历史") },
                        enabled = selected != null,
                        onClick = {
                            menuExpanded = false
                            revisionHistoryOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复原始结果") },
                        enabled = contentState.currentRevisionId != null,
                        onClick = {
                            menuExpanded = false
                            contentViewModel.restoreOriginal()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除总结") },
                        enabled = selected != null,
                        onClick = {
                            menuExpanded = false
                            deleteVersionOpen = true
                        },
                    )
                }
            }
        }

        val current = selected
        if (current == null) {
            Text(
                "暂无总结。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            when {
                contentState.summaryId == current.entity.id && contentState.document != null ->
                    AiSummaryRevisionBody(
                        document = checkNotNull(contentState.document),
                        evidenceByRef = current.evidenceByRef,
                        onSeekEvidence = onSeekEvidence,
                    )

                !current.entity.displayText.isNullOrBlank() ->
                    Text(current.entity.displayText.orEmpty())

                else ->
                    Text(
                        "该总结没有可显示的内容。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        contentState.notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (historyExpanded) {
        AlertDialog(
            onDismissRequest = { historyExpanded = false },
            title = { Text("总结版本") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    history.forEach { summary ->
                        val role =
                            when (summary.id) {
                                currentSummaryId -> "当前"
                                visibleCandidateId -> "新结果"
                                else -> "历史"
                            }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                historyExpanded = false
                                viewModel.selectSummary(summary.id)
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(summaryHistoryLabel(summary, role, summary.id == selectedId))
                                Text(
                                    summaryProductModelLabel(summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyExpanded = false }) {
                    Text("关闭")
                }
            },
        )
    }

    if (editorOpen) {
        contentState.document?.let { document ->
            AiSummaryEditorDialog(
                initial = document,
                onDismiss = { editorOpen = false },
                onSave = { revised ->
                    editorOpen = false
                    contentViewModel.saveRevision(revised)
                },
            )
        }
    }

    if (revisionHistoryOpen) {
        AiSummaryRevisionHistoryDialog(
            state = contentState,
            onDismiss = { revisionHistoryOpen = false },
            onSelect = contentViewModel::selectRevision,
            onDelete = contentViewModel::deleteRevision,
        )
    }

    if (deleteVersionOpen) {
        AlertDialog(
            onDismissRequest = { deleteVersionOpen = false },
            title = { Text("删除当前查看的总结？") },
            text = { Text("该操作只删除当前查看的总结及其修订，不会删除录音或转写。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteVersionOpen = false
                        contentViewModel.deleteVersion {
                            viewModel.bind(transcriptionId)
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteVersionOpen = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun summaryHistoryLabel(
    summary: AiSummaryEntity,
    role: String,
    selected: Boolean,
): String {
    val prefix = if (selected) "✓ " else ""
    val time =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(summary.completedAtMs ?: summary.createdAtMs))
    return "$prefix$role · $time"
}

private fun summaryProductModelLabel(summary: AiSummaryEntity): String =
    if (summary.promptVersion == OfflineSummaryPromptContract.PROMPT_VERSION) {
        "Gemma 4 E2B · 本地"
    } else {
        summary.model.ifBlank { "未知模型" }
    }
