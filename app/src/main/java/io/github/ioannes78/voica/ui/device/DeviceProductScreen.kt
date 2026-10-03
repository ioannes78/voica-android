package io.github.ioannes78.voica.ui.device

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.RecordingCommandState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.DeviceActionMessage
import io.github.ioannes78.voica.ui.DeviceViewModel
import io.github.ioannes78.voica.ui.files.DeviceFilesCard
import io.github.ioannes78.voica.ui.recording.RecordingCard

private enum class DevicePage {
    HOME,
    FILES,
    ALL_DEVICES,
    DIAGNOSTICS,
}

@Composable
fun DeviceProductScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
) {
    val scan by viewModel.scanState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val info by viewModel.deviceInfo.collectAsState()
    val recording by viewModel.recordingState.collectAsState()
    val deviceFiles by viewModel.deviceFileListState.collectAsState()
    val libraryRecordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
    val diagnostics by viewModel.diagnostics.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    var page by rememberSaveable { mutableStateOf(DevicePage.HOME) }

    LaunchedEffect(connection) {
        if (connection is DeviceConnectionState.PermissionRequired) {
            viewModel.refreshPermissions()
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            viewModel.refreshPermissions()
        }

    if (page != DevicePage.HOME) {
        BackHandler { page = DevicePage.HOME }
    }

    when (page) {
        DevicePage.HOME -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    DeviceHeader(
                        connected = connection is DeviceConnectionState.Ready,
                        onRefresh = viewModel::refreshDeviceInfo,
                        onSyncTime = viewModel::syncTime,
                        onOpenFiles = { page = DevicePage.FILES },
                        onOpenDiagnostics = { page = DevicePage.DIAGNOSTICS },
                    )
                }

                item {
                    DeviceStatusPanel(
                        connection = connection,
                        info = info,
                        isScanning = scan.isScanning,
                        timedOut = scan.timedOut,
                        discoveredCount = scan.devices.size,
                        missingPermissions = missingPermissions,
                        onScan = viewModel::startScan,
                        onStopScan = viewModel::stopScan,
                        onRequestPermissions = {
                            permissionLauncher.launch(missingPermissions.toTypedArray())
                        },
                        onRefresh = viewModel::refreshDeviceInfo,
                        onSyncTime = viewModel::syncTime,
                        onDisconnect = viewModel::disconnect,
                    )
                }

                if (connection !is DeviceConnectionState.Ready) {
                    items(
                        items = scan.devices.take(MAX_HOME_SCAN_RESULTS),
                        key = { it.address },
                    ) { device ->
                        CompactScanDeviceRow(
                            device = device,
                            onConnect = viewModel::connect,
                        )
                    }

                    if (scan.devices.size > MAX_HOME_SCAN_RESULTS) {
                        item {
                            TextButton(onClick = { page = DevicePage.ALL_DEVICES }) {
                                Text("查看全部 ${scan.devices.size} 台设备")
                            }
                        }
                    }
                } else {
                    item {
                        RecordingCard(
                            state = recording,
                            onStart = viewModel::startRecording,
                            onPause = viewModel::pauseRecording,
                            onResume = viewModel::resumeRecording,
                            onSave = viewModel::saveRecording,
                            onRefresh = viewModel::refreshRecordingState,
                            onSetGain = viewModel::setRecordingGain,
                        )
                    }

                    item {
                        DeviceFilesEntry(
                            count = deviceFiles.files.size,
                            latestName = deviceFiles.files.firstOrNull()?.displayFilename,
                            onClick = { page = DevicePage.FILES },
                        )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DevicePage.FILES -> {
            DeviceFilesPage(
                padding = padding,
                viewModel = viewModel,
                onBack = { page = DevicePage.HOME },
            )
        }

        DevicePage.ALL_DEVICES -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    CompactSubpageHeader(
                        title = "发现的设备",
                        onBack = { page = DevicePage.HOME },
                    )
                }
                items(scan.devices, key = { it.address }) { device ->
                    CompactScanDeviceRow(
                        device = device,
                        onConnect = {
                            page = DevicePage.HOME
                            viewModel.connect(it)
                        },
                    )
                }
            }
        }

        DevicePage.DIAGNOSTICS -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    CompactSubpageHeader(
                        title = "设备诊断",
                        onBack = { page = DevicePage.HOME },
                    )
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            DiagnosticRow("Session", diagnostics.sessionId.toString())
                            DiagnosticRow("GATT", diagnostics.gattStage)
                            DiagnosticRow("MTU", diagnostics.negotiatedMtu?.toString() ?: "--")
                            DiagnosticRow("AE20", diagnostics.shape.ae20Found.toString())
                            DiagnosticRow("AE22", diagnostics.ae22Subscribed.toString())
                            DiagnosticRow("AE23", diagnostics.ae23Subscribed.toString())
                            DiagnosticRow(
                                "录音状态",
                                diagnostics.recording.statusDecoded ?: "--",
                            )
                            DiagnosticRow(
                                "录音时长",
                                diagnostics.recording.durationSeconds?.toString() ?: "--",
                            )
                            DiagnosticRow(
                                "设备文件",
                                diagnostics.fileList.parsedEntryCount.toString(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceHeader(
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
    ) {
        Text(
            stringResource(R.string.device_screen_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "更多设备操作",
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

@Composable
private fun DeviceFilesEntry(
    count: Int,
    latestName: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = { Text("设备录音") },
            supportingContent = {
                Text(
                    if (latestName == null) {
                        "$count 个文件"
                    } else {
                        "$count 个文件 · 最近：$latestName"
                    },
                    maxLines = 1,
                )
            },
            trailingContent = {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            },
        )
    }
}

@Composable
private fun DeviceFilesPage(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
    onBack: () -> Unit,
) {
    val recording by viewModel.recordingState.collectAsState()
    val state by viewModel.deviceFileListState.collectAsState()
    val operation by viewModel.fileOperationState.collectAsState()
    val recordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())

    val canRefresh =
        recording.status == RecordingStatus.Idle &&
            recording.freshness == RecordingFreshness.FRESH &&
            recording.commandState == RecordingCommandState.IDLE &&
            state.freshness != FileListFreshness.LOADING &&
            operation !is FileOperationState.Active

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            CompactSubpageHeader(
                title = "设备录音",
                onBack = onBack,
            )
        }
        item {
            DeviceFilesCard(
                state = state,
                operationState = operation,
                localRecordings = recordings,
                canRefresh = canRefresh,
                onRefresh = viewModel::refreshDeviceFiles,
                onDownload = viewModel::downloadDeviceFile,
                onCancelDownload = viewModel::cancelDeviceFileDownload,
                onDeleteRemote = viewModel::deleteRemoteRecording,
                onRangeProbe = viewModel::runRangeProbe,
            )
        }
    }
}

@Composable
private fun CompactSubpageHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private const val MAX_HOME_SCAN_RESULTS = 4
