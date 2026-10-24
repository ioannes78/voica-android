package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R

@Composable
internal fun VoicaBrandHeader(
    connected: Boolean,
    onRefresh: () -> Unit,
    onSyncTime: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "闻知 WENZHI",
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.size(44.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    "闻知",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "WENZHI · 闻声 · 知意",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "设备与主页操作",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("刷新设备信息") },
                    onClick = {
                        menuExpanded = false
                        onRefresh()
                    },
                    enabled = connected,
                )
                DropdownMenuItem(
                    text = { Text("同步设备时间") },
                    onClick = {
                        menuExpanded = false
                        onSyncTime()
                    },
                    enabled = connected,
                )
                DropdownMenuItem(
                    text = { Text("设备录音") },
                    onClick = {
                        menuExpanded = false
                        onOpenFiles()
                    },
                    enabled = connected,
                )
                DropdownMenuItem(
                    text = { Text("设备诊断") },
                    onClick = {
                        menuExpanded = false
                        onOpenDiagnostics()
                    },
                )
            }
        }
    }
}
