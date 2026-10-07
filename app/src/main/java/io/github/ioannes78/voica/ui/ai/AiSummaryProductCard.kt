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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.ContentTextExportCoordinator
import io.github.ioannes78.voica.TextContentDocument
import io.github.ioannes78.voica.TextExportFormat
import io.github.ioannes78.voica.TextShareOutcome
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.ai.SummaryTemplateCatalog
import io.github.ioannes78.voica.database.AiSummaryStateValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSummaryProductCard(
    transcriptionId: String,
    recordingName: String,
    viewModel: AiSummaryViewModel,
    contentViewModel: AiSummaryContentViewModel,
    onOpenSettings: () -> Unit,
    onSeekEvidence: (Long) -> Unit,
    showTransientHeader: Boolean = true,
) {
    val runState by viewModel.runState.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val candidateId by viewModel.candidateId.collectAsState()
    val stale by viewModel.stale.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val generationModels by viewModel.generationModels.collectAsState()
    val customTemplates by viewModel.customTemplates.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val contentState by contentViewModel.state.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var generationOpen by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf(false) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var selectedPresetId by remember { mutableStateOf(SummaryTemplateCatalog.GENERIC) }
    var ambiguousRetrySummaryId by remember(transcriptionId) { mutableStateOf<String?>(null) }
    var ambiguousRetryMessage by remember(transcriptionId) { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textExport = remember(context) { ContentTextExportCoordinator(context) }
    var pendingTxt by remember { mutableStateOf<TextContentDocument?>(null) }
    var pendingMarkdown by remember { mutableStateOf<TextContentDocument?>(null) }

    LaunchedEffect(transcriptionId) {
        viewModel.bind(transcriptionId)
    }
    LaunchedEffect(runState, transcriptionId) {
        when (val state = runState) {
            is AiSummaryRunState.Completed ->
                if (state.transcriptionId == transcriptionId) {
                    ambiguousRetrySummaryId = null
                    ambiguousRetryMessage = null
                    viewModel.acknowledgeTerminal(state.summaryId)
                }
            is AiSummaryRunState.Failed ->
                if (state.transcriptionId == transcriptionId) {
                    if (state.errorCode == "REMOTE_RESULT_UNKNOWN" && state.summaryId != null) {
                        ambiguousRetrySummaryId = state.summaryId
                        ambiguousRetryMessage = state.message
                    }
                    state.summaryId?.let(viewModel::acknowledgeTerminal)
                }
            is AiSummaryRunState.Running ->
                if (state.transcriptionId == transcriptionId) {
                    ambiguousRetrySummaryId = null
                    ambiguousRetryMessage = null
                }
            else -> Unit
        }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshProvider()
    }
    LaunchedEffect(selected?.entity?.id, selected?.result) {
        contentViewModel.bind(selected?.entity, selected?.result)
    }
    LaunchedEffect(generationOpen, providers, provider?.providerProfileId) {
        if (!generationOpen) return@LaunchedEffect
        val selectedProvider =
            providers.firstOrNull { it.providerProfileId == selectedProviderId }
                ?: provider
                ?: providers.firstOrNull()
        if (selectedProvider != null) {
            selectedProviderId = selectedProvider.providerProfileId
            selectedModel = selectedProvider.model
            viewModel.loadGenerationModels(selectedProvider.providerProfileId)
        }
    }
    LaunchedEffect(generationModels, selectedProviderId) {
        if (!generationOpen) return@LaunchedEffect
        if (selectedModel.isBlank() || generationModels.none { it.id == selectedModel }) {
            val configured =
                providers.firstOrNull { it.providerProfileId == selectedProviderId }?.model
            selectedModel =
                configured?.takeIf { it.isNotBlank() }
                    ?: generationModels.firstOrNull()?.id.orEmpty()
        }
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
                    providerName = selected?.entity?.providerNameSnapshot,
                    modelName = selected?.entity?.model,
                )
            }
    val running =
        (runState as? AiSummaryRunState.Running)
            ?.takeIf { it.transcriptionId == transcriptionId }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (stale && selected?.entity?.status == AiSummaryStateValue.COMPLETED) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "当前总结基于较早的转写内容",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        enabled = running == null,
                        onClick = { viewModel.regenerateSelected() },
                    ) {
                        Text("重新生成总结")
                    }
                }
            }
        }

        if (showTransientHeader) {
            candidateId?.let { newSummaryId ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "新的总结结果已生成",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { viewModel.selectSummary(newSummaryId) }) {
                            Text("查看")
                        }
                        Button(onClick = { viewModel.adoptSummaryResult(newSummaryId) }) {
                            Text("使用新结果")
                        }
                    }
                }
            }
        }

        val retrySummaryId = ambiguousRetrySummaryId
        if (showTransientHeader && retrySummaryId != null && running == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        ambiguousRetryMessage
                            ?: "上一次请求状态无法确认，需要手动重试。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "为避免重复生成或重复计费，Voica 不会自动重新发送该请求。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { viewModel.retryAmbiguous(retrySummaryId) }) {
                        Text("手动重试")
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
                val entity = selected?.entity
                val providerModel =
                    entity?.let { it.providerNameSnapshot + " · " + it.model }
                        ?: provider?.let { it.displayName + " · " + it.model }
                providerModel?.let {
                    Text(
                        it,
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
            Button(
                enabled = contentState.document != null && selected?.entity?.status == AiSummaryStateValue.COMPLETED,
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
                                        val result = textExport.prepareShare(document, TextExportFormat.TXT)
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
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("生成新总结") },
                        enabled = running == null,
                        onClick = {
                            menuExpanded = false
                            generationOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复 AI 原始结果") },
                        enabled = contentState.currentRevisionId != null,
                        onClick = {
                            menuExpanded = false
                            contentViewModel.restoreOriginal()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("AI 服务设置") },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }

        if (provider == null && selected == null) {
            Text("尚未配置 AI 服务。", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenSettings) { Text("前往 AI 设置") }
        }

        if (showTransientHeader && running != null) {
            Text(
                productProgressText(running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancel) { Text("取消生成") }
        }

        val current = selected
        if (current == null && running == null && providers.isNotEmpty()) {
            Button(
                enabled = providers.any { it.model.isNotBlank() },
                onClick = { generationOpen = true },
            ) {
                Text("生成总结")
            }
        } else if (current != null) {
            when {
                contentState.summaryId == current.entity.id && contentState.document != null ->
                    AiSummaryRevisionBody(
                        document = checkNotNull(contentState.document),
                        evidenceByRef = current.evidenceByRef,
                        onSeekEvidence = onSeekEvidence,
                    )

                !current.entity.displayText.isNullOrBlank() ->
                    Text(current.entity.displayText.orEmpty())

                current.entity.status == AiSummaryStateValue.INTERRUPTED -> {
                    Text("上次生成已中断，可继续生成。")
                    Button(
                        enabled = running == null,
                        onClick = { viewModel.resumeSelected() },
                    ) { Text("继续生成") }
                }

                !current.entity.sanitizedErrorMessage.isNullOrBlank() ->
                    Text(
                        current.entity.sanitizedErrorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
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

    if (generationOpen) {
        ModalBottomSheet(onDismissRequest = { generationOpen = false }) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("生成总结", style = MaterialTheme.typography.headlineSmall)
                val selectedProvider =
                    providers.firstOrNull { it.providerProfileId == selectedProviderId }

                Text("AI 服务", style = MaterialTheme.typography.titleSmall)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = providers.isNotEmpty(),
                        onClick = { providerMenuOpen = true },
                    ) {
                        Text(selectedProvider?.displayName ?: "选择 AI 服务")
                    }
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false },
                    ) {
                        providers.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName + " · " + option.model) },
                                onClick = {
                                    providerMenuOpen = false
                                    selectedProviderId = option.providerProfileId
                                    selectedModel = option.model
                                    viewModel.loadGenerationModels(option.providerProfileId)
                                },
                            )
                        }
                    }
                }

                Text("模型", style = MaterialTheme.typography.titleSmall)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedProvider != null && generationModels.isNotEmpty(),
                        onClick = { modelMenuOpen = true },
                    ) {
                        Text(selectedModel.ifBlank { selectedProvider?.model ?: "选择模型" })
                    }
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                    ) {
                        generationModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName.ifBlank { model.id }) },
                                onClick = {
                                    modelMenuOpen = false
                                    selectedModel = model.id
                                },
                            )
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedProviderId != null && selectedModel.isNotBlank(),
                    onClick = {
                        generationOpen = false
                        viewModel.generateSmart(selectedProviderId, selectedModel)
                    },
                ) { Text("智能总结") }

                HorizontalDivider()
                Text("预设模板", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { presetMenuOpen = true },
                        ) {
                            Text(SummaryTemplateCatalog.find(selectedPresetId)?.name ?: "选择模板")
                        }
                        DropdownMenu(
                            expanded = presetMenuOpen,
                            onDismissRequest = { presetMenuOpen = false },
                        ) {
                            SummaryTemplateCatalog.all().forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name) },
                                    onClick = {
                                        selectedPresetId = template.id ?: SummaryTemplateCatalog.GENERIC
                                        presetMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        enabled = selectedProviderId != null && selectedModel.isNotBlank(),
                        onClick = {
                            generationOpen = false
                            viewModel.generatePreset(
                                selectedPresetId,
                                selectedProviderId,
                                selectedModel,
                            )
                        },
                    ) { Text("生成") }
                }

                if (customTemplates.isNotEmpty()) {
                    HorizontalDivider()
                    Text("自定义模板", style = MaterialTheme.typography.titleSmall)
                    customTemplates.forEach { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(template.name, modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = selectedProviderId != null && selectedModel.isNotBlank(),
                                onClick = {
                                    generationOpen = false
                                    viewModel.generateCustom(
                                        template.id,
                                        selectedProviderId,
                                        selectedModel,
                                    )
                                },
                            ) { Text("生成") }
                        }
                    }
                }

                Text(
                    "发送的是生成开始时冻结的当前有效转写文本；不会因生成过程中切换内容而改变输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

private fun productProgressText(state: AiSummaryRunState.Running): String =
    when (state.phase) {
        AiSummaryEnginePhase.PREPARING -> "正在准备当前转写…"
        AiSummaryEnginePhase.ANALYZING -> "正在分析内容…"
        AiSummaryEnginePhase.MAPPING ->
            if (state.totalUnits > 0) {
                "正在总结 ${state.completedUnits}/${state.totalUnits}…"
            } else {
                "正在总结…"
            }
        AiSummaryEnginePhase.REDUCING ->
            if (state.totalUnits > 0) {
                "正在合并 ${state.completedUnits}/${state.totalUnits}…"
            } else {
                "正在合并…"
            }
        AiSummaryEnginePhase.VALIDATING -> "正在校验结果…"
    }
