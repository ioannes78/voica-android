package io.github.ioannes78.voica.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
    val customTemplates by viewModel.customTemplates.collectAsState()
    val notice by viewModel.notice.collectAsState()

    var presetExpanded by remember { mutableStateOf(false) }
    var selectedPresetId by remember { mutableStateOf(SummaryTemplateCatalog.GENERIC) }
    var customExpanded by remember { mutableStateOf(false) }
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

    val running =
        (runState as? AiSummaryRunState.Running)
            ?.takeIf { it.transcriptionId == transcriptionId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("AI 智能总结", style = MaterialTheme.typography.titleLarge)
            recordingName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            if (provider == null) {
                Text(
                    "尚未配置文本大模型。请先到设置页添加 Provider、API Key 与模型。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenSettings) {
                    Text("前往文本模型设置")
                }
            } else {
                val p = provider!!
                Text(
                    "Provider：" + p.displayName +
                        " · 模型：" + p.model.ifBlank { "未选择" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "将发送：当前转写文本 + 必要的说话人/证据引用 → " +
                        p.host.ifBlank { "已配置 Provider" } +
                        "。不会上传原始录音。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (running != null) {
                Text(
                    progressText(running),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::cancel) {
                    Text("取消 AI 总结")
                }
            } else {
                Button(
                    onClick = { viewModel.generateSmart() },
                    enabled = provider?.model?.isNotBlank() == true,
                ) {
                    Text("智能总结")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { presetExpanded = true },
                            enabled = provider?.model?.isNotBlank() == true,
                        ) {
                            val template =
                                SummaryTemplateCatalog.find(selectedPresetId)
                            Text(template?.name ?: "选择预设模板")
                        }
                        DropdownMenu(
                            expanded = presetExpanded,
                            onDismissRequest = { presetExpanded = false },
                        ) {
                            SummaryTemplateCatalog.all().forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name) },
                                    onClick = {
                                        selectedPresetId = template.id ?: SummaryTemplateCatalog.GENERIC
                                        presetExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.generatePreset(selectedPresetId) },
                        enabled = provider?.model?.isNotBlank() == true,
                    ) {
                        Text("按预设生成")
                    }
                }
            }

            HorizontalDivider()
            Text("自定义模板", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { customExpanded = !customExpanded }) {
                Text(if (customExpanded) "收起模板编辑" else "新建自定义模板")
            }

            if (customExpanded) {
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
                    supportingText = {
                        Text("自定义要求不会覆盖系统的隐私、证据和防提示注入规则。")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("输出章节", style = MaterialTheme.typography.titleSmall)
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
                    },
                ) {
                    Text("保存自定义模板")
                }
            }

            customTemplates.forEach { template ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.generateCustom(template.id) },
                        enabled =
                            running == null &&
                                provider?.model?.isNotBlank() == true,
                    ) {
                        Text(template.name)
                    }
                    TextButton(
                        onClick = { viewModel.deleteCustomTemplate(template.id) },
                        enabled = running == null,
                    ) {
                        Text("删除")
                    }
                }
            }

            if (history.isNotEmpty()) {
                HorizontalDivider()
                Text("AI 总结历史", style = MaterialTheme.typography.titleMedium)
                history.forEach { summary ->
                    SummaryHistoryRow(
                        summary = summary,
                        selected = selected?.entity?.id == summary.id,
                        onSelect = { viewModel.selectSummary(summary.id) },
                    )
                }
            }

            selected?.let { document ->
                HorizontalDivider()
                Text(
                    document.result?.title
                        ?: document.entity.displayText?.lineSequence()?.firstOrNull()
                        ?: "AI 总结",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    summaryStatusLabel(document.entity.status),
                    style = MaterialTheme.typography.bodySmall,
                )

                when {
                    document.result != null -> {
                        val result = checkNotNull(document.result)
                        if (result.overview.isNotBlank()) {
                            Text(result.overview)
                        }
                        result.sections.forEach { section ->
                            if (section.items.isNotEmpty()) {
                                Text(
                                    section.label,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                section.items.forEach { item ->
                                    Text("• " + item.text)
                                    if (item.evidenceRefs.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(4.dp),
                                        ) {
                                            item.evidenceRefs.forEach { ref ->
                                                val evidence =
                                                    document.evidenceByRef[ref]
                                                if (evidence != null) {
                                                    TextButton(
                                                        onClick = {
                                                            onSeekEvidence(
                                                                evidence.startSampleIndex,
                                                            )
                                                        },
                                                    ) {
                                                        Text(ref + " · 跳转")
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
                        Text("上次生成因进程中断而停止；不会自动继续调用云端模型。")
                    }

                    !document.entity.sanitizedErrorMessage.isNullOrBlank() -> {
                        Text(document.entity.sanitizedErrorMessage!!)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        OutlinedButton(
                            onClick = { viewModel.regenerateSelected() },
                            enabled =
                                running == null &&
                                    provider?.model?.isNotBlank() == true,
                        ) {
                            Text("按原模板重新生成")
                        }
                    }
                }
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SummaryHistoryRow(
    summary: AiSummaryEntity,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val created =
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(Date(summary.createdAtMs))
        Text(
            created + " · " +
                summaryStatusLabel(summary.status) +
                " · " +
                summary.providerNameSnapshot +
                (if (selected) " · 当前查看" else ""),
        )
    }
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
