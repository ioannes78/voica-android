package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.protocol.BatteryState

@Composable
fun DeviceStatusPanel(
    connection: DeviceConnectionState,
    info: DeviceInfo,
    isScanning: Boolean,
    timedOut: Boolean,
    discoveredCount: Int,
    missingPermissions: Set<String>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSyncTime: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = connection is DeviceConnectionState.Ready

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.device_status),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )

                when {
                    missingPermissions.isNotEmpty() -> {
                        Button(onClick = onRequestPermissions) {
                            Text(stringResource(R.string.permission_grant))
                        }
                    }
                    ready -> {
                        OutlinedButton(onClick = onDisconnect) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                    isScanning -> {
                        OutlinedButton(onClick = onStopScan) {
                            Text(stringResource(R.string.scan_stop))
                        }
                    }
                    else -> {
                        Button(onClick = onScan) {
                            Text(stringResource(R.string.scan_start))
                        }
                    }
                }
            }

            if (ready) {
                val readyState = connection as DeviceConnectionState.Ready
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            info.name ?: stringResource(R.string.device_default_name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            info.address ?: readyState.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        stringResource(R.string.device_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompactMetric(
                        value = batteryText(info.battery),
                        label = stringResource(R.string.device_metric_battery),
                        modifier = Modifier.weight(1f),
                    )
                    CompactMetric(
                        value = formatCapacity(info.remainingKb),
                        label = stringResource(R.string.device_metric_remaining),
                        modifier = Modifier.weight(1f),
                    )
                    CompactMetric(
                        value = info.firmwareVersion ?: "--",
                        label = stringResource(R.string.device_metric_firmware),
                        modifier = Modifier.weight(1f),
                    )
                }

            } else {
                Text(
                    connectionSummary(connection),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    missingPermissions.isNotEmpty() -> {
                        Text(
                            stringResource(R.string.permission_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    isScanning -> {
                        Text(
                            stringResource(R.string.device_scan_found_count, discoveredCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    timedOut -> {
                        Text(
                            stringResource(R.string.scan_timeout),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactScanDeviceRow(
    device: BleScanDevice,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onConnect(device.address) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                device.name ?: stringResource(R.string.unnamed_device),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(device.address)
                    append(" · RSSI ")
                    append(device.rssi)
                    append(" dBm")
                    if (device.advertisesAe20) {
                        append(" · AE20")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun connectionSummary(state: DeviceConnectionState): String =
    when (state) {
        DeviceConnectionState.Unavailable -> stringResource(R.string.state_unavailable)
        is DeviceConnectionState.PermissionRequired -> stringResource(R.string.state_permission_required)
        DeviceConnectionState.BluetoothOff -> stringResource(R.string.state_bluetooth_off)
        DeviceConnectionState.Idle -> stringResource(R.string.device_not_connected)
        DeviceConnectionState.Scanning -> stringResource(R.string.scan_scanning)
        is DeviceConnectionState.Connecting -> stringResource(R.string.state_connecting)
        is DeviceConnectionState.LinkConnected -> stringResource(R.string.state_link_connected)
        is DeviceConnectionState.DiscoveringServices -> stringResource(R.string.state_discovering)
        is DeviceConnectionState.Subscribing -> stringResource(R.string.device_connecting)
        is DeviceConnectionState.NegotiatingMtu -> stringResource(R.string.state_mtu)
        is DeviceConnectionState.Ready -> stringResource(R.string.device_connected)
        is DeviceConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
        is DeviceConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
        is DeviceConnectionState.ReconnectWaiting ->
            if (state.attempt <= FAST_RECONNECT_VISIBLE_ATTEMPTS) {
                stringResource(R.string.state_reconnect, state.attempt, state.delayMs / 1000)
            } else {
                "等待设备重新连接"
            }
        is DeviceConnectionState.Error -> stringResource(R.string.state_error)
    }

@Composable
private fun batteryText(state: BatteryState): String =
    when (state) {
        is BatteryState.Level -> state.percent.toString() + "%"
        BatteryState.Charging -> stringResource(R.string.battery_charging)
        is BatteryState.Unknown -> "--"
    }

private fun formatCapacity(kb: Long?): String {
    if (kb == null) return "--"
    return when {
        kb >= 1024L * 1024L -> "%.2f GB".format(kb.toDouble() / 1024.0 / 1024.0)
        kb >= 1024L -> "%.1f MB".format(kb.toDouble() / 1024.0)
        else -> "$kb KB"
    }
}

private const val FAST_RECONNECT_VISIBLE_ATTEMPTS = 3
