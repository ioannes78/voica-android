package io.github.ioannes78.voica.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ioannes78.voica.ai.AiSummaryRevisionDocument
import io.github.ioannes78.voica.ai.AiSummaryRevisionEditor
import io.github.ioannes78.voica.ai.AiSummaryRevisionProvenance
import io.github.ioannes78.voica.ai.AiSummarySectionType
import io.github.ioannes78.voica.database.AiSummaryEvidenceEntity
import java.text.DateFormat
import java.util.Date

@Composable
fun AiSummaryRevisionBody(
    document: AiSummaryRevisionDocument,
    evidenceByRef: Map<String, AiSummaryEvidenceEntity>,
    onSeekEvidence: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (document.overview.isNotBlank()) {
            Text(
                document.overview,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        document.sections.forEach { section ->
            if (section.items.isNotEmpty()) {
                Text(
                    section.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                section.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("• " + item.text)
                        when (item.provenance) {
                            AiSummaryRevisionProvenance.AI_ORIGINAL -> {
                                if (item.sourceEvidenceRefs.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        item.sourceEvidenceRefs.forEach { ref ->
                                            val evidence = evidenceByRef[ref]
                                            if (evidence != null) {
                                                TextButton(
                                                    onClick = {
                                                        onSeekEvidence(
                                                            evidence.startSampleIndex,
                                                        )
                                                    },
                                                ) {
                                                    Text(
                                                        formatRevisionEvidenceTime(
                                                            evidence.startSampleIndex,
                                                        ),
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .labelSmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            AiSummaryRevisionProvenance.USER_EDITED -> {
                                Text(
                                    "人工修改",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            AiSummaryRevisionProvenance.USER_ADDED -> {
                                Text(
                                    "人工新增",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiSummaryEditorDialog(
    initial: AiSummaryRevisionDocument,
    onDismiss: () -> Unit,
    onSave: (AiSummaryRevisionDocument) -> Unit,
) {
    var document by remember(initial) { mutableStateOf(initial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                        "编辑总结",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = isValidRevisionDocument(document),
                        onClick = { onSave(document) },
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
                    item(key = "title") {
                        OutlinedTextField(
                            value = document.title,
                            onValueChange = { document = document.copy(title = it.take(300)) },
                            label = { Text("标题") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "overview") {
                        OutlinedTextField(
                            value = document.overview,
                            onValueChange = { document = document.copy(overview = it) },
                            label = { Text("概览") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                    itemsIndexed(
                        items = document.sections,
                        key = { _, section -> section.id },
                    ) { sectionIndex, section ->
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
                                OutlinedTextField(
                                    value = section.label,
                                    onValueChange = { value ->
                                        document =
                                            document.copy(
                                                sections =
                                                    document.sections.map { candidate ->
                                                        if (candidate.id == section.id) {
                                                            candidate.copy(label = value.take(120))
                                                        } else {
                                                            candidate
                                                        }
                                                    },
                                            )
                                    },
                                    label = { Text("章节标题") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(
                                        enabled = sectionIndex > 0,
                                        onClick = {
                                            document =
                                                AiSummaryRevisionEditor.moveSection(
                                                    document,
                                                    section.id,
                                                    -1,
                                                )
                                        },
                                    ) {
                                        Text("上移")
                                    }
                                    TextButton(
                                        enabled = sectionIndex < document.sections.lastIndex,
                                        onClick = {
                                            document =
                                                AiSummaryRevisionEditor.moveSection(
                                                    document,
                                                    section.id,
                                                    1,
                                                )
                                        },
                                    ) {
                                        Text("下移")
                                    }
                                    TextButton(
                                        onClick = {
                                            document =
                                                AiSummaryRevisionEditor.deleteSection(
                                                    document,
                                                    section.id,
                                                )
                                        },
                                    ) {
                                        Text("删除章节")
                                    }
                                }
                                section.items.forEach { item ->
                                    OutlinedTextField(
                                        value = item.text,
                                        onValueChange = { value ->
                                            document =
                                                document.copy(
                                                    sections =
                                                        document.sections.map { candidate ->
                                                            if (candidate.id != section.id) {
                                                                candidate
                                                            } else {
                                                                candidate.copy(
                                                                    items =
                                                                        candidate.items.map { current ->
                                                                            if (current.id != item.id) {
                                                                                current
                                                                            } else {
                                                                                current.copy(
                                                                                    text = value,
                                                                                    provenance =
                                                                                        if (
                                                                                            current.provenance ==
                                                                                                AiSummaryRevisionProvenance.USER_ADDED
                                                                                        ) {
                                                                                            AiSummaryRevisionProvenance.USER_ADDED
                                                                                        } else {
                                                                                            AiSummaryRevisionProvenance.USER_EDITED
                                                                                        },
                                                                                )
                                                                            }
                                                                        },
                                                                )
                                                            }
                                                        },
                                                )
                                        },
                                        label = {
                                            Text(
                                                when (item.provenance) {
                                                    AiSummaryRevisionProvenance.AI_ORIGINAL ->
                                                        "AI 原始内容"
                                                    AiSummaryRevisionProvenance.USER_EDITED ->
                                                        "人工修改"
                                                    AiSummaryRevisionProvenance.USER_ADDED ->
                                                        "人工新增"
                                                },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                    )
                                    TextButton(
                                        onClick = {
                                            document =
                                                AiSummaryRevisionEditor.deleteItem(
                                                    document,
                                                    item.id,
                                                )
                                        },
                                    ) {
                                        Text("删除此项")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        document =
                                            AiSummaryRevisionEditor.addItem(
                                                source = document,
                                                sectionId = section.id,
                                                text = "新内容",
                                            )
                                    },
                                ) {
                                    Text("+ 添加内容")
                                }
                            }
                        }
                    }
                    item(key = "add-section") {
                        OutlinedButton(
                            onClick = {
                                document =
                                    AiSummaryRevisionEditor.addSection(
                                        source = document,
                                        label = "新章节",
                                        type = AiSummarySectionType.CUSTOM,
                                    )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("+ 添加章节")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiSummaryRevisionHistoryDialog(
    state: AiSummaryContentState,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("总结修订历史") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "ai-original") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (state.currentRevisionId == null) {
                                    "✓ AI 原始结果"
                                } else {
                                    "AI 原始结果"
                                },
                            )
                            Text(
                                "保留模型、模板和 Evidence 原始血缘",
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

private fun isValidRevisionDocument(document: AiSummaryRevisionDocument): Boolean =
    document.sections.all { section ->
        section.label.isNotBlank() &&
            section.items.all { item -> item.text.isNotBlank() }
    }

private fun formatRevisionEvidenceTime(sampleIndex: Long): String {
    val totalSeconds = sampleIndex / 16_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}
