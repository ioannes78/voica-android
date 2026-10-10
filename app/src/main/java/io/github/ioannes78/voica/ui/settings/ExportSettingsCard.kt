package io.github.ioannes78.voica.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ExportDestinationStore
import io.github.ioannes78.voica.ExportFeedbackBus
import io.github.ioannes78.voica.ExportFeedbackEvent
import io.github.ioannes78.voica.openExportedFile

@Composable
fun ExportSettingsCard() {
    val context = LocalContext.current
    val store = remember(context) { ExportDestinationStore(context) }
    val destination by store.destination.collectAsState()
    val feedbackEnabled by store.feedbackEnabled.collectAsState()
    val lastExport by store.lastExport.collectAsState()

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val granted =
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, flags)
                        true
                    }.getOrDefault(false)
                if (granted) {
                    store.saveCustomTree(uri)
                    ExportFeedbackBus.publish(
                        ExportFeedbackEvent(
                            message = "导出位置已更新：${store.current().label}",
                            success = true,
                        ),
                    )
                } else {
                    ExportFeedbackBus.publish(
                        ExportFeedbackEvent(
                            message = "无法保存所选文件夹权限",
                            success = false,
                        ),
                    )
                }
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    "导出位置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(destination.label) },
                    supportingContent = {
                        Text("录音、转写、总结默认导出到这里。")
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("选择其他文件夹") },
                    supportingContent = { Text("使用系统文件夹授权，后续导出自动保存。") },
                    leadingContent = {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { folderLauncher.launch(destination.treeUri) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("恢复默认") },
                    supportingContent = { Text(ExportDestinationStore.DEFAULT_DISPLAY_LABEL) },
                    leadingContent = {
                        Icon(Icons.Outlined.Restore, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            store.resetDefault()
                            ExportFeedbackBus.publish(
                                ExportFeedbackEvent(
                                    message = "已恢复默认导出位置：${ExportDestinationStore.DEFAULT_SHORT_LABEL}",
                                    success = true,
                                ),
                            )
                        },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    "导出反馈",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("导出完成后显示结果提示") },
                    supportingContent = {
                        Text("显示文件名和保存位置，并可直接打开。")
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Notifications, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = feedbackEnabled,
                            onCheckedChange = store::setFeedbackEnabled,
                        )
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("打开已导出文件") },
                    supportingContent = {
                        Text(
                            lastExport?.let {
                                "${it.displayName} · ${it.locationLabel}"
                            } ?: "暂无最近导出的文件",
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    },
                    modifier =
                        if (lastExport != null) {
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val item = lastExport ?: return@clickable
                                    if (!openExportedFile(context, item.uri, item.mimeType)) {
                                        ExportFeedbackBus.publish(
                                            ExportFeedbackEvent(
                                                message = "无法打开已导出文件",
                                                success = false,
                                            ),
                                        )
                                    }
                                }
                        } else {
                            Modifier.fillMaxWidth()
                        },
                )
            }
        }
    }
}
