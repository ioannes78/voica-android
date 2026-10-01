package io.github.ioannes78.voica.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ModelManagerCard(
    modelManager: ModelManager,
) {
    val scope = rememberCoroutineScope()
    val operations by modelManager.operations.collectAsState()
    var models by remember { mutableStateOf<List<ModelAvailability>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val catalog = modelManager.catalog()
        models =
            catalog.models.mapNotNull { descriptor ->
                modelManager.availability(descriptor.modelId)
            }
    }

    LaunchedEffect(modelManager, operations) {
        reload()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "本地模型",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "APK 仅内置 Silero VAD 基线；ASR、标点和高质量模型由受控模型通道提供。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = null
                    scope.launch {
                        try {
                            modelManager.checkForUpdates(force = true)
                            reload()
                            message = "模型更新检查完成"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message =
                                "模型更新检查失败：" +
                                    (error.message ?: error::class.java.simpleName)
                        } finally {
                            checking = false
                        }
                    }
                },
            ) {
                Text(if (checking) "检查中…" else "检查模型更新")
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            models.forEach { availability ->
                ModelAvailabilityRow(
                    availability = availability,
                    operation = operations[availability.descriptor.modelId],
                    onInstall = {
                        val descriptor = availability.descriptor
                        scope.launch {
                            try {
                                modelManager.install(
                                    modelId = descriptor.modelId,
                                    version = descriptor.version,
                                    revision = descriptor.revision,
                                )
                                reload()
                                message =
                                    descriptor.displayName +
                                        " 已下载并校验，等待运行库验证后启用"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                message =
                                    descriptor.displayName +
                                        " 下载失败：" +
                                        (error.message ?: error::class.java.simpleName)
                            }
                        }
                    },
                    onCancel = {
                        scope.launch {
                            modelManager.cancelInstall(
                                availability.descriptor.modelId,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelAvailabilityRow(
    availability: ModelAvailability,
    operation: ModelOperationStatus?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptor = availability.descriptor
    val downloadedCandidateReady =
        availability.availableRevision != null &&
            availability.installedRevision == availability.availableRevision &&
            availability.activeRevision != availability.availableRevision

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            descriptor.displayName,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            buildString {
                append("可用版本 ")
                append(availability.availableVersion ?: descriptor.version)
                append(" · 当前 ")
                append(availability.activeVersion ?: "未启用")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            modelStateText(availability.state),
            style = MaterialTheme.typography.bodySmall,
        )

        if (downloadedCandidateReady) {
            Text(
                "已下载并校验 · 等待运行库验证后启用",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (availability.updateAvailable) {
            Text(
                "有新版本可用",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (operation?.state == ModelState.DOWNLOADING) {
            val downloaded = operation.downloadedBytes
            val total = operation.totalBytes
            if (downloaded != null && total != null && total > 0L) {
                LinearProgressIndicator(
                    progress = { downloaded.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    (downloaded * 100L / total).toString() + "% · " +
                        formatBytes(downloaded) + " / " + formatBytes(total),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel) {
                Text("取消下载")
            }
        } else if (
            !downloadedCandidateReady &&
            descriptor.downloadUrl != null &&
            (availability.state == ModelState.NOT_INSTALLED ||
                availability.updateAvailable)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall) {
                    Text(if (availability.updateAvailable) "下载更新" else "下载")
                }
            }
        }
    }
}

private fun modelStateText(state: ModelState): String =
    when (state) {
        ModelState.NOT_INSTALLED -> "未安装"
        ModelState.DOWNLOADING -> "下载中"
        ModelState.VERIFYING -> "校验中"
        ModelState.INSTALLED -> "已安装"
        ModelState.LOAD_FAILED -> "加载失败"
        ModelState.CORRUPTED -> "文件损坏"
        ModelState.INCOMPATIBLE -> "与当前 App/运行库不兼容"
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            "%.1f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
        bytes >= 1024L ->
            "%.1f KB".format(bytes.toDouble() / 1024.0)
        else -> bytes.toString() + " B"
    }
