package io.github.ioannes78.voica.ui.transcript

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ioannes78.voica.ContentTextExportCoordinator
import io.github.ioannes78.voica.TextContentDocument
import io.github.ioannes78.voica.TextExportFormat
import io.github.ioannes78.voica.TextShareOutcome
import io.github.ioannes78.voica.transcript.RevisionParagraphDraft
import io.github.ioannes78.voica.transcript.RevisionTimingQuality
import io.github.ioannes78.voica.transcript.TranscriptRevisionEditor
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

enum class TranscriptViewMode {
    READING,
    TIMELINE,
}

@Composable
fun TranscriptContentActionBar(
    recordingName: String,
    mode: TranscriptViewMode,
    state: TranscriptContentState,
    viewModel: TranscriptContentViewModel,
    onModeChange: (TranscriptViewMode) -> Unit,
    onVersionDeleted: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var renameValue by remember(state.transcriptionId, state.displayName) {
        mutableStateOf(state.displayName.orEmpty())
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textExport = remember(context) { ContentTextExportCoordinator(context) }
    var pendingTxtDocument by remember { mutableStateOf<TextContentDocument?>(null) }
    var pendingMarkdownDocument by remember { mutableStateOf<TextContentDocument?>(null) }
    val txtLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(TextExportFormat.TXT.mimeType),
        ) { uri ->
            val document = pendingTxtDocument
            pendingTxtDocument = null
            if (uri != null && document != null) {
                scope.launch {
                    val result =
                        textExport.exportToUri(
                            document = document,
                            format = TextExportFormat.TXT,
                            destinationUri = uri,
                        )
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
            val document = pendingMarkdownDocument
            pendingMarkdownDocument = null
            if (uri != null && document != null) {
                scope.launch {
                    val result =
                        textExport.exportToUri(
                            document = document,
                            format = TextExportFormat.MARKDOWN,
                            destinationUri = uri,
                        )
                    Toast.makeText(
                        context,
                        if (result.exported) "Markdown 已导出" else result.error ?: "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    val exportDocument =
        remember(
            recordingName,
            state.displayName,
            state.currentRevisionId,
            state.paragraphs,
        ) {
            TranscriptContentExportFormatter.build(
                recordingName = recordingName,
                versionName = state.displayName,
                paragraphs = state.paragraphs,
                includeSpeaker = true,
                includeTime = false,
            )
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == TranscriptViewMode.READING,
                onClick = { onModeChange(TranscriptViewMode.READING) },
                label = { Text("阅读") },
            )
            FilterChip(
                selected = mode == TranscriptViewMode.TIMELINE,
                onClick = { onModeChange(TranscriptViewMode.TIMELINE) },
                label = { Text("时间轴") },
            )
            Column(modifier = Modifier.weight(1f)) {
                val name = state.displayName?.takeIf { it.isNotBlank() }
                Text(
                    name ?: "当前转写",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                if (state.currentRevisionId != null) {
                    Text(
                        "已人工整理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.foundation.layout.Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = state.transcriptionId != null,
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "转写内容操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑整理稿") },
                        enabled = state.paragraphs.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            editorOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("修订历史") },
                        onClick = {
                            menuExpanded = false
                            historyOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制阅读稿") },
                        enabled = exportDocument.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            val copied =
                                textExport.copyToClipboard(
                                    label = exportDocument.baseName,
                                    text = exportDocument.plainText,
                                )
                            Toast.makeText(
                                context,
                                if (copied) "已复制阅读稿" else "没有可复制的文本",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("分享阅读稿") },
                        enabled = exportDocument.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            scope.launch {
                                when (
                                    val outcome =
                                        textExport.prepareShare(
                                            document = exportDocument,
                                            format = TextExportFormat.TXT,
                                        )
                                ) {
                                    is TextShareOutcome.Ready ->
                                        context.startActivity(outcome.intent)
                                    is TextShareOutcome.Failed ->
                                        Toast.makeText(
                                            context,
                                            outcome.reason,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 TXT") },
                        enabled = exportDocument.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                scope.launch {
                                    val result =
                                        textExport.exportToDownloads(
                                            exportDocument,
                                            TextExportFormat.TXT,
                                        )
                                    Toast.makeText(
                                        context,
                                        if (result.exported) {
                                            "已导出到 Downloads/Voica"
                                        } else {
                                            result.error ?: "导出失败"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                pendingTxtDocument = exportDocument
                                txtLauncher.launch(
                                    exportDocument.baseName + TextExportFormat.TXT.extension,
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        enabled = exportDocument.markdownText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                scope.launch {
                                    val result =
                                        textExport.exportToDownloads(
                                            exportDocument,
                                            TextExportFormat.MARKDOWN,
                                        )
                                    Toast.makeText(
                                        context,
                                        if (result.exported) {
                                            "已导出到 Downloads/Voica"
                                        } else {
                                            result.error ?: "导出失败"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                pendingMarkdownDocument = exportDocument
                                markdownLauncher.launch(
                                    exportDocument.baseName +
                                        TextExportFormat.MARKDOWN.extension,
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("恢复模型原文") },
                        enabled = state.currentRevisionId != null,
                        onClick = {
                            menuExpanded = false
                            viewModel.restoreModelOriginal()
                            onModeChange(TranscriptViewMode.TIMELINE)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("重命名转写版本") },
                        onClick = {
                            menuExpanded = false
                            renameValue = state.displayName.orEmpty()
                            renameOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除当前转写版本") },
                        onClick = {
                            menuExpanded = false
                            deleteOpen = true
                        },
                    )
                }
            }
        }
        state.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editorOpen) {
        TranscriptEditorDialog(
            initialParagraphs = state.paragraphs,
            onDismiss = { editorOpen = false },
            onSave = { drafts ->
                editorOpen = false
                viewModel.saveRevision(drafts)
                onModeChange(TranscriptViewMode.READING)
            },
        )
    }

    if (historyOpen) {
        TranscriptRevisionHistoryDialog(
            state = state,
            onDismiss = { historyOpen = false },
            onSelect = { revisionId ->
                viewModel.selectRevision(revisionId)
                historyOpen = false
                onModeChange(
                    if (revisionId == null) TranscriptViewMode.TIMELINE else TranscriptViewMode.READING,
                )
            },
            onDelete = viewModel::deleteRevision,
        )
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名转写版本") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(120) },
                    label = { Text("版本名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameVersion(renameValue)
                        renameOpen = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除转写版本") },
            text = {
                Text(
                    "将删除当前转写版本及其人工修订。若有 AI 总结仍引用该版本，Voica 会阻止删除，不会连带删除总结。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val transcriptionId = state.transcriptionId
                        deleteOpen = false
                        if (transcriptionId != null) {
                            viewModel.deleteVersion(transcriptionId) {
                                onVersionDeleted()
                            }
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun TranscriptReadingParagraphCard(
    paragraph: TranscriptReadingParagraph,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = if (paragraph.isUserModified) 1.dp else 0.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            paragraph.speakerDisplayName?.let { speaker ->
                Text(
                    speaker,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SelectionContainer {
                Text(
                    paragraph.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (paragraph.isUserModified) {
                Text(
                    "已人工整理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class EditableTranscriptParagraph(
    val localId: String,
    val field: TextFieldValue,
    val draft: RevisionParagraphDraft,
)

@Composable
fun TranscriptEditorDialog(
    initialParagraphs: List<TranscriptReadingParagraph>,
    onDismiss: () -> Unit,
    onSave: (List<RevisionParagraphDraft>) -> Unit,
) {
    var items by remember(initialParagraphs) {
        mutableStateOf(
            initialParagraphs.map { paragraph ->
                EditableTranscriptParagraph(
                    localId = paragraph.stableId,
                    field = TextFieldValue(paragraph.text),
                    draft =
                        RevisionParagraphDraft(
                            text = paragraph.text,
                            sourceAnchorRefs = paragraph.sourceAnchorRefs,
                            anchorStartSampleIndex = paragraph.anchorStartSampleIndex,
                            anchorEndSampleIndexExclusive = paragraph.anchorEndSampleIndexExclusive,
                            speakerId = paragraph.speakerId,
                            timingQuality = paragraph.timingQuality,
                            isUserModified = paragraph.isUserModified,
                        ),
                )
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Text(
                        "编辑转写",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = items.isNotEmpty() && items.all { it.field.text.isNotBlank() },
                        onClick = {
                            val arranged =
                                TranscriptRevisionEditor.arrangeForReading(
                                    items.map { it.draft.copy(text = it.field.text.trim()) },
                                )
                            items =
                                arranged.mapIndexed { index, draft ->
                                    EditableTranscriptParagraph(
                                        localId =
                                            items.getOrNull(index)?.localId
                                                ?: UUID.randomUUID().toString(),
                                        field = TextFieldValue(draft.text),
                                        draft = draft,
                                    )
                                }
                        },
                    ) {
                        Text("整理段落")
                    }
                    Button(
                        enabled = items.isNotEmpty() && items.all { it.field.text.isNotBlank() },
                        onClick = {
                            onSave(
                                items.map { item ->
                                    item.draft.copy(text = item.field.text.trim())
                                },
                            )
                        },
                    ) {
                        Text("保存")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 10.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.localId },
                    ) { index, item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 10.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "段落 " + (index + 1),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = item.field,
                                    onValueChange = { next ->
                                        val changed = next.text != item.draft.text
                                        val nextTiming =
                                            if (!changed) {
                                                item.draft.timingQuality
                                            } else if (
                                                item.draft.timingQuality ==
                                                    RevisionTimingQuality.APPROXIMATE
                                            ) {
                                                RevisionTimingQuality.APPROXIMATE
                                            } else {
                                                RevisionTimingQuality.ANCHORED
                                            }
                                        items =
                                            items.toMutableList().also { mutable ->
                                                mutable[index] =
                                                    item.copy(
                                                        field = next,
                                                        draft =
                                                            item.draft.copy(
                                                                text = next.text,
                                                                timingQuality = nextTiming,
                                                                isUserModified =
                                                                    item.draft.isUserModified ||
                                                                        changed,
                                                            ),
                                                    )
                                            }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(
                                        enabled =
                                            index > 0 &&
                                                items[index - 1].field.text.isNotBlank() &&
                                                item.field.text.isNotBlank(),
                                        onClick = {
                                            val drafts =
                                                items.map { it.draft.copy(text = it.field.text.trim()) }
                                            val merged =
                                                TranscriptRevisionEditor.mergeWithPrevious(
                                                    drafts,
                                                    index,
                                                )
                                            items =
                                                rebuildEditableAfterMerge(
                                                    previous = items,
                                                    drafts = merged,
                                                    changedIndex = index - 1,
                                                )
                                        },
                                    ) {
                                        Text("合并上段")
                                    }
                                    TextButton(
                                        enabled =
                                            index < items.lastIndex &&
                                                item.field.text.isNotBlank() &&
                                                items[index + 1].field.text.isNotBlank(),
                                        onClick = {
                                            val drafts =
                                                items.map { it.draft.copy(text = it.field.text.trim()) }
                                            val merged =
                                                TranscriptRevisionEditor.mergeWithNext(
                                                    drafts,
                                                    index,
                                                )
                                            items =
                                                rebuildEditableAfterMerge(
                                                    previous = items,
                                                    drafts = merged,
                                                    changedIndex = index,
                                                )
                                        },
                                    ) {
                                        Text("合并下段")
                                    }
                                    TextButton(
                                        enabled =
                                            item.field.selection.collapsed &&
                                                item.field.selection.start in
                                                    1 until item.field.text.length,
                                        onClick = {
                                            val drafts =
                                                items.map { it.draft.copy(text = it.field.text.trim()) }
                                            val split =
                                                TranscriptRevisionEditor.splitAt(
                                                    paragraphs = drafts,
                                                    index = index,
                                                    textOffset = item.field.selection.start,
                                                    exactSplitSampleIndex = null,
                                                )
                                            items =
                                                rebuildEditableAfterSplit(
                                                    previous = items,
                                                    drafts = split,
                                                    splitIndex = index,
                                                )
                                        },
                                    ) {
                                        Text("从光标拆分")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptRevisionHistoryDialog(
    state: TranscriptContentState,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修订历史") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "original") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (state.currentRevisionId == null) {
                                    "✓ 模型原文"
                                } else {
                                    "模型原文"
                                },
                            )
                            Text(
                                "不可修改的识别结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onSelect(null) }) {
                            Text("查看")
                        }
                    }
                }
                itemsIndexed(
                    items = state.revisions,
                    key = { _, revision -> revision.id },
                ) { _, revision ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (if (state.currentRevisionId == revision.id) "✓ " else "") +
                                    "修订 " +
                                    revision.revisionNumber,
                            )
                            Text(
                                DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                ).format(Date(revision.createdAtMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onSelect(revision.id) }) {
                            Text("查看")
                        }
                        TextButton(onClick = { onDelete(revision.id) }) {
                            Text("删除")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun rebuildEditableAfterMerge(
    previous: List<EditableTranscriptParagraph>,
    drafts: List<RevisionParagraphDraft>,
    changedIndex: Int,
): List<EditableTranscriptParagraph> =
    drafts.mapIndexed { index, draft ->
        val old =
            when {
                index < changedIndex -> previous[index]
                index == changedIndex -> previous[changedIndex]
                else -> previous[index + 1]
            }
        EditableTranscriptParagraph(
            localId = old.localId,
            field = TextFieldValue(draft.text),
            draft = draft,
        )
    }

private fun rebuildEditableAfterSplit(
    previous: List<EditableTranscriptParagraph>,
    drafts: List<RevisionParagraphDraft>,
    splitIndex: Int,
): List<EditableTranscriptParagraph> =
    drafts.mapIndexed { index, draft ->
        val localId =
            when {
                index < splitIndex -> previous[index].localId
                index == splitIndex -> previous[splitIndex].localId
                index == splitIndex + 1 -> UUID.randomUUID().toString()
                else -> previous[index - 1].localId
            }
        EditableTranscriptParagraph(
            localId = localId,
            field = TextFieldValue(draft.text),
            draft = draft,
        )
    }
