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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.BleError
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.FileTransferDiagnostics
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RecordingCommandState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.ble.RemoteDeleteDiagnostics
import io.github.ioannes78.voica.ble.RangeProbeDiagnostics
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.protocol.BatteryState
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.ui.files.DeviceFilesCard
import io.github.ioannes78.voica.ui.files.LocalRecordingsCard
import io.github.ioannes78.voica.ui.playback.PlaybackCard
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.recording.RecordingCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoicaApp(
    repository: DeviceRepository,
    recordingLibraryRepository: RecordingLibraryRepository,
    canonicalAudioCoordinator: CanonicalAudioCoordinator,
    playbackController: PlaybackController,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val deviceViewModel: DeviceViewModel = viewModel(
        factory = remember(
            repository,
            recordingLibraryRepository,
            canonicalAudioCoordinator,
        ) {
            DeviceViewModel.Factory(
                repository,
                recordingLibraryRepository,
                canonicalAudioCoordinator,
            )
        },
    )
    val playbackViewModel: PlaybackViewModel = viewModel(
        factory = remember(
            playbackController,
            recordingLibraryRepository,
        ) {
            PlaybackViewModel.Factory(
                playbackController,
                recordingLibraryRepository,
            )
        },
    )

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
                    icon = { Text("文") },
                    label = { Text(stringResource(R.string.tab_local_files)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("设") },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DeviceScreen(padding, deviceViewModel)
            1 -> LocalFilesScreen(
                padding,
                deviceViewModel,
                playbackViewModel,
            )
            else -> SettingsScreen(padding)
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
    val recording by viewModel.recordingState.collectAsState()
    val deviceFiles by viewModel.deviceFileListState.collectAsState()
    val fileOperation by viewModel.fileOperationState.collectAsState()
    val fileTransferDiagnostics by viewModel.fileTransferDiagnostics.collectAsState()
    val remoteDeleteDiagnostics by viewModel.remoteDeleteDiagnostics.collectAsState()
    val rangeProbeDiagnostics by viewModel.rangeProbeDiagnostics.collectAsState()
    val libraryRecordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
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
                stringResource(R.string.stage7_subtitle),
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
                val canRefreshFiles =
                    recording.status == RecordingStatus.Idle &&
                        recording.freshness == RecordingFreshness.FRESH &&
                        recording.commandState == RecordingCommandState.IDLE &&
                        deviceFiles.freshness != FileListFreshness.LOADING &&
                        fileOperation !is FileOperationState.Active
                DeviceFilesCard(
                    state = deviceFiles,
                    operationState = fileOperation,
                    localRecordings = libraryRecordings,
                    canRefresh = canRefreshFiles,
                    onRefresh = viewModel::refreshDeviceFiles,
                    onDownload = viewModel::downloadDeviceFile,
                    onCancelDownload = viewModel::cancelDeviceFileDownload,
                    onDeleteRemote = viewModel::deleteRemoteRecording,
                    onRangeProbe = viewModel::runRangeProbe,
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
                DiagnosticsCard(
                    diagnostics,
                    fileTransferDiagnostics,
                    remoteDeleteDiagnostics,
                    rangeProbeDiagnostics,
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LocalFilesScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
    playbackViewModel: PlaybackViewModel,
) {
    val recordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
    val playback by playbackViewModel.snapshot.collectAsState()
    val playbackName =
        recordings.firstOrNull { it.id == playback.recordingId }?.displayName

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.local_files_screen_title),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                stringResource(R.string.local_files_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (playback.recordingId != null) {
            item {
                PlaybackCard(
                    snapshot = playback,
                    recordingName = playbackName,
                    onPlay = playbackViewModel::play,
                    onPause = playbackViewModel::pause,
                    onSeek = playbackViewModel::seekToSample,
                    onSpeed = playbackViewModel::setSpeed,
                    onRetry = playbackViewModel::retryCurrent,
                )
            }
        }
        item {
            LocalRecordingsCard(
                recordings = recordings,
                onPlay = playbackViewModel::loadAndPlay,
                onRename = viewModel::renameLocalRecording,
                onDeleteLocal = playbackViewModel::deleteRecording,
                onGenerateCanonical = viewModel::generateCanonicalAudio,
                onCancelCanonical = viewModel::cancelCanonicalAudio,
            )
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
private fun DiagnosticsCard(
    diagnostics: BleDiagnostics,
    fileTransfer: FileTransferDiagnostics,
    remoteDelete: RemoteDeleteDiagnostics,
    rangeProbe: RangeProbeDiagnostics,
) {
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
            HorizontalDivider()
            Text("Recording", style = MaterialTheme.typography.titleSmall)
            DiagnosticLine(
                "Recording state",
                diagnostics.recording.statusDecoded ?: "--",
            )
            DiagnosticLine(
                "Recording raw",
                diagnostics.recording.statusRaw?.toString() ?: "--",
            )
            DiagnosticLine(
                "Recording freshness",
                diagnostics.recording.freshness.name,
            )
            DiagnosticLine(
                "Duration",
                diagnostics.recording.durationSeconds?.toString() ?: "--",
            )
            DiagnosticLine(
                "Current bytes",
                diagnostics.recording.currentSizeBytes?.toString() ?: "--",
            )
            DiagnosticLine(
                "Filename",
                diagnostics.recording.filename ?: "--",
            )
            DiagnosticLine(
                "Gain",
                diagnostics.recording.gainDecoded ?: "--",
            )
            DiagnosticLine(
                "Gain raw",
                diagnostics.recording.gainRaw?.toString() ?: "--",
            )
            DiagnosticLine(
                "RX source/cmd",
                listOf(
                    diagnostics.recording.lastResponseSource,
                    diagnostics.recording.lastResponseCommand,
                ).joinToString("/"),
            )
            DiagnosticLine(
                "REQ/RSP seq",
                listOf(
                    diagnostics.recording.lastRequestSequence,
                    diagnostics.recording.lastResponseSequence,
                ).joinToString("/"),
            )
            DiagnosticLine(
                "RX latency",
                diagnostics.recording.lastResponseLatencyMs?.let { it.toString() + " ms" } ?: "--",
            )
            DiagnosticLine(
                "Last sync",
                diagnostics.recording.lastSyncReason?.name ?: "--",
            )
            DiagnosticLine(
                "Command result",
                diagnostics.recording.lastCommandResultCode?.toString() ?: "--",
            )
            DiagnosticLine(
                "Polling",
                diagnostics.recording.pollingActive.toString(),
            )
            diagnostics.recording.lastHardwareEvent?.let { event ->
                DiagnosticLine(
                    "Hardware event",
                    event.kind.name + "/" + event.source +
                        "/cmd=" + event.command + "/seq=" + event.sequence,
                )
            }
            diagnostics.recording.lastDecodeError?.let {
                DiagnosticLine("Decode error", it)
            }
            diagnostics.recording.lastOperationError?.let {
                DiagnosticLine("Recording error", it)
            }
            HorizontalDivider()
            Text("File list", style = MaterialTheme.typography.titleSmall)
            DiagnosticLine(
                "File session",
                diagnostics.fileList.sessionId?.toString() ?: "--",
            )
            DiagnosticLine(
                "Transport session",
                diagnostics.fileList.transportSessionId?.toString() ?: "--",
            )
            DiagnosticLine(
                "File request seq",
                diagnostics.fileList.requestSequence?.toString() ?: "--",
            )
            DiagnosticLine(
                "File frames",
                diagnostics.fileList.dataFrameCount.toString(),
            )
            DiagnosticLine(
                "Declared/parsed",
                diagnostics.fileList.declaredEntryCount.toString() + "/" +
                    diagnostics.fileList.parsedEntryCount,
            )
            DiagnosticLine(
                "Data RX source",
                diagnostics.fileList.lastDataNotificationSource?.name ?: "--",
            )
            DiagnosticLine(
                "Done RX source",
                diagnostics.fileList.listDoneNotificationSource?.name ?: "--",
            )
            DiagnosticLine(
                "Last data body",
                diagnostics.fileList.lastDataBodySize?.toString() ?: "--",
            )
            DiagnosticLine(
                "Filename field",
                diagnostics.fileList.lastFilenameFieldLength?.toString() ?: "--",
            )
            DiagnosticLine(
                "Done body",
                diagnostics.fileList.listDoneBodySize?.toString() ?: "--",
            )
            DiagnosticLine(
                "List done",
                diagnostics.fileList.receivedListDone.toString(),
            )
            DiagnosticLine(
                "Newest raw filename",
                diagnostics.fileList.newestRawFilename ?: "--",
            )
            DiagnosticLine(
                "Newest resolved filename",
                diagnostics.fileList.newestResolvedFilename ?: "--",
            )
            DiagnosticLine(
                "Newest rawTimeValue",
                diagnostics.fileList.newestRawTimeValue?.toString() ?: "--",
            )
            DiagnosticLine(
                "Newest size bytes",
                diagnostics.fileList.newestSizeBytes?.toString() ?: "--",
            )
            DiagnosticLine(
                "Newest resolution",
                diagnostics.fileList.newestFilenameResolution ?: "--",
            )
            DiagnosticLine(
                "Completion",
                diagnostics.fileList.completionReason?.name ?: "--",
            )
            DiagnosticLine(
                "Session duration",
                diagnostics.fileList.durationMs?.let { it.toString() + " ms" } ?: "--",
            )
            diagnostics.fileList.lastMalformedReason?.let {
                DiagnosticLine("File malformed", it)
            }
            diagnostics.fileList.lastOperationError?.let {
                DiagnosticLine("File error", it)
            }
            HorizontalDivider()
            Text("File transfer", style = MaterialTheme.typography.titleSmall)
            DiagnosticLine(
                "Transfer operation",
                fileTransfer.operationId?.toString() ?: "--",
            )
            DiagnosticLine(
                "Transfer session",
                fileTransfer.transportSessionId?.toString() ?: "--",
            )
            DiagnosticLine(
                "List filename",
                fileTransfer.listFilename ?: "--",
            )
            DiagnosticLine(
                "Request filename",
                fileTransfer.requestFilename ?: "--",
            )
            DiagnosticLine(
                "Request filename bytes",
                fileTransfer.requestFilenameByteLength?.toString() ?: "--",
            )
            DiagnosticLine(
                "Request frame bytes",
                fileTransfer.requestFrameLength?.toString() ?: "--",
            )
            DiagnosticLine(
                "Request seq",
                fileTransfer.requestSequence?.toString() ?: "--",
            )
            DiagnosticLine(
                "Actual filename",
                fileTransfer.actualTransferFilename ?: "--",
            )
            DiagnosticLine(
                "START source",
                fileTransfer.startSource?.name ?: "--",
            )
            DiagnosticLine(
                "DATA source",
                fileTransfer.lastDataSource?.name ?: "--",
            )
            DiagnosticLine(
                "END source",
                fileTransfer.endSource?.name ?: "--",
            )
            DiagnosticLine(
                "DATA frames",
                fileTransfer.dataFrameCount.toString(),
            )
            DiagnosticLine(
                "Expected/received",
                (fileTransfer.expectedBytes?.toString() ?: "--") + "/" +
                    fileTransfer.receivedBytes,
            )
            DiagnosticLine(
                "First data",
                fileTransfer.firstDataPrefixHex ?: "--",
            )
            DiagnosticLine(
                "Container",
                fileTransfer.detectedContainer?.name ?: "--",
            )
            DiagnosticLine(
                "Remote status",
                fileTransfer.remoteStatusCode?.toString() ?: "--",
            )
            fileTransfer.lastError?.let {
                DiagnosticLine(
                    "Transfer error",
                    it.code.name + (it.detail?.let { detail -> ": " + detail } ?: ""),
                )
            }
            HorizontalDivider()
            Text("Remote delete", style = MaterialTheme.typography.titleSmall)
            DiagnosticLine(
                "Delete payload",
                remoteDelete.payloadStrategy ?: "--",
            )
            DiagnosticLine(
                "Delete body bytes",
                remoteDelete.requestBodyLength?.toString() ?: "--",
            )
            DiagnosticLine(
                "Delete response source",
                remoteDelete.responseSource?.name ?: "--",
            )
            DiagnosticLine(
                "Delete status",
                remoteDelete.responseStatusCode?.toString() ?: "--",
            )
            DiagnosticLine(
                "Delete response body",
                remoteDelete.responseBodyHex ?: "--",
            )
            DiagnosticLine(
                "Delete latency",
                remoteDelete.responseLatencyMs?.let { "$it ms" } ?: "--",
            )
            DiagnosticLine(
                "Delete verification",
                remoteDelete.verificationResult ?: "--",
            )
            DiagnosticLine(
                "Delete outcome unknown",
                remoteDelete.outcomeUnknown.toString(),
            )
            remoteDelete.lastError?.let {
                DiagnosticLine(
                    "Delete error",
                    it.code.name + (it.detail?.let { detail -> ": " + detail } ?: ""),
                )
            }
            HorizontalDivider()
            Text("Range probe", style = MaterialTheme.typography.titleSmall)
            DiagnosticLine(
                "Range",
                if (rangeProbe.startOffset != null && rangeProbe.requestedEnd != null) {
                    rangeProbe.startOffset.toString() + ".." + rangeProbe.requestedEnd
                } else {
                    "--"
                },
            )
            DiagnosticLine("Range received", rangeProbe.receivedBytes.toString())
            DiagnosticLine(
                "Range actual filename",
                rangeProbe.actualTransferFilename ?: "--",
            )
            DiagnosticLine(
                "Range first data",
                rangeProbe.firstDataPrefixHex ?: "--",
            )
            DiagnosticLine(
                "Range matches local",
                rangeProbe.matchesLocalBytes?.toString() ?: "--",
            )
            DiagnosticLine(
                "Range end semantics",
                rangeProbe.inferredEndSemantics ?: "--",
            )
            DiagnosticLine(
                "Range remote status",
                rangeProbe.remoteStatusCode?.toString() ?: "--",
            )
            rangeProbe.lastError?.let {
                DiagnosticLine(
                    "Range error",
                    it.code.name + (it.detail?.let { detail -> ": " + detail } ?: ""),
                )
            }
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
    val scope = rememberCoroutineScope()
    var runtimeProbeRunning by remember { mutableStateOf(false) }
    var runtimeProbeResult by remember { mutableStateOf<String?>(null) }
    val runtimeAvailableText = stringResource(R.string.settings_sherpa_runtime_available)
    val runtimeUnavailableText = stringResource(R.string.settings_sherpa_runtime_unavailable)

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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_local_ai_runtime),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        R.string.settings_sherpa_runtime_version,
                        SherpaRuntime.RUNTIME_VERSION,
                    ),
                )
                Button(
                    enabled = !runtimeProbeRunning,
                    onClick = {
                        runtimeProbeRunning = true
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                SherpaRuntime.probeNativeLoad()
                            }
                            runtimeProbeResult =
                                if (result.available) {
                                    runtimeAvailableText
                                } else {
                                    runtimeUnavailableText +
                                        (result.error?.let { ": $it" } ?: "")
                                }
                            runtimeProbeRunning = false
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (runtimeProbeRunning) {
                                R.string.settings_sherpa_runtime_testing
                            } else {
                                R.string.settings_sherpa_runtime_test
                            },
                        ),
                    )
                }
                runtimeProbeResult?.let { result ->
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
