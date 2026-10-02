package io.github.ioannes78.voica.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("文本大模型 Provider", style = MaterialTheme.typography.titleLarge)
            Text(
                "用于 Stage 11 AI 智能总结。默认只发送转写文本与必要的说话人/证据引用，不上传原始录音。",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.profiles.isNotEmpty()) {
                Text("已保存配置", style = MaterialTheme.typography.titleSmall)
                state.profiles.forEach { profile ->
                    OutlinedButton(
                        onClick = { viewModel.selectProfile(profile.providerProfileId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val defaultSuffix =
                            if (profile.providerProfileId == state.defaultProfileId) {
                                " · 默认"
                            } else {
                                ""
                            }
                        Text(profile.displayName + defaultSuffix)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::newProfile) {
                    Text("新增 Provider")
                }
                if (state.persisted &&
                    state.selectedProfileId != state.defaultProfileId
                ) {
                    OutlinedButton(
                        onClick = viewModel::setDefault,
                        enabled = !state.busy,
                    ) {
                        Text("设为默认")
                    }
                }
            }

            HorizontalDivider()

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
                    "Vertex AI 需要 Google Cloud OAuth（project/location/访问令牌）。Stage 11 不在设备端保存服务账号私钥，因此当前仅冻结接口契约。",
                    style = MaterialTheme.typography.bodySmall,
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
                    Text("显示本次输入的 API Key")
                }
            }

            OutlinedTextField(
                value = state.defaultModel,
                onValueChange = viewModel::setModel,
                label = { Text("模型 ID（支持手动填写）") },
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
                    Text(
                        "从 " + state.discoveredModels.size + " 个模型中选择",
                    )
                }
                Text(
                    "模型较多时可搜索名称或 ID；请选择支持文本对话的模型。",
                    style = MaterialTheme.typography.bodySmall,
                )
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

            OutlinedTextField(
                value = state.manualContextWindowTokens,
                onValueChange = viewModel::setManualContextWindow,
                label = { Text("上下文窗口（可选，tokens）") },
                supportingText = {
                    Text("Provider 未返回上下文长度时可手动填写；留空使用保守估算。")
                },
                singleLine = true,
                enabled = !state.busy && !state.stage11Unsupported,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.busy && !state.stage11Unsupported,
                ) {
                    Text(if (state.busy) "处理中…" else "保存")
                }
                OutlinedButton(
                    onClick = viewModel::discoverModels,
                    enabled = !state.busy && state.persisted && !state.stage11Unsupported,
                ) {
                    Text("获取模型")
                }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.busy && state.persisted && !state.stage11Unsupported,
                ) {
                    Text("连接测试")
                }
            }

            if (state.persisted) {
                TextButton(
                    onClick = viewModel::deleteSelected,
                    enabled = !state.busy,
                ) {
                    Text("删除此 Provider 配置")
                }
            }

            state.host?.let {
                Text("当前目标：" + it, style = MaterialTheme.typography.bodySmall)
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
