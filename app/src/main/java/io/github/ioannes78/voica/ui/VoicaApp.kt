package io.github.ioannes78.voica.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.BleError
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.protocol.BatteryState

@Composable
fun VoicaApp(repository: DeviceRepository) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("蓝") },
                    label = { Text(stringResource(R.string.tab_device)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("设") },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        if (selectedTab == 0) {
            val deviceViewModel: DeviceViewModel = viewModel(
                factory = remember(repository) {
                    DeviceViewModel.Factory(repository)
                },
            )
            DeviceScreen(padding, deviceViewModel)
        } else {
            SettingsScreen(padding)
        }
    }
}

@Composable
private fun DeviceScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
) {
    val scan by viewModel.scanState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val info by viewModel.deviceInfo.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connection) {
        if (connection is DeviceConnectionState.PermissionRequired) {
            viewModel.refreshPermissions()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                stringResource(R.string.stage2_subtitle),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.version_label),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            StatusCard(
                connection = connection,
                missingPermissions = missingPermissions,
                onRequestPermissions = {
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                },
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.scan_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = viewModel::startScan,
                            enabled = !scan.isScanning && missingPermissions.isEmpty(),
                        ) {
                            Text(stringResource(R.string.scan_start))
                        }
                        OutlinedButton(
                            onClick = viewModel::stopScan,
                            enabled = scan.isScanning,
                        ) {
                            Text(stringResource(R.string.scan_stop))
                        }
                    }
                    Text(
                        if (scan.isScanning) {
                            stringResource(R.string.scan_scanning)
                        } else if (scan.timedOut) {
                            stringResource(R.string.scan_timeout)
                        } else {
                            stringResource(R.string.scan_idle)
                        },
                    )
                    scan.error?.let {
                        Text(errorText(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        items(scan.devices, key = { it.address }) { device ->
            ScanDeviceCard(device = device, onConnect = viewModel::connect)
        }

        if (connection is DeviceConnectionState.Ready) {
            item {
                DeviceInfoCard(
                    info = info,
                    connection = connection as DeviceConnectionState.Ready,
                    onRefresh = viewModel::refreshDeviceInfo,
                    onSyncTime = viewModel::syncTime,
                    onDisconnect = viewModel::disconnect,
                )
            }
        } else if (connection !is DeviceConnectionState.Idle &&
            connection !is DeviceConnectionState.Scanning &&
            connection !is DeviceConnectionState.PermissionRequired &&
            connection !is DeviceConnectionState.Unavailable &&
            connection !is DeviceConnectionState.BluetoothOff
        ) {
            item {
                OutlinedButton(onClick = viewModel::disconnect) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        }

        actionMessage?.let { message ->
            item {
                Text(
                    stringResource(
                        if (message == DeviceActionMessage.SYNC_SENT) {
                            R.string.sync_sent
                        } else {
                            R.string.sync_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { diagnosticsExpanded = !diagnosticsExpanded },
            ) {
                Text(
                    if (diagnosticsExpanded) {
                        stringResource(R.string.diagnostics_hide)
                    } else {
                        stringResource(R.string.diagnostics_show)
                    },
                )
            }
        }

        if (diagnosticsExpanded) {
            item {
                DiagnosticsCard(diagnostics)
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(
    connection: DeviceConnectionState,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.device_status),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(connectionText(connection))
            if (missingPermissions.isNotEmpty()) {
                Text(stringResource(R.string.permission_needed))
                Button(onClick = onRequestPermissions) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
private fun ScanDeviceCard(
    device: BleScanDevice,
    onConnect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                device.name ?: stringResource(R.string.unnamed_device),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(device.address)
            Text("RSSI " + device.rssi + " dBm")
            Text(
                if (device.advertisesAe20) {
                    stringResource(R.string.ae20_advertised)
                } else {
                    stringResource(R.string.ae20_not_advertised)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { onConnect(device.address) }) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    info: DeviceInfo,
    connection: DeviceConnectionState.Ready,
    onRefresh: () -> Unit,
    onSyncTime: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                stringResource(R.string.device_info),
                style = MaterialTheme.typography.titleLarge,
            )
            InfoRow(stringResource(R.string.info_name), info.name ?: "--")
            InfoRow(stringResource(R.string.info_address), info.address ?: connection.address)
            InfoRow(stringResource(R.string.info_battery), batteryText(info.battery))
            InfoRow(
                stringResource(R.string.info_capacity_remaining),
                formatCapacity(info.remainingKb),
            )
            InfoRow(
                stringResource(R.string.info_capacity_total),
                formatCapacity(info.totalKb),
            )
            InfoRow(
                stringResource(R.string.info_firmware),
                info.firmwareVersion ?: "--",
            )
            InfoRow(
                stringResource(R.string.info_auth),
                if (info.authAvailable) {
                    stringResource(R.string.auth_read_ok)
                } else {
                    stringResource(R.string.auth_unknown)
                },
            )
            InfoRow(
                stringResource(R.string.info_mtu),
                connection.negotiatedMtu.toString(),
            )
            InfoRow(
                stringResource(R.string.info_atomic36),
                yesNo(connection.capability.atomic36Supported),
            )
            InfoRow(
                stringResource(R.string.info_data168),
                yesNo(connection.capability.data168Supported),
            )
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
                OutlinedButton(onClick = onSyncTime) {
                    Text(stringResource(R.string.sync_time))
                }
            }
            OutlinedButton(onClick = onDisconnect) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: BleDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleLarge,
            )
            DiagnosticLine("Session", diagnostics.sessionId.toString())
            DiagnosticLine("GATT", diagnostics.gattStage)
            DiagnosticLine("Requested MTU", diagnostics.requestedMtu.toString())
            DiagnosticLine(
                "Actual MTU",
                diagnostics.negotiatedMtu?.toString() ?: "--",
            )
            DiagnosticLine(
                "36B atomic",
                diagnostics.mtuCapability.atomic36Supported.toString(),
            )
            DiagnosticLine(
                "168B data",
                diagnostics.mtuCapability.data168Supported.toString(),
            )
            DiagnosticLine("AE20", diagnostics.shape.ae20Found.toString())
            DiagnosticLine(
                "AE21 properties",
                diagnostics.shape.ae21Properties?.toString() ?: "--",
            )
            DiagnosticLine("AE22 found", diagnostics.shape.ae22Found.toString())
            DiagnosticLine("AE22 subscribed", diagnostics.ae22Subscribed.toString())
            DiagnosticLine("AE23 found", diagnostics.shape.ae23Found.toString())
            DiagnosticLine("AE23 subscribed", diagnostics.ae23Subscribed.toString())
            DiagnosticLine(
                "Queue",
                diagnostics.queue.activeOperation ?: "idle",
            )
            DiagnosticLine(
                "Queue wait",
                diagnostics.queue.waitingCount.toString(),
            )
            DiagnosticLine(
                "Reconnect",
                diagnostics.reconnectAttempt.toString(),
            )
            DiagnosticLine(
                "AE22 frames",
                diagnostics.notifications.ae22Frames.toString(),
            )
            DiagnosticLine(
                "AE23 frames",
                diagnostics.notifications.ae23Frames.toString(),
            )
            DiagnosticLine(
                "AE22 CRC",
                diagnostics.notifications.ae22CrcErrors.toString(),
            )
            DiagnosticLine(
                "AE23 CRC",
                diagnostics.notifications.ae23CrcErrors.toString(),
            )
            DiagnosticLine(
                "Last TX",
                listOf(
                    diagnostics.lastTxType,
                    diagnostics.lastTxCommand,
                    diagnostics.lastTxSequence,
                ).joinToString("/"),
            )
            diagnostics.lastError?.let {
                DiagnosticLine("Last error", errorText(it))
            }
            if (diagnostics.logs.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.diagnostics_recent_log),
                    style = MaterialTheme.typography.titleSmall,
                )
                diagnostics.logs.takeLast(20).forEach { Text(it) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(label + ": " + value, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun connectionText(state: DeviceConnectionState): String =
    when (state) {
        DeviceConnectionState.Unavailable ->
            stringResource(R.string.state_unavailable)
        is DeviceConnectionState.PermissionRequired ->
            stringResource(R.string.state_permission_required)
        DeviceConnectionState.BluetoothOff ->
            stringResource(R.string.state_bluetooth_off)
        DeviceConnectionState.Idle ->
            stringResource(R.string.state_idle)
        DeviceConnectionState.Scanning ->
            stringResource(R.string.state_scanning)
        is DeviceConnectionState.Connecting ->
            stringResource(R.string.state_connecting)
        is DeviceConnectionState.LinkConnected ->
            stringResource(R.string.state_link_connected)
        is DeviceConnectionState.DiscoveringServices ->
            stringResource(R.string.state_discovering)
        is DeviceConnectionState.Subscribing ->
            if (state.source == NotificationSource.AE22) {
                stringResource(R.string.state_ae22)
            } else {
                stringResource(R.string.state_ae23)
            }
        is DeviceConnectionState.NegotiatingMtu ->
            stringResource(R.string.state_mtu)
        is DeviceConnectionState.Ready ->
            stringResource(R.string.state_ready)
        is DeviceConnectionState.Disconnecting ->
            stringResource(R.string.state_disconnecting)
        is DeviceConnectionState.Disconnected ->
            stringResource(R.string.state_disconnected)
        is DeviceConnectionState.ReconnectWaiting ->
            stringResource(
                R.string.state_reconnect,
                state.attempt,
                state.delayMs / 1000,
            )
        is DeviceConnectionState.Error ->
            stringResource(R.string.state_error) + ": " + errorText(state.error)
    }

@Composable
private fun errorText(error: BleError): String =
    when (error.code) {
        io.github.ioannes78.voica.ble.BleErrorCode.PERMISSION_DENIED ->
            stringResource(R.string.error_permission)
        io.github.ioannes78.voica.ble.BleErrorCode.BLUETOOTH_UNAVAILABLE ->
            stringResource(R.string.error_unavailable)
        io.github.ioannes78.voica.ble.BleErrorCode.BLUETOOTH_OFF ->
            stringResource(R.string.error_bluetooth_off)
        io.github.ioannes78.voica.ble.BleErrorCode.MTU_TOO_SMALL ->
            stringResource(R.string.error_mtu_small)
        else -> error.code.name + (error.detail?.let { ": " + it } ?: "")
    }

@Composable
private fun batteryText(state: BatteryState): String =
    when (state) {
        is BatteryState.Level -> state.percent.toString() + "%"
        BatteryState.Charging -> stringResource(R.string.battery_charging)
        is BatteryState.Unknown -> "--"
    }

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.yes else R.string.no)

private fun formatCapacity(kb: Long?): String {
    if (kb == null) return "--"
    return when {
        kb >= 1024L * 1024L ->
            "%.2f GB".format(kb.toDouble() / 1024.0 / 1024.0)
        kb >= 1024L ->
            "%.1f MB".format(kb.toDouble() / 1024.0)
        else -> kb.toString() + " KB"
    }
}

@Composable
private fun SettingsScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.settings_language))
                Text(stringResource(R.string.settings_language_value))
                HorizontalDivider()
                Text(stringResource(R.string.settings_scope))
                Text(stringResource(R.string.settings_scope_value))
            }
        }
    }
}
