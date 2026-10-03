package io.github.ioannes78.voica.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ai.ProviderPresetCatalog

@Composable
fun ProviderSettingsCard(
    viewModel: ProviderSettingsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf(state.profiles.isEmpty()) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var baselineProfileId by remember { mutableStateOf<String?>(null) }
    var baselineSignature by remember { mutableStateOf<String?>(null) }
    var discardConfirmOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.profiles.size) {
        if (state.profiles.isEmpty()) {
            editing = true
        }
    }

    LaunchedEffect(editing, state.selectedProfileId) {
        if (
            editing &&
            baselineProfileId != state.selectedProfileId
        ) {
            baselineProfileId = state.selectedProfileId
            baselineSignature = state.editSignature()
        }
    }

    LaunchedEffect(state.notice, state.busy) {
        if (
            editing &&
            !state.busy &&
            state.notice?.contains("已保存") == true
        ) {
            baselineSignature = state.editSignature()
        }
    }

    val dirty =
        editing &&
            baselineSignature != null &&
            state.editSignature() != baselineSignature

    fun requestEditorExit() {
        if (dirty) {
            discardConfirmOpen = true
        } else {
            editing = false
            viewModel.refresh()
        }
    }

    BackHandler(
        enabled = editing && state.profiles.isNotEmpty(),
    ) {
        requestEditorExit()
    }

    if (!editing) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "文本大模型 Provider",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "管理 AI 总结使用的模型服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        viewModel.newProfile()
                        editing = true
                    },
                ) {
                    Text("新增")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    state.profiles.forEachIndexed { index, profile ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    profile.displayName +
                                        if (profile.providerProfileId == state.defaultProfileId) {
                                            " · 默认"
                                        } else {
                                            ""
                                        },
                                )
                            },
                            supportingContent = {
                                Text(
                                    profile.defaultModel.ifBlank { "未选择模型" },
                                    maxLines = 1,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    viewModel.selectProfile(profile.providerProfileId)
                                    editing = true
                                },
                        )
                        if (index != state.profiles.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.profiles.isNotEmpty()) {
                TextButton(onClick = { requestEditorExit() }) {
                    Text("返回")
                }
            }
            Text(
                state.displayName.ifBlank { "Provider 配置" },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.persisted &&
                state.selectedProfileId != state.defaultProfileId
            ) {
                TextButton(
                    onClick = viewModel::setDefault,
                    enabled = !state.busy,
                ) {
                    Text("设为默认")
                }
            }
        }

        Box {
            OutlinedButton(
                onClick = { providerMenuExpanded = true },
                enabled = !state.busy,
            ) {
                Text(
                    state.selectedPreset?.displayName
                        ?: state.presetId,
                )
            }
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
            ) {
                ProviderPresetCatalog.builtIn.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.displayName) },
                        onClick = {
                            providerMenuExpanded = false
                            viewModel.setPreset(preset.presetId)
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::setDisplayName,
            label = { Text("配置名称") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::setBaseUrl,
            label = { Text("Base URL（HTTPS）") },
            singleLine = true,
            enabled = !state.busy && !state.stage11Unsupported,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.stage11Unsupported) {
            Text(
                "该 Provider 需要当前阶段未启用的 OAuth 配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::setApiKey,
                label = {
                    Text(
                        if (state.credentialConfigured) {
                            "API Key（已安全保存；留空保持不变）"
                        } else {
                            "API Key"
                        },
                    )
                },
                visualTransformation =
                    if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = showApiKey,
                    onCheckedChange = { showApiKey = it },
                )
                Text(
                    "显示本次输入",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedTextField(
            value = state.defaultModel,
            onValueChange = viewModel::setModel,
            label = { Text("模型 ID") },
            singleLine = true,
            enabled = !state.busy && !state.stage11Unsupported,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.discoveredModels.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    modelSearch = ""
                    modelPickerOpen = true
                },
                enabled = !state.busy,
            ) {
                Text("从 " + state.discoveredModels.size + " 个模型中选择")
            }
        }

        if (modelPickerOpen) {
            ModelPickerDialog(
                models = state.discoveredModels,
                query = modelSearch,
                onQueryChange = { modelSearch = it },
                onSelect = { modelId ->
                    viewModel.setModel(modelId)
                    modelPickerOpen = false
                },
                onDismiss = { modelPickerOpen = false },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = viewModel::save,
                enabled = !state.busy && !state.stage11Unsupported,
            ) {
                Text(if (state.busy) "处理中…" else "保存")
            }
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.busy && state.persisted && !state.stage11Unsupported,
            ) {
                Text("测试连接")
            }
            OutlinedButton(
                onClick = viewModel::discoverModels,
                enabled = !state.busy && state.persisted && !state.stage11Unsupported,
            ) {
                Text("获取模型")
            }
        }

        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(if (advancedExpanded) "收起高级设置" else "高级设置")
        }

        if (advancedExpanded) {
            OutlinedTextField(
                value = state.manualContextWindowTokens,
                onValueChange = viewModel::setManualContextWindow,
                label = { Text("上下文窗口（可选，tokens）") },
                singleLine = true,
                enabled = !state.busy && !state.stage11Unsupported,
                modifier = Modifier.fillMaxWidth(),
            )
            state.host?.let {
                Text(
                    "目标：" + it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.persisted) {
            TextButton(
                onClick = {
                    viewModel.deleteSelected()
                    editing = false
                },
                enabled = !state.busy,
            ) {
                Text(
                    "删除此 Provider 配置",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            "AI 总结默认只发送转写文本和必要的说话人/证据引用，不上传原始录音。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (discardConfirmOpen) {
        AlertDialog(
            onDismissRequest = { discardConfirmOpen = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前 Provider 配置有尚未保存的修改。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardConfirmOpen = false
                        editing = false
                        viewModel.refresh()
                    },
                ) {
                    Text("放弃")
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmOpen = false }) {
                    Text("继续编辑")
                }
            },
        )
    }
}

private fun ProviderEditorState.editSignature(): String =
    listOf(
        presetId,
        displayName,
        baseUrl,
        apiKeyInput,
        defaultModel,
        manualContextWindowTokens,
    ).joinToString("\u001F")

@Composable
private fun ModelPickerDialog(
    models: List<io.github.ioannes78.voica.ai.ProviderModel>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val needle = query.trim()
    val filtered =
        remember(models, needle) {
            if (needle.isBlank()) {
                models
            } else {
                models.filter { model ->
                    model.id.contains(needle, ignoreCase = true) ||
                        model.displayName.contains(needle, ignoreCase = true)
                }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文本模型") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("搜索模型名称或 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "显示 " + filtered.size + " / " + models.size,
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = filtered,
                        key = { model -> model.id },
                    ) { model ->
                        TextButton(
                            onClick = { onSelect(model.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(model.displayName)
                                if (model.id != model.displayName) {
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
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
