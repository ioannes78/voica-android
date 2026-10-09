package io.github.ioannes78.voica.ui.transcript

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

@Composable
fun TranscriptProductActionBar(
    recordingName: String,
    mode: TranscriptViewMode,
    state: TranscriptContentState,
    viewModel: TranscriptContentViewModel,
    speakerModeLabel: String,
    onModeChange: (TranscriptViewMode) -> Unit,
    onSpeakerModeClick: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textExport = remember(context) { ContentTextExportCoordinator(context) }
    var pendingTxt by remember { mutableStateOf<TextContentDocument?>(null) }
    var pendingMarkdown by remember { mutableStateOf<TextContentDocument?>(null) }
    val document =
        remember(recordingName, state.currentRevisionId, state.paragraphs) {
            TranscriptContentExportFormatter.build(
                recordingName = recordingName,
                versionName = null,
                paragraphs = state.paragraphs,
                includeSpeaker = true,
                includeTime = false,
            )
        }

    val txtLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(TextExportFormat.TXT.mimeType),
        ) { uri ->
            val pending = pendingTxt
            pendingTxt = null
            if (uri != null && pending != null) {
                scope.launch {
                    val result = textExport.exportToUri(pending, TextExportFormat.TXT, uri)
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
            val pending = pendingMarkdown
            pendingMarkdown = null
            if (uri != null && pending != null) {
                scope.launch {
                    val result = textExport.exportToUri(pending, TextExportFormat.MARKDOWN, uri)
                    Toast.makeText(
                        context,
                        if (result.exported) "Markdown 已导出" else result.error ?: "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTranscriptChip(
                label = "时间轴",
                selected = mode == TranscriptViewMode.TIMELINE,
                onClick = { onModeChange(TranscriptViewMode.TIMELINE) },
            )
            CompactTranscriptChip(
                label = "阅读",
                selected = mode == TranscriptViewMode.READING,
                onClick = { onModeChange(TranscriptViewMode.READING) },
            )
            CompactTranscriptChip(
                label = "说话人·$speakerModeLabel",
                selected = false,
                onClick = onSpeakerModeClick,
            )
            Button(
                modifier = Modifier.heightIn(min = 40.dp),
                enabled = state.paragraphs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                onClick = { editorOpen = true },
            ) {
                Text("编辑", maxLines = 1)
            }
            Box {
                IconButton(
                    modifier = Modifier.size(40.dp),
                    enabled = state.transcriptionId != null,
                    onClick = { menuExpanded = true },
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多转写操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制全文") },
                        enabled = document.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            val copied =
                                textExport.copyToClipboard(
                                    label = recordingName,
                                    text = document.plainText,
                                )
                            Toast.makeText(
                                context,
                                if (copied) "已复制全文" else "没有可复制的文本",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        enabled = document.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            scope.launch {
                                when (
                                    val outcome =
                                        textExport.prepareShare(
                                            document = document,
                                            format = TextExportFormat.TXT,
                                        )
                                ) {
                                    is TextShareOutcome.Ready -> context.startActivity(outcome.intent)
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
                        enabled = document.plainText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                scope.launch {
                                    val result = textExport.exportToDownloads(document, TextExportFormat.TXT)
                                    Toast.makeText(
                                        context,
                                        if (result.exported) "已导出到 Downloads/Voica" else result.error ?: "导出失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                pendingTxt = document
                                txtLauncher.launch(document.baseName + TextExportFormat.TXT.extension)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        enabled = document.markdownText.isNotBlank(),
                        onClick = {
                            menuExpanded = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                scope.launch {
                                    val result = textExport.exportToDownloads(document, TextExportFormat.MARKDOWN)
                                    Toast.makeText(
                                        context,
                                        if (result.exported) "已导出到 Downloads/Voica" else result.error ?: "导出失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                pendingMarkdown = document
                                markdownLauncher.launch(document.baseName + TextExportFormat.MARKDOWN.extension)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重新转写") },
                        onClick = {
                            menuExpanded = false
                            onRetranscribe()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复模型结果") },
                        enabled = state.currentRevisionId != null,
                        onClick = {
                            menuExpanded = false
                            viewModel.restoreModelOriginal()
                            onModeChange(TranscriptViewMode.READING)
                        },
                    )
                }
            }
        }
        if (state.currentRevisionId != null) {
            Text(
                "已编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
}

@Composable
private fun CompactTranscriptChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outline
    Surface(
        modifier =
            Modifier
                .heightIn(min = 40.dp)
                .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TranscriptContinuousReading(
    paragraphs: List<TranscriptReadingParagraph>,
    modifier: Modifier = Modifier,
) {
    val fullText = remember(paragraphs) {
        paragraphs
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        SelectionContainer {
            Text(
                text = fullText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
