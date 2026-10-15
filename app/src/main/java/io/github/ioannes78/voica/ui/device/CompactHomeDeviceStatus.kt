package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.protocol.BatteryState

/** Compact homepage connection projection. Full diagnostics remain on the existing diagnostics page. */
@Composable
internal fun CompactHomeDeviceStatus(
    connection: DeviceConnectionState,
    info: DeviceInfo,
    isScanning: Boolean,
    discoveredCount: Int,
    missingPermissions: Set<String>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = connection is DeviceConnectionState.Ready
    val title =
        when {
            ready -> "录音卡已连接"
            missingPermissions.isNotEmpty() -> "需要蓝牙权限"
            isScanning || connection == DeviceConnectionState.Scanning -> "正在查找录音卡"
            connection is DeviceConnectionState.Connecting ||
                connection is DeviceConnectionState.LinkConnected ||
                connection is DeviceConnectionState.DiscoveringServices ||
                connection is DeviceConnectionState.Subscribing ||
                connection is DeviceConnectionState.NegotiatingMtu -> "正在连接录音卡"
            connection is DeviceConnectionState.ReconnectWaiting -> "正在等待录音卡重连"
            else -> "未连接录音卡"
        }
    val supporting =
        if (ready) {
            listOfNotNull(
                info.name?.trim()?.takeIf { it.isNotEmpty() },
                compactBatteryText(info.battery),
            ).joinToString(" · ").ifBlank { "设备已就绪" }
        } else {
            when {
                missingPermissions.isNotEmpty() -> "授权后可连接录音卡"
                isScanning -> "已发现 $discoveredCount 个蓝牙设备"
                connection == DeviceConnectionState.BluetoothOff -> "请先打开蓝牙"
                connection is DeviceConnectionState.Error -> "连接异常，可重新扫描"
                connection is DeviceConnectionState.ReconnectWaiting -> "录音卡录音不会因 App 重连而被停止"
                else -> "也可以直接使用手机录音和实时转写"
            }
        }

    Card(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    supporting,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                when {
                    missingPermissions.isNotEmpty() ->
                        TextButton(onClick = onRequestPermissions) { Text("授权") }
                    ready ->
                        TextButton(onClick = onDisconnect) { Text("断开") }
                    isScanning ->
                        TextButton(onClick = onStopScan) { Text("停止") }
                    connection != DeviceConnectionState.BluetoothOff &&
                        connection != DeviceConnectionState.Unavailable ->
                        TextButton(onClick = onScan) { Text("连接") }
                }
            },
        )
    }
}

private fun compactBatteryText(state: BatteryState): String? =
    when (state) {
        is BatteryState.Level -> "电量 ${state.percent}%"
        BatteryState.Charging -> "充电中"
        is BatteryState.Unknown -> null
    }
