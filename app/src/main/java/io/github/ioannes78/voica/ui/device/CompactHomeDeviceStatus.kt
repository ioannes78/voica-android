package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.protocol.BatteryState
import java.util.Locale

/** Homepage-only connection projection. Full diagnostics remain on the existing diagnostics page. */
@Composable
internal fun CompactHomeDeviceStatus(
    connection: DeviceConnectionState,
    info: DeviceInfo,
    recordingFileCount: Int,
    isScanning: Boolean,
    discoveredCount: Int,
    missingPermissions: Set<String>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = connection is DeviceConnectionState.Ready
    val address = when (connection) {
        is DeviceConnectionState.Ready -> connection.address
        is DeviceConnectionState.Connecting -> connection.address
        is DeviceConnectionState.LinkConnected -> connection.address
        is DeviceConnectionState.DiscoveringServices -> connection.address
        is DeviceConnectionState.NegotiatingMtu -> connection.address
        is DeviceConnectionState.ReconnectWaiting -> connection.address
        else -> info.address
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        if (ready) {
            ConnectedDeviceStatus(info, address, recordingFileCount, onDisconnect, onOpenFiles)
        } else {
            DisconnectedDeviceStatus(
                connection, isScanning, discoveredCount, missingPermissions,
                onScan, onStopScan, onRequestPermissions,
            )
        }
    }
}

@Composable
private fun ConnectedDeviceStatus(
    info: DeviceInfo,
    address: String?,
    recordingFileCount: Int,
    onDisconnect: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.voica_qs668_device_photo),
                contentDescription = "QS668 / CB08",
                modifier = Modifier.size(width = 66.dp, height = 72.dp),
                contentScale = ContentScale.Fit,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("QS668 / CB08", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    address ?: info.address ?: "设备地址未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onDisconnect) {
                Text("已连接", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        DeviceMetricsRow(info, recordingFileCount, onOpenFiles)
    }
}

@Composable
private fun DeviceMetricsRow(info: DeviceInfo, recordingFileCount: Int, onOpenFiles: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeviceMetric(compactBatteryValue(info.battery), "电量", Modifier.weight(1f))
        MetricDivider()
        DeviceMetric(compactStorageValue(info.remainingKb), "可用空间", Modifier.weight(1.15f))
        MetricDivider()
        DeviceMetric(
            value = "$recordingFileCount 个",
            label = "录音文件",
            modifier = Modifier.weight(1f).clickable(onClick = onOpenFiles).padding(vertical = 2.dp),
            emphasize = true,
        )
        MetricDivider()
        DeviceMetric(
            info.firmwareVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: "--",
            "固件",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeviceMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier.width(1.dp).height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

@Composable
private fun DisconnectedDeviceStatus(
    connection: DeviceConnectionState,
    isScanning: Boolean,
    discoveredCount: Int,
    missingPermissions: Set<String>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val title = when {
        missingPermissions.isNotEmpty() -> "需要蓝牙权限"
        isScanning || connection == DeviceConnectionState.Scanning -> "正在查找录音卡"
        connection is DeviceConnectionState.Connecting || connection is DeviceConnectionState.LinkConnected ||
            connection is DeviceConnectionState.DiscoveringServices || connection is DeviceConnectionState.Subscribing ||
            connection is DeviceConnectionState.NegotiatingMtu -> "正在连接录音卡"
        connection is DeviceConnectionState.ReconnectWaiting -> "正在等待录音卡重连"
        else -> "未连接设备"
    }
    val supporting = when {
        missingPermissions.isNotEmpty() -> "授权后可连接 QS668 / CB08"
        isScanning -> "已发现 $discoveredCount 个蓝牙设备"
        connection == DeviceConnectionState.BluetoothOff -> "请先打开蓝牙"
        connection is DeviceConnectionState.Error -> "连接异常，可重新扫描"
        connection is DeviceConnectionState.ReconnectWaiting -> "录音卡录音不会因 App 重连而停止"
        else -> "也可以直接使用手机录音和实时转写"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.voica_qs668_device_photo),
            contentDescription = "QS668 / CB08",
            modifier = Modifier.size(width = 62.dp, height = 68.dp).alpha(0.42f),
            contentScale = ContentScale.Fit,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        when {
            missingPermissions.isNotEmpty() -> TextButton(onClick = onRequestPermissions) { Text("授权") }
            isScanning -> TextButton(onClick = onStopScan) { Text("停止") }
            connection != DeviceConnectionState.BluetoothOff && connection != DeviceConnectionState.Unavailable ->
                TextButton(onClick = onScan) { Text("扫描设备") }
        }
    }
}

private fun compactBatteryValue(state: BatteryState): String = when (state) {
    is BatteryState.Level -> "${state.percent}%"
    BatteryState.Charging -> "充电中"
    is BatteryState.Unknown -> "--"
}

private fun compactStorageValue(remainingKb: Long?): String {
    val value = remainingKb ?: return "--"
    val gb = value.toDouble() / 1024.0 / 1024.0
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.0f MB", value.toDouble() / 1024.0)
}
