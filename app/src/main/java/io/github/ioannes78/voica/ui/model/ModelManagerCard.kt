package io.github.ioannes78.voica.ui.model

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.DurableModelInstallController
import io.github.ioannes78.voica.ModelInstallJournalRecord
import io.github.ioannes78.voica.ModelInstallOrigin
import io.github.ioannes78.voica.ModelInstallPhase
import io.github.ioannes78.voica.ModelUpdateController
import io.github.ioannes78.voica.VoicaModelChannel
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelState
import io.github.ioannes78.voica.modelInstallRecordUserMessage
import io.github.ioannes78.voica.modelUserSafeErrorMessage
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ModelManagerCard(
    modelManager: ModelManager,
    modelUpdateController: ModelUpdateController,
) {
    val scope = rememberCoroutineScope()
    val application = LocalContext.current.applicationContext as Application
    val debugChannelEnabled = VoicaModelChannel.isDebuggable(application)
    val operations by modelManager.operations.collectAsState()
    val durableController = modelManager as? DurableModelInstallController
    val durableOperationsFlow =
        remember(modelManager) {
            durableController?.installOperations
                ?: MutableStateFlow<Map<String, ModelInstallJournalRecord>>(emptyMap())
        }
    val durableOperations by durableOperationsFlow.collectAsState()
    val updateSettings by modelUpdateController.settings.collectAsState()
    val updateState by modelUpdateController.state.collectAsState()
    var models by remember { mutableStateOf<List<ModelAvailability>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var updateSettingsExpanded by remember { mutableStateOf(false) }
    var debugExpanded by remember { mutableStateOf(false) }
    var debugManifestUrl by remember {
        mutableStateOf(
            VoicaModelChannel.configuredDebugManifestUrl(application).orEmpty(),
        )
    }

    suspend fun reload() {
        val catalog = modelManager.catalog()
        models =
            catalog.models.mapNotNull { descriptor ->
                modelManager.availability(descriptor.modelId)
            }
    }

    LaunchedEffect(modelManager, operations, durableOperations) {
        reload()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("本地模型", style = MaterialTheme.typography.titleMedium)
            Text(
                "APK 仅内置 Silero VAD 基线；ASR、标点、高质量与说话人模型由受控模型通道提供。",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(onClick = { updateSettingsExpanded = !updateSettingsExpanded }) {
                    Text(if (updateSettingsExpanded) "收起更新设置" else "更新设置")
                }
                if (debugChannelEnabled) {
                    TextButton(onClick = { debugExpanded = !debugExpanded }) {
                        Text(if (debugExpanded) "收起开发选项" else "开发选项")
                    }
                }
            }

            if (debugChannelEnabled && debugExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "候选模型清单验收（Debug）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "仅用于后续未合并候选清单的真机验收。正式模型使用 production；修改后需完全退出并重新打开 App 才会生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = debugManifestUrl,
                        onValueChange = { debugManifestUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("候选 production.json URL") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                try {
                                    VoicaModelChannel.setDebugManifestUrl(
                                        application = application,
                                        manifestUrl = debugManifestUrl.trim().takeIf { it.isNotEmpty() },
                                    )
                                    debugManifestUrl =
                                        VoicaModelChannel.configuredDebugManifestUrl(application).orEmpty()
                                    message = "候选模型清单已保存；完全退出并重新打开 App 后生效"
                                } catch (_: Exception) {
                                    message = "候选模型清单无效，请检查 URL"
                                }
                            },
                        ) {
                            Text("保存候选清单")
                        }
                        OutlinedButton(
                            onClick = {
                                VoicaModelChannel.setDebugManifestUrl(
                                    application = application,
                                    manifestUrl = null,
                                )
                                debugManifestUrl = ""
                                message = "已恢复 production；完全退出并重新打开 App 后生效"
                            },
                        ) {
                            Text("恢复 production")
                        }
                    }
                    Text(
                        "下次启动清单：" + VoicaModelChannel.resolveManifestUrl(application),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (updateSettingsExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动检查模型更新")
                        Text(
                            "默认开启；仅检查 production 清单。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = updateSettings.automaticChecksEnabled,
                        onCheckedChange = modelUpdateController::setAutomaticChecksEnabled,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Silero 小模型自动升级")
                        Text(
                            "自动下载、校验、运行库 smoke test 后切换；ASR、标点、SenseVoice 与说话人模型仍需手动确认。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = updateSettings.automaticSmallModelUpdatesEnabled,
                        onCheckedChange = modelUpdateController::setAutomaticSmallModelUpdatesEnabled,
                    )
                }
            }

            Button(
                enabled = !checking && !updateState.checking,
                onClick = {
                    checking = true
                    message = null
                    scope.launch {
                        try {
                            modelUpdateController.checkForUpdates(force = true)
                            reload()
                            val autoUpdated =
                                modelUpdateController.state.value.automaticallyUpdatedModelIds
                            message =
                                if (autoUpdated.isEmpty()) {
                                    "模型更新检查完成"
                                } else {
                                    "模型更新检查完成；Silero 已自动验证并启用新版本"
                                }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message =
                                "模型更新检查失败：" +
                                    modelUserSafeErrorMessage(
                                        error.message,
                                        "请检查网络后重试",
                                    )
                        } finally {
                            checking = false
                        }
                    }
                },
            ) {
                Text(if (checking || updateState.checking) "检查中…" else "检查模型更新")
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            updateState.errorMessage?.let { error ->
                if (message == null) {
                    Text(
                        "自动检查失败：" +
                            modelUserSafeErrorMessage(error, "请检查网络后重试"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            models.forEach { availability ->
                val descriptor = availability.descriptor
                ModelAvailabilityRow(
                    availability = availability,
                    operation = operations[descriptor.modelId],
                    durableOperation = durableOperations[descriptor.modelId],
                    onInstallAndActivate = {
                        scope.launch {
                            try {
                                if (durableController != null) {
                                    durableController.installAndActivate(
                                        modelId = descriptor.modelId,
                                        version = descriptor.version,
                                        revision = descriptor.revision,
                                        origin = ModelInstallOrigin.MANUAL,
                                    )
                                } else {
                                    modelManager.install(
                                        modelId = descriptor.modelId,
                                        version = descriptor.version,
                                        revision = descriptor.revision,
                                    )
                                    modelManager.confirmInstalledVersion(
                                        modelId = descriptor.modelId,
                                        version = descriptor.version,
                                        revision = descriptor.revision,
                                    )
                                }
                                reload()
                                message = descriptor.displayName + " 已下载、验证并启用"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reload()
                                message =
                                    descriptor.displayName +
                                        " 安装未完成：" +
                                        modelUserSafeErrorMessage(
                                            error.message,
                                            "请查看模型状态后重试",
                                        )
                            }
                        }
                    },
                    onRollback = {
                        scope.launch {
                            try {
                                modelManager.rollback(descriptor.modelId)
                                reload()
                                message = descriptor.displayName + " 已回滚"
                            } catch (error: Exception) {
                                message =
                                    descriptor.displayName +
                                        " 回滚失败：" +
                                        modelUserSafeErrorMessage(error.message, "请稍后重试")
                            }
                        }
                    },
                    onDelete = {
                        val version = availability.installedVersion
                        val revision = availability.installedRevision
                        if (version != null && revision != null) {
                            scope.launch {
                                try {
                                    modelManager.removeDownloadedVersion(
                                        modelId = descriptor.modelId,
                                        version = version,
                                        revision = revision,
                                    )
                                    reload()
                                    message = descriptor.displayName + " 已删除"
                                } catch (error: Exception) {
                                    message =
                                        descriptor.displayName +
                                            " 删除失败：" +
                                            modelUserSafeErrorMessage(error.message, "请稍后重试")
                                }
                            }
                        }
                    },
                    onCancel = {
                        scope.launch {
                            modelManager.cancelInstall(descriptor.modelId)
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
    durableOperation: ModelInstallJournalRecord?,
    onInstallAndActivate: () -> Unit,
    onRollback: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptor = availability.descriptor
    val downloadedCandidateReady =
        availability.availableRevision != null &&
            availability.installedRevision == availability.availableRevision &&
            availability.activeRevision != availability.availableRevision
    val hasDownloadedInstalledVersion =
        availability.installedRevision != null &&
            (availability.builtinRevision == null ||
                availability.installedRevision != availability.builtinRevision)
    val canRollback =
        availability.activeRevision != null &&
            (availability.previousRevision != null ||
                (availability.builtinRevision != null &&
                    availability.activeRevision != availability.builtinRevision))
    val durableVisible =
        durableOperation != null &&
            durableOperation.phase != ModelInstallPhase.READY &&
            durableOperation.phase != ModelInstallPhase.CANCELLED
    val durableRunning = durableVisible && durableOperation?.phase?.terminal == false
    val durableAttention =
        durableOperation?.requiresUserResume == true ||
            durableOperation?.phase == ModelInstallPhase.FAILED_RUNTIME ||
            durableOperation?.phase == ModelInstallPhase.FAILED_INTEGRITY ||
            durableOperation?.phase == ModelInstallPhase.FAILED_CONFIGURATION
    val sameDurableCandidate =
        durableOperation?.snapshot?.descriptor?.let { frozen ->
            frozen.version == descriptor.version && frozen.revision == descriptor.revision
        } == true
    val legacyRunning =
        operation?.state == ModelState.DOWNLOADING ||
            operation?.state == ModelState.VERIFYING
    var expanded by remember(descriptor.modelId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(descriptor.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    (availability.activeVersion ?: "未启用") +
                        " · " + modelStateText(availability.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (durableVisible && durableOperation != null) {
                    Text(
                        durableOperation.snapshot.descriptor.version +
                            " · " + durablePhaseText(durableOperation),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (durableAttention) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            }
            Text(
                if (expanded) "收起" else "详情",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (!expanded && !durableRunning && !durableAttention && !legacyRunning) {
            return@Column
        }

        Text(
            "可用版本 " +
                (availability.availableVersion ?: descriptor.version) +
                " · 当前 " +
                (availability.activeVersion ?: "未启用"),
            style = MaterialTheme.typography.bodySmall,
        )

        if (availability.builtinRevision != null &&
            availability.activeRevision == availability.builtinRevision &&
            !availability.updateAvailable
        ) {
            Text(
                "APK 内置基线 · 已是最新版本；暂无远程更新",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (durableVisible && durableOperation != null) {
            DurableOperationSection(
                record = durableOperation,
                sameCandidateAsCatalog = sameDurableCandidate,
                hasDownloadedCandidate = downloadedCandidateReady,
                onInstallAndActivate = onInstallAndActivate,
                onDelete = onDelete,
                onCancel = onCancel,
            )
        } else {
            LegacyOrStableModelActions(
                availability = availability,
                operation = operation,
                downloadedCandidateReady = downloadedCandidateReady,
                onInstallAndActivate = onInstallAndActivate,
                onDelete = onDelete,
                onCancel = onCancel,
            )
        }

        if (!durableRunning && !durableVisible &&
            (canRollback || hasDownloadedInstalledVersion)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canRollback) {
                    OutlinedButton(onClick = onRollback) {
                        Text("回滚")
                    }
                }
                if (hasDownloadedInstalledVersion) {
                    OutlinedButton(onClick = onDelete) {
                        Text("删除模型")
                    }
                }
            }
        }
    }
}

@Composable
private fun DurableOperationSection(
    record: ModelInstallJournalRecord,
    sameCandidateAsCatalog: Boolean,
    hasDownloadedCandidate: Boolean,
    onInstallAndActivate: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    modelInstallRecordUserMessage(record)?.let { error ->
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    when (record.phase) {
        ModelInstallPhase.DOWNLOAD -> {
            val total = record.totalBytes
            val complete = total != null && total > 0L && record.downloadedBytes >= total
            if (total != null && total > 0L) {
                LinearProgressIndicator(
                    progress = { record.downloadedBytes.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    (record.downloadedBytes * 100L / total).toString() + "% · " +
                        formatBytes(record.downloadedBytes) + " / " + formatBytes(total),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel) {
                Text(if (complete) "暂停安装" else "取消下载")
            }
        }

        ModelInstallPhase.REQUESTED -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(durablePhaseText(record), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel) {
                Text("取消下载")
            }
        }

        ModelInstallPhase.VERIFY,
        ModelInstallPhase.EXTRACT,
        ModelInstallPhase.FILE_VERIFY,
        ModelInstallPhase.RUNTIME_VALIDATE,
        ModelInstallPhase.ATOMIC_ACTIVATE -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(durablePhaseText(record), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel) {
                Text("暂停安装")
            }
        }

        ModelInstallPhase.INTERRUPTED,
        ModelInstallPhase.FAILED_RECOVERABLE -> {
            if (sameCandidateAsCatalog) {
                Button(onClick = onInstallAndActivate) {
                    Text("继续安装")
                }
            } else {
                Text(
                    "未完成任务对应 ${record.snapshot.descriptor.version}；当前清单版本已变化。请放弃旧任务后安装当前版本。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onCancel) {
                Text("放弃安装并删除下载")
            }
        }

        ModelInstallPhase.FAILED_RUNTIME -> {
            if (sameCandidateAsCatalog) {
                Button(onClick = onInstallAndActivate) {
                    Text("重新验证")
                }
            }
            if (hasDownloadedCandidate) {
                OutlinedButton(onClick = onDelete) {
                    Text("删除候选")
                }
            }
        }

        ModelInstallPhase.FAILED_INTEGRITY,
        ModelInstallPhase.FAILED_CONFIGURATION -> {
            if (sameCandidateAsCatalog) {
                Button(onClick = onInstallAndActivate) {
                    Text("重新下载安装")
                }
            }
        }

        ModelInstallPhase.READY,
        ModelInstallPhase.CANCELLED -> Unit
    }
}

@Composable
private fun LegacyOrStableModelActions(
    availability: ModelAvailability,
    operation: ModelOperationStatus?,
    downloadedCandidateReady: Boolean,
    onInstallAndActivate: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptor = availability.descriptor
    if (operation?.state == ModelState.VERIFYING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("正在验证并启用模型…", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCancel) {
            Text("暂停安装")
        }
        return
    }
    if (operation?.state == ModelState.DOWNLOADING) {
        val downloaded = operation.downloadedBytes
        val total = operation.totalBytes
        val complete = downloaded != null && total != null && total > 0L && downloaded >= total
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
            Text(if (complete) "暂停安装" else "取消下载")
        }
        return
    }

    if (operation?.state == ModelState.LOAD_FAILED ||
        operation?.state == ModelState.CORRUPTED
    ) {
        operation.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                "安装错误：" + modelUserSafeErrorMessage(error, "模型安装未完成，请重试"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (downloadedCandidateReady) {
        Text("候选模型已下载，尚未启用", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onInstallAndActivate) {
                Text("验证并启用")
            }
            OutlinedButton(onClick = onDelete) {
                Text("删除候选")
            }
        }
        return
    }

    if (descriptor.downloadUrl != null &&
        (availability.state == ModelState.NOT_INSTALLED ||
            availability.state == ModelState.LOAD_FAILED ||
            availability.state == ModelState.CORRUPTED ||
            availability.updateAvailable)
    ) {
        if (availability.updateAvailable) {
            Text("有新版本可用", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onInstallAndActivate) {
            Text(
                when {
                    availability.updateAvailable -> "下载并启用更新"
                    availability.state == ModelState.LOAD_FAILED ||
                        availability.state == ModelState.CORRUPTED -> "重新下载安装"
                    else -> "下载并启用"
                },
            )
        }
    }
}

private fun durablePhaseText(record: ModelInstallJournalRecord): String =
    when (record.phase) {
        ModelInstallPhase.REQUESTED -> "等待开始"
        ModelInstallPhase.DOWNLOAD -> "正在下载"
        ModelInstallPhase.VERIFY -> "正在校验下载文件"
        ModelInstallPhase.EXTRACT -> "正在解压模型"
        ModelInstallPhase.FILE_VERIFY -> "正在校验模型文件"
        ModelInstallPhase.RUNTIME_VALIDATE -> "正在验证运行库"
        ModelInstallPhase.ATOMIC_ACTIVATE -> "正在启用模型"
        ModelInstallPhase.READY -> "已启用"
        ModelInstallPhase.INTERRUPTED -> "安装已中断 · 可继续"
        ModelInstallPhase.FAILED_RECOVERABLE -> "安装暂停 · 可继续"
        ModelInstallPhase.FAILED_INTEGRITY -> "文件校验失败"
        ModelInstallPhase.FAILED_RUNTIME -> "运行库验证失败"
        ModelInstallPhase.FAILED_CONFIGURATION -> "安装配置失败"
        ModelInstallPhase.CANCELLED -> "已取消"
    }

private fun modelStateText(state: ModelState): String =
    when (state) {
        ModelState.NOT_INSTALLED -> "未安装"
        ModelState.DOWNLOADING -> "下载中"
        ModelState.VERIFYING -> "验证中"
        ModelState.INSTALLED -> "已安装"
        ModelState.LOAD_FAILED -> "安装未完成"
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
