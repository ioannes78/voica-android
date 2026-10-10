package io.github.ioannes78.voica.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ExportDestinationSnapshot
import io.github.ioannes78.voica.ExportDestinationStore

@Composable
fun ExportSettingsCard() {
    val context = LocalContext.current
    val store = remember(context) { ExportDestinationStore(context) }
    val destination by store.destination.collectAsState()
    val displayLocation = remember(destination.treeUri, destination.label) {
        destination.displayLocationLabel()
    }

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
                } else {
                    Toast.makeText(
                        context,
                        "无法保存所选文件夹权限",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                "导出位置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("保存位置") },
                supportingContent = { Text(displayLocation) },
                leadingContent = {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                },
                modifier =
                    Modifier
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = destination.custom) {
                            store.resetDefault()
                        },
            )
        }
    }
}

private fun ExportDestinationSnapshot.displayLocationLabel(): String {
    val uri = treeUri ?: return ExportDestinationStore.DEFAULT_DISPLAY_LABEL
    return readableTreePath(uri) ?: label
}

private fun readableTreePath(uri: Uri): String? =
    runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val separator = treeId.indexOf(':')
        if (separator < 0) return@runCatching null

        val volumeId = treeId.substring(0, separator)
        val relativePath = treeId.substring(separator + 1).trim('/')
        val volumeLabel =
            when (volumeId.lowercase()) {
                "primary" -> "内部存储"
                "home" -> "文档"
                else -> volumeId
            }

        if (relativePath.isBlank()) {
            volumeLabel
        } else {
            "$volumeLabel / ${relativePath.replace("/", " / ")}"
        }
    }.getOrNull()
