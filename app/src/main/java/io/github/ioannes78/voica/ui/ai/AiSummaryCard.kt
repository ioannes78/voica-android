package io.github.ioannes78.voica.ui.ai

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.ai.AiSummarySectionType
import io.github.ioannes78.voica.ai.SummaryTemplateCatalog
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryStateValue
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSummaryCard(
    transcriptionId: String,
    recordingName: String?,
    viewModel: AiSummaryViewModel,
    onOpenSettings: () -> Unit,
    onSeekEvidence: (Long) -> Unit,
) {
    val runState by viewModel.runState.collectAsState()
    val history by viewModel.history.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val generationModels by viewModel.generationModels.collectAsState()
    val customTemplates by viewModel.customTemplates.collectAsState()
    val notice by viewModel.notice.collectAsState()

    var moreExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var generationSheetOpen by remember { mutableStateOf(false) }
    var templateEditorOpen by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    var generationProviderExpanded by remember { mutableStateOf(false) }
    var generationModelExpanded by remember { mutableStateOf(false) }
    var selectedGenerationProviderId by remember { mutableStateOf<String?>(null) }
    var selectedGenerationModel by remember { mutableStateOf("") }
    var selectedPresetId by remember { mutableStateOf(SummaryTemplateCatalog.GENERIC) }
    var customName by remember { mutableStateOf("") }
    var customFocus by remember { mutableStateOf("") }
    var customInstruction by remember { mutableStateOf("") }
    var customSections by remember {
        mutableStateOf(
            setOf(
                AiSummarySectionType.SUMMARY,
                AiSummarySectionType.KEY_POINT,
                AiSummarySectionType.FOLLOW_UP,
            ),
        )
    }

    LaunchedEffect(transcriptionId) {
        viewModel.bind(transcriptionId)
    }
    LaunchedEffect(Unit) {
        viewModel.refreshProvider()
    }
    LaunchedEffect(generationSheetOpen, providers, provider?.providerProfileId) {
        if (!generationSheetOpen) return@LaunchedEffect
        val selectedProfile =
            provider
                ?: providers.firstOrNull {
                    it.providerProfileId == selectedGenerationProviderId
                }
                ?: providers.firstOrNull()
        if (selectedProfile != null) {
            selectedGenerationProviderId = selectedProfile.providerProfileId
            selectedGenerationModel = selectedProfile.model
            viewModel.loadGenerationModels(selectedProfile.providerProfileId)
        }
    }

    LaunchedEffect(generationModels, selectedGenerationProviderId) {
        if (!generationSheetOpen) return@LaunchedEffect
        if (
            selectedGenerationModel.isBlank() ||
            generationModels.none { it.id == selectedGenerationModel }
        ) {
            val fallback =
                providers.firstOrNull {
                    it.providerProfileId == selectedGenerationProviderId
                }?.model
            selectedGenerationModel =
                fallback?.takeIf { it.isNotBlank() }
                    ?: generationModels.firstOrNull()?.id.orEmpty()
        }
    }

    val running =
        (runState as? AiSummaryRunState.Running)
            ?.takeIf { it.transcriptionId == transcriptionId }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    selected?.result?.title
                        ?: selected?.entity?.displayText?.lineSequence()?.firstOrNull()
                        ?: "AI 总结",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                val selectedEntity = selected?.entity
                val providerModelLabel =
                    if (selectedEntity != null) {
                        selectedEntity.providerNameSnapshot +
                            " · " +
                            selectedEntity.model.ifBlank { "未选择模型" }
                    } else {
                        provider?.let {
                            it.displayName + " · " + it.model.ifBlank { "未选择模型" }
                        }
                    }
                providerModelLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (history.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { historyExpanded = true }) {
                        Text("版本 " + history.size)
                    }
                    DropdownMenu(
                        expanded = historyExpanded,
                        onDismissRequest = { historyExpanded = false },
                    ) {
                        history.forEach { summary ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        historyLabel(
                                            summary = summary,
                                            selected = selected?.entity?.id == summary.id,
                                        ),
                                    )
                                },
                                onClick = {
                                    historyExpanded = false
                                    viewModel.selectSummary(summary.id)
                                },
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "AI 总结操作",
                    )
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("生成新总结") },
                        onClick = {
                            moreExpanded = false
                            generationSheetOpen = true
                        },
                        enabled = running == null,
                    )
                    DropdownMenuItem(
                        text = { Text("新建自定义模板") },
                        onClick = {
                            moreExpanded = false
                            templateEditorOpen = true
                        },
                        enabled = running == null,
                    )
                    DropdownMenuItem(
                        text = { Text("文本模型设置") },
                        onClick = {
                            moreExpanded = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }

        if (provider == null) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "尚未配置文本大模型。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSettings) {
                    Text("前往 AI 设置")
                }
            }
        }

        if (running != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    progressText(running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = viewModel::cancel) {
                    Text("取消生成")
                }
            }
        }

        val document = selected
        if (document == null && running == null) {
            if (providers.isNotEmpty()) {
                Button(
                    onClick = { generationSheetOpen = true },
                    enabled = providers.any { it.model.isNotBlank() },
                ) {
                    Text("生成 AI 总结")
                }
            }
        } else if (document != null) {
            Text(
                summaryStatusLabel(document.entity.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                document.result != null -> {
                    val result = checkNotNull(document.result)
                    if (result.overview.isNotBlank()) {
                        Text(
                            result.overview,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    result.sections.forEach { section ->
                        if (section.items.isNotEmpty()) {
                            Text(
                                section.label,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            section.items.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("• " + item.text)
                                    if (item.evidenceRefs.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            item.evidenceRefs.forEach { ref ->
                                                val evidence = document.evidenceByRef[ref]
                                                if (evidence != null) {
                                                    TextButton(
                                                        onClick = {
                                                            onSeekEvidence(
                                                                evidence.startSampleIndex,
                                                            )
                                                        },
                                                    ) {
                                                        Text(
                                                            formatEvidenceTime(
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
                            }
                        }
                    }
                }

                !document.entity.displayText.isNullOrBlank() -> {
                    Text(document.entity.displayText!!)
                }

                document.entity.status == AiSummaryStateValue.INTERRUPTED -> {
                    Text("上次生成已中断，可继续生成。")
                }

                !document.entity.sanitizedErrorMessage.isNullOrBlank() -> {
                    Text(
                        document.entity.sanitizedErrorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (document.entity.status == AiSummaryStateValue.INTERRUPTED) {
                    Button(
                        onClick = { viewModel.resumeSelected() },
                        enabled = running == null,
                    ) {
                        Text("继续生成")
                    }
                }
                if (document.entity.status != AiSummaryStateValue.CREATED &&
                    document.entity.status !in AiSummaryStateValue.ACTIVE
                ) {
                    TextButton(
                        onClick = { viewModel.regenerateSelected() },
                        enabled =
                            running == null &&
                                document.entity.model.isNotBlank(),
                    ) {
                        Text("按原模板重新生成")
                    }
                }
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (generationSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { generationSheetOpen = false },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "生成 AI 总结",
                    style = MaterialTheme.typography.headlineSmall,
                )
                val selectedGenerationProvider =
                    providers.firstOrNull {
                        it.providerProfileId == selectedGenerationProviderId
                    }

                Text(
                    "AI 服务",
                    style = MaterialTheme.typography.titleSmall,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { generationProviderExpanded = true },
                        enabled = providers.isNotEmpty(),
                    ) {
                        Text(selectedGenerationProvider?.displayName ?: "选择 AI 服务")
                    }
                    DropdownMenu(
                        expanded = generationProviderExpanded,
                        onDismissRequest = { generationProviderExpanded = false },
                    ) {
                        providers.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.displayName +
                                            " · " +
                                            option.model.ifBlank { "未选择模型" },
                                    )
                                },
                                onClick = {
                                    generationProviderExpanded = false
                                    selectedGenerationProviderId = option.providerProfileId
                                    selectedGenerationModel = option.model
                                    viewModel.loadGenerationModels(option.providerProfileId)
                                },
                            )
                        }
                    }
                }

                Text(
                    "模型",
                    style = MaterialTheme.typography.titleSmall,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { generationModelExpanded = true },
                        enabled =
                            selectedGenerationProvider != null &&
                                generationModels.isNotEmpty(),
                    ) {
                        Text(
                            selectedGenerationModel.ifBlank {
                                selectedGenerationProvider?.model ?: "选择模型"
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = generationModelExpanded,
                        onDismissRequest = { generationModelExpanded = false },
                    ) {
                        generationModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName.ifBlank { model.id }) },
                                onClick = {
                                    generationModelExpanded = false
                                    selectedGenerationModel = model.id
                                },
                            )
                        }
                    }
                }

                selectedGenerationProvider?.let { option ->
                    val compatibilityText =
                        if (selectedGenerationModel == option.model) {
                            option.structuredCompatibilityLabel
                        } else {
                            "本次模型未单独验证，将使用兼容策略"
                        }
                    compatibilityText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = {
                        generationSheetOpen = false
                        viewModel.generateSmart(
                            providerProfileId = selectedGenerationProviderId,
                            model = selectedGenerationModel,
                        )
                    },
                    enabled =
                        selectedGenerationProviderId != null &&
                            selectedGenerationModel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("智能总结")
                }

                HorizontalDivider()
                Text(
                    "预设模板",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { presetExpanded = true },
                            enabled =
                            selectedGenerationProviderId != null &&
                                selectedGenerationModel.isNotBlank(),
                        ) {
                            val template = SummaryTemplateCatalog.find(selectedPresetId)
                            Text(template?.name ?: "选择模板")
                        }
                        DropdownMenu(
                            expanded = presetExpanded,
                            onDismissRequest = { presetExpanded = false },
                        ) {
                            SummaryTemplateCatalog.all().forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name) },
                                    onClick = {
                                        selectedPresetId =
                                            template.id ?: SummaryTemplateCatalog.GENERIC
                                        presetExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            generationSheetOpen = false
                            viewModel.generatePreset(
                                presetId = selectedPresetId,
                                providerProfileId = selectedGenerationProviderId,
                                model = selectedGenerationModel,
                            )
                        },
                        enabled =
                            selectedGenerationProviderId != null &&
                                selectedGenerationModel.isNotBlank(),
                    ) {
                        Text("生成")
                    }
                }

                if (customTemplates.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "自定义模板",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    customTemplates.forEach { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                template.name,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    generationSheetOpen = false
                                    viewModel.generateCustom(
                                        templateId = template.id,
                                        providerProfileId = selectedGenerationProviderId,
                                        model = selectedGenerationModel,
                                    )
                                },
                            ) {
                                Text("生成")
                            }
                            TextButton(
                                onClick = { viewModel.deleteCustomTemplate(template.id) },
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        generationSheetOpen = false
                        templateEditorOpen = true
                    },
                ) {
                    Text("新建自定义模板")
                }

                Text(
                    "默认仅发送当前转写文本和必要的说话人/证据引用，不上传原始录音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    if (templateEditorOpen) {
        ModalBottomSheet(
            onDismissRequest = { templateEditorOpen = false },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "新建自定义模板",
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it.take(120) },
                    label = { Text("模板名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customFocus,
                    onValueChange = { customFocus = it.take(2_000) },
                    label = { Text("关注重点") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customInstruction,
                    onValueChange = { customInstruction = it.take(4_000) },
                    label = { Text("输出要求") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "输出章节",
                    style = MaterialTheme.typography.titleSmall,
                )
                AiSummarySectionType.entries.forEach { section ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = section in customSections,
                            onCheckedChange = { checked ->
                                customSections =
                                    if (checked) {
                                        customSections + section
                                    } else {
                                        customSections - section
                                    }
                            },
                        )
                        Text(sectionLabel(section))
                    }
                }
                Button(
                    onClick = {
                        viewModel.saveCustomTemplate(
                            name = customName,
                            sections = customSections,
                            focus = customFocus,
                            instruction = customInstruction,
                        )
                        templateEditorOpen = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存模板")
                }
                Text(
                    "自定义要求不会覆盖系统的隐私、证据和防提示注入规则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

private fun historyLabel(
    summary: AiSummaryEntity,
    selected: Boolean,
): String {
    val created =
        DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
        ).format(Date(summary.createdAtMs))
    return (if (selected) "✓ " else "") +
        created + " · " +
        summaryStatusLabel(summary.status) + " · " +
        summary.providerNameSnapshot + " · " +
        summary.model
}

private fun formatEvidenceTime(sampleIndex: Long): String {
    val totalSeconds = sampleIndex / 16_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun progressText(state: AiSummaryRunState.Running): String =
    when (state.phase) {
        AiSummaryEnginePhase.PREPARING -> "正在准备转写与模型配置…"
        AiSummaryEnginePhase.ANALYZING -> "正在分析内容类型与上下文容量…"
        AiSummaryEnginePhase.MAPPING ->
            if (state.totalUnits > 0) {
                "正在分段总结 " + state.completedUnits + "/" + state.totalUnits + "…"
            } else {
                "正在分段总结…"
            }
        AiSummaryEnginePhase.REDUCING ->
            if (state.totalUnits > 0) {
                "正在合并总结 " + state.completedUnits + "/" + state.totalUnits + "…"
            } else {
                "正在合并总结…"
            }
        AiSummaryEnginePhase.VALIDATING -> "正在校验结构与证据引用…"
    }

private fun summaryStatusLabel(status: String): String =
    when (status) {
        AiSummaryStateValue.CREATED -> "等待生成"
        AiSummaryStateValue.PREPARING -> "准备中"
        AiSummaryStateValue.ANALYZING -> "内容分析"
        AiSummaryStateValue.PLANNING -> "规划中"
        AiSummaryStateValue.MAPPING -> "分段总结"
        AiSummaryStateValue.REDUCING -> "合并总结"
        AiSummaryStateValue.VALIDATING -> "校验中"
        AiSummaryStateValue.COMPLETED -> "已完成"
        AiSummaryStateValue.FAILED -> "生成失败"
        AiSummaryStateValue.CANCELLED -> "已取消"
        AiSummaryStateValue.INTERRUPTED -> "已中断，可继续"
        else -> status
    }

private fun sectionLabel(section: AiSummarySectionType): String =
    when (section) {
        AiSummarySectionType.SUMMARY -> "摘要"
        AiSummarySectionType.KEY_POINT -> "要点"
        AiSummarySectionType.DECISION -> "决策"
        AiSummarySectionType.ACTION_ITEM -> "待办"
        AiSummarySectionType.RISK -> "风险"
        AiSummarySectionType.QUESTION -> "问题"
        AiSummarySectionType.FACT -> "明确陈述"
        AiSummarySectionType.QA -> "问答"
        AiSummarySectionType.KNOWLEDGE_POINT -> "知识点"
        AiSummarySectionType.FOLLOW_UP -> "后续事项"
        AiSummarySectionType.CUSTOM -> "自定义章节"
    }
