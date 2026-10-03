package io.github.ioannes78.voica.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.model.DownloadedModelVersionInfo

@Composable
fun StorageManagementContent(
    viewModel: StorageManagementViewModel,
    scope: LazyListScope,
) {
    val state by viewModel.state.collectAsState()
    var confirmCanonical by remember { mutableStateOf(false) }
    var confirmModel by remember { mutableStateOf<DownloadedModelVersionInfo?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    scope.item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Voica 托管空间",
                    style = MaterialTheme.typography.titleMedium,
                )
                val snapshot = state.snapshot
                if (snapshot == null) {
                    Text(
                        if (state.loading) "正在统计…" else "暂无存储统计",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "合计 " + formatStorageBytes(snapshot.totalManagedBytes),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    StorageMetricRow("原始录音", snapshot.originalAudioBytes)
                    StorageMetricRow(
                        "标准化音频",
                        snapshot.canonicalAudioBytes,
                        if (snapshot.reclaimableCanonicalBytes > 0L) {
                            "可安全回收 " +
                                formatStorageBytes(snapshot.reclaimableCanonicalBytes)
                        } else {
                            "当前没有可安全回收项"
                        },
                    )
                    StorageMetricRow("已下载模型", snapshot.downloadedModelsBytes)
                    StorageMetricRow(
                        "数据库及文本数据",
                        snapshot.databaseBytes,
                        "包含数据库、WAL 与 SHM 实际文件",
                    )
                    StorageMetricRow("临时文件", snapshot.temporaryBytes)
                }
            }
        }
    }

    scope.item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("安全清理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "只清理 Voica 托管的过期临时文件、导入暂存、分享缓存、Canonical .part 和模型 staging/package .part。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !state.operationRunning,
                    onClick = viewModel::cleanupTemporaryFiles,
                ) {
                    Text("清理临时文件")
                }

                HorizontalDivider()

                Text("标准化音频", style = MaterialTheme.typography.titleSmall)
                Text(
                    "只会删除无转写/说话人依赖、源文件仍完整且可重新生成的 Canonical WAV；原始录音不会删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled =
                        !state.operationRunning &&
                            (state.snapshot?.reclaimableCanonicalBytes ?: 0L) > 0L,
                    onClick = { confirmCanonical = true },
                ) {
                    Text(
                        "清理可重新生成的音频" +
                            state.snapshot?.reclaimableCanonicalBytes
                                ?.takeIf { it > 0L }
                                ?.let { " · " + formatStorageBytes(it) }
                                .orEmpty(),
                    )
                }
            }
        }
    }

    scope.item {
        Text(
            "已下载模型",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val models = state.snapshot?.downloadedModels.orEmpty()
    if (models.isEmpty()) {
        scope.item {
            Text(
                "没有可管理的下载模型。APK 内置模型不计入可删除文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    } else {
        models.forEach { model ->
            scope.item(
                key =
                    "storage-model-" + model.modelId + "-" +
                        model.revision + "-" + model.version,
            ) {
                DownloadedModelStorageRow(
                    model = model,
                    operationRunning = state.operationRunning,
                    onDelete = { confirmModel = model },
                )
            }
        }
    }

    state.message?.let { message ->
        scope.item {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    scope.item {
        OutlinedButton(
            enabled = !state.loading && !state.operationRunning,
            onClick = viewModel::refresh,
        ) {
            Text("重新统计")
        }
    }

    if (confirmCanonical) {
        AlertDialog(
            onDismissRequest = { confirmCanonical = false },
            title = { Text("清理标准化音频？") },
            text = {
                Text(
                    "只清理可由现有原始录音重新生成、且当前没有转写或说话人结果依赖的 Canonical WAV。原始录音、转写、总结不会删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCanonical = false
                        viewModel.cleanupCanonical()
                    },
                ) {
                    Text("清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCanonical = false }) {
                    Text("取消")
                }
            },
        )
    }

    confirmModel?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text("删除下载模型？") },
            text = {
                Text(
                    model.displayName + " " + model.version +
                        " 将从本机删除。需要时可重新下载；当前使用或回滚保护的版本不会提供删除操作。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmModel = null
                        viewModel.removeDownloadedModel(
                            modelId = model.modelId,
                            version = model.version,
                            revision = model.revision,
                        )
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmModel = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StorageMetricRow(
    label: String,
    bytes: Long,
    detail: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                formatStorageBytes(bytes),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        supportingContent =
            detail?.let { value ->
                {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
    )
}

@Composable
private fun DownloadedModelStorageRow(
    model: DownloadedModelVersionInfo,
    operationRunning: Boolean,
    onDelete: () -> Unit,
) {
    val protection =
        when {
            model.inUse -> "任务正在使用"
            model.active -> "当前版本"
            model.previous -> "回滚保护"
            else -> null
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ListItem(
                headlineContent = {
                    Text(model.displayName)
                },
                supportingContent = {
                    Text(
                        model.version + " · rev " + model.revision +
                            " · " + formatStorageBytes(model.sizeBytes) +
                            (protection?.let { " · " + it } ?: ""),
                    )
                },
                trailingContent = {
                    if (protection == null) {
                        TextButton(
                            enabled = !operationRunning,
                            onClick = onDelete,
                        ) {
                            Text("删除")
                        }
                    }
                },
            )
        }
    }
}
