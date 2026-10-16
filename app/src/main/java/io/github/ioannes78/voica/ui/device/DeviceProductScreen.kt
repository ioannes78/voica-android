package io.github.ioannes78.voica.ui.device

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.PhoneMicrophoneProductTarget
import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.RecordingCommandState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.DeviceActionMessage
import io.github.ioannes78.voica.ui.DeviceViewModel
import io.github.ioannes78.voica.ui.files.CompactDeviceFilesScreen
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRealtimeRuntime
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRecordingRuntime
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRealtimeScreen
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingScreen
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingUiController
import io.github.ioannes78.voica.ui.realtime.LocalStage16ARealtimeRuntime
import io.github.ioannes78.voica.ui.realtime.PhoneMicrophoneRealtimeUiController
import io.github.ioannes78.voica.ui.realtime.RecorderCardRealtimeUiController
import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionScreen
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityDestination
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivitySource
import kotlinx.coroutines.launch

private enum class DevicePage {
    HOME,
    REALTIME,
    PHONE_RECORDING,
    PHONE_REALTIME,
    FILES,
    ALL_DEVICES,
    DIAGNOSTICS,
}

@Composable
fun DeviceProductScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
    homeRequestToken: Int,
    onSecondaryPageChanged: (Boolean) -> Unit,
) {
    val scan by viewModel.scanState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val info by viewModel.deviceInfo.collectAsState()
    val recording by viewModel.recordingState.collectAsState()
    val deviceFiles by viewModel.deviceFileListState.collectAsState()
    val libraryRecordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
    val diagnostics by viewModel.diagnostics.collectAsState()
    val fileTransferDiagnostics by viewModel.fileTransferDiagnostics.collectAsState()
    val remoteDeleteDiagnostics by viewModel.remoteDeleteDiagnostics.collectAsState()
    val rangeProbeDiagnostics by viewModel.rangeProbeDiagnostics.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val batchDeleteState by viewModel.remoteDeleteBatchState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val realtimeRuntime = LocalStage16ARealtimeRuntime.current
    val realtimeState by realtimeRuntime.state.collectAsState()

    val phoneRecordingRuntime = LocalPhoneMicrophoneRecordingRuntime.current
    val phoneRealtimeRuntime = LocalPhoneMicrophoneRealtimeRuntime.current
    val phoneUiScope = rememberCoroutineScope()
    val recorderRealtimeController = remember(realtimeRuntime) { RecorderCardRealtimeUiController(realtimeRuntime) }
    val phoneRecordingController =
        remember(phoneRecordingRuntime, phoneRealtimeRuntime, phoneUiScope) {
            PhoneMicrophoneRecordingUiController(
                recordingRuntime = phoneRecordingRuntime,
                realtimeRuntime = phoneRealtimeRuntime,
                scope = phoneUiScope,
            )
        }
    val phoneRealtimeController =
        remember(phoneRealtimeRuntime, phoneRecordingRuntime, phoneUiScope) {
            PhoneMicrophoneRealtimeUiController(
                runtime = phoneRealtimeRuntime,
                captureState = phoneRecordingRuntime.captureState,
                scope = phoneUiScope,
            )
        }
    val phoneRecordingUiState by phoneRecordingController.state.collectAsState()
    val phoneRealtimeState by phoneRealtimeController.state.collectAsState()

    var page by rememberSaveable { mutableStateOf(DevicePage.HOME) }
    var homeSourcePreference by rememberSaveable {
        mutableStateOf(HomeActivitySourcePreference.RECORDER_CARD)
    }
    var showDevicePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(page) {
        onSecondaryPageChanged(page != DevicePage.HOME)
    }

    LaunchedEffect(homeRequestToken) {
        if (homeRequestToken > 0) {
            page = DevicePage.HOME
        }
    }

    LaunchedEffect(connection) {
        if (connection is DeviceConnectionState.PermissionRequired) {
            viewModel.refreshPermissions()
        }
        if (connection is DeviceConnectionState.Ready) {
            showDevicePicker = false
        }
    }

    LaunchedEffect(actionMessage) {
        val message = actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            if (message == DeviceActionMessage.SYNC_SENT) {
                "设备时间同步成功"
            } else {
                "设备时间同步失败"
            },
        )
        viewModel.consumeActionMessage()
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

    val compatibleDevices =
        scan.devices.filter { it.advertisesAe20 || it.likelyQs668 }
    val otherDevices =
        scan.devices.filterNot { it.advertisesAe20 || it.likelyQs668 }
    val recorderAvailable = connection is DeviceConnectionState.Ready
    val effectiveSourcePreference =
        if (recorderAvailable) {
            homeSourcePreference
        } else {
            HomeActivitySourcePreference.PHONE_MICROPHONE
        }
    val canStartDeviceRecording =
        recorderAvailable &&
            recording.status == RecordingStatus.Idle &&
            recording.freshness == RecordingFreshness.FRESH &&
            recording.commandState == RecordingCommandState.IDLE
    val canStartRealtime = canStartDeviceRecording
    val canStartPhoneRecording =
        !phoneRecordingUiState.ownedByRealtime &&
            phoneRecordingUiState.recording.phase in PHONE_MIC_STARTABLE_PHASES
    val canStartPhoneRealtime = canStartPhoneRecording
    val canStartHomeRecording =
        when (effectiveSourcePreference) {
            HomeActivitySourcePreference.RECORDER_CARD -> canStartDeviceRecording
            HomeActivitySourcePreference.PHONE_MICROPHONE -> canStartPhoneRecording
        }
    val canStartHomeRealtime =
        when (effectiveSourcePreference) {
            HomeActivitySourcePreference.RECORDER_CARD -> canStartRealtime
            HomeActivitySourcePreference.PHONE_MICROPHONE -> canStartPhoneRealtime
        }
    val homeProjection =
        buildHomeActivityProjection(
            deviceRecording = recording,
            deviceRealtime = realtimeState,
            phoneRecording = phoneRecordingUiState,
            phoneRealtime = phoneRealtimeState,
            sourcePreference = effectiveSourcePreference,
        )

    fun showActionMessage(message: String?) {
        if (message.isNullOrBlank()) return
        phoneUiScope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun launchRealtimeAction(
        source: AppActivitySource,
        action: AppActivityAction,
    ) {
        val controller =
            when (source) {
                AppActivitySource.RECORDER_CARD -> recorderRealtimeController
                AppActivitySource.PHONE_MICROPHONE -> phoneRealtimeController
                else -> return
            }
        phoneUiScope.launch {
            val outcome =
                when (action) {
                    AppActivityAction.PAUSE -> controller.pause()
                    AppActivityAction.RESUME -> controller.resume()
                    AppActivityAction.STOP -> controller.stop()
                    else -> return@launch
                }
            if (!outcome.success || !outcome.message.isNullOrBlank()) {
                showActionMessage(outcome.message)
            }
        }
    }

    fun openProjectedActivity(activity: AppActivityFact) {
        when (val destination = activity.destination) {
            AppActivityDestination.DeviceRealtime -> page = DevicePage.REALTIME
            is AppActivityDestination.PhoneMicrophone -> {
                page =
                    when (destination.target) {
                        PhoneMicrophoneProductTarget.RECORDING -> DevicePage.PHONE_RECORDING
                        PhoneMicrophoneProductTarget.REALTIME -> DevicePage.PHONE_REALTIME
                    }
            }
            is AppActivityDestination.RecordingDetail,
            null,
            -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (page) {
            DevicePage.HOME -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        VoicaBrandHeader(
                            connected = recorderAvailable,
                            onRefresh = viewModel::refreshDeviceInfo,
                            onSyncTime = viewModel::syncTime,
                            onOpenFiles = { page = DevicePage.FILES },
                            onOpenDiagnostics = { page = DevicePage.DIAGNOSTICS },
                        )
                    }

                    item {
                        CompactHomeDeviceStatus(
                            connection = connection,
                            info = info,
                            isScanning = scan.isScanning,
                            discoveredCount = scan.devices.size,
                            missingPermissions = missingPermissions,
                            onScan = {
                                showDevicePicker = true
                                viewModel.startScan()
                            },
                            onStopScan = {
                                showDevicePicker = false
                                viewModel.stopScan()
                            },
                            onRequestPermissions = {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            },
                            onDisconnect = viewModel::disconnect,
                        )
                    }

                    item {
                        HomePhoneMicrophonePermissionGate(
                            onPermissionReady = { action ->
                                when (action) {
                                    HomePhoneMicrophoneStartAction.RECORDING -> {
                                        phoneUiScope.launch {
                                            val outcome = phoneRecordingController.start()
                                            if (!outcome.success || !outcome.message.isNullOrBlank()) {
                                                showActionMessage(outcome.message)
                                            }
                                        }
                                    }
                                    HomePhoneMicrophoneStartAction.REALTIME -> {
                                        phoneUiScope.launch {
                                            val outcome = phoneRealtimeController.start()
                                            if (!outcome.success || !outcome.message.isNullOrBlank()) {
                                                showActionMessage(outcome.message)
                                            }
                                        }
                                    }
                                }
                            },
                        ) { requestPhoneStart, permissionMessage ->
                            HomeActivityPanel(
                                projection = homeProjection,
                                sourcePreference = effectiveSourcePreference,
                                recorderAvailable = recorderAvailable,
                                canStartRecording = canStartHomeRecording,
                                canStartRealtime = canStartHomeRealtime,
                                permissionMessage =
                                    if (effectiveSourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE) {
                                        permissionMessage
                                    } else {
                                        null
                                    },
                                onSourcePreferenceChanged = { homeSourcePreference = it },
                                onStartRecording = {
                                    when (effectiveSourcePreference) {
                                        HomeActivitySourcePreference.RECORDER_CARD -> viewModel.startRecording()
                                        HomeActivitySourcePreference.PHONE_MICROPHONE ->
                                            requestPhoneStart(HomePhoneMicrophoneStartAction.RECORDING)
                                    }
                                },
                                onStartRealtime = {
                                    when (effectiveSourcePreference) {
                                        HomeActivitySourcePreference.RECORDER_CARD -> {
                                            phoneUiScope.launch {
                                                val outcome = recorderRealtimeController.start()
                                                if (!outcome.success || !outcome.message.isNullOrBlank()) {
                                                    showActionMessage(outcome.message)
                                                }
                                            }
                                        }
                                        HomeActivitySourcePreference.PHONE_MICROPHONE ->
                                            requestPhoneStart(HomePhoneMicrophoneStartAction.REALTIME)
                                    }
                                },
                                onAction = { activity, action ->
                                    when {
                                        activity.source == AppActivitySource.RECORDER_CARD &&
                                            activity.kind == AppActivityKind.RECORDING -> {
                                            when (action) {
                                                AppActivityAction.PAUSE -> viewModel.pauseRecording()
                                                AppActivityAction.RESUME -> viewModel.resumeRecording()
                                                AppActivityAction.STOP -> viewModel.saveRecording()
                                                else -> Unit
                                            }
                                        }
                                        activity.source == AppActivitySource.PHONE_MICROPHONE &&
                                            activity.kind == AppActivityKind.RECORDING -> {
                                            phoneUiScope.launch {
                                                val outcome =
                                                    when (action) {
                                                        AppActivityAction.PAUSE -> phoneRecordingController.pause()
                                                        AppActivityAction.RESUME -> phoneRecordingController.resume()
                                                        AppActivityAction.STOP -> phoneRecordingController.stop()
                                                        else -> return@launch
                                                    }
                                                if (!outcome.success || !outcome.message.isNullOrBlank()) {
                                                    showActionMessage(outcome.message)
                                                }
                                            }
                                        }
                                        activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION ->
                                            launchRealtimeAction(activity.source, action)
                                    }
                                },
                                onOpenActivity = ::openProjectedActivity,
                                onSelectSecondaryActivity = { activity ->
                                    homeSourcePreference =
                                        when (activity.source) {
                                            AppActivitySource.RECORDER_CARD ->
                                                HomeActivitySourcePreference.RECORDER_CARD
                                            AppActivitySource.PHONE_MICROPHONE ->
                                                HomeActivitySourcePreference.PHONE_MICROPHONE
                                            else -> effectiveSourcePreference
                                        }
                                },
                            )
                        }
                    }

                    if (recorderAvailable) {
                        item {
                            DeviceFilesEntry(
                                count = deviceFiles.files.size,
                                latestName = deviceFiles.files.firstOrNull()?.displayFilename,
                                onClick = { page = DevicePage.FILES },
                            )
                        }
                    }
                }

                HomeDeviceScanSheet(
                    visible = showDevicePicker,
                    isScanning = scan.isScanning,
                    compatibleDevices = compatibleDevices,
                    otherDevices = otherDevices,
                    onDismiss = {
                        showDevicePicker = false
                        if (scan.isScanning) viewModel.stopScan()
                    },
                    onConnect = viewModel::connect,
                    onRescan = viewModel::startScan,
                )
            }

            DevicePage.REALTIME -> {
                RealtimeTranscriptionScreen(
                    padding = padding,
                    runtime = realtimeRuntime,
                    canStart = canStartRealtime,
                    onBack = { page = DevicePage.HOME },
                )
            }

            DevicePage.PHONE_RECORDING -> {
                PhoneMicrophoneRecordingScreen(
                    padding = padding,
                    controller = phoneRecordingController,
                    onBack = { page = DevicePage.HOME },
                )
            }

            DevicePage.PHONE_REALTIME -> {
                PhoneMicrophoneRealtimeScreen(
                    padding = padding,
                    controller = phoneRealtimeController,
                    canStart = canStartPhoneRealtime,
                    onBack = { page = DevicePage.HOME },
                )
            }

            DevicePage.FILES -> {
                val operation by viewModel.fileOperationState.collectAsState()
                val canRefresh =
                    recording.status == RecordingStatus.Idle &&
                        recording.freshness == RecordingFreshness.FRESH &&
                        recording.commandState == RecordingCommandState.IDLE &&
                        deviceFiles.freshness != FileListFreshness.LOADING &&
                        operation !is FileOperationState.Active
                CompactDeviceFilesScreen(
                    padding = padding,
                    state = deviceFiles,
                    operationState = operation,
                    batchDeleteState = batchDeleteState,
                    localRecordings = libraryRecordings,
                    canRefresh = canRefresh,
                    onBack = { page = DevicePage.HOME },
                    onRefresh = viewModel::refreshDeviceFiles,
                    onDownload = viewModel::downloadDeviceFile,
                    onCancelDownload = viewModel::cancelDeviceFileDownload,
                    onDeleteRemote = viewModel::deleteRemoteRecording,
                    onDeleteSelected = viewModel::deleteRemoteRecordings,
                    onDismissBatchResult = viewModel::dismissRemoteDeleteBatchResult,
                    onRangeProbe = viewModel::runRangeProbe,
                )
            }

            DevicePage.ALL_DEVICES -> {
                LazyColumn(
                    modifier =
                        Modifier
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
                    if (compatibleDevices.isNotEmpty()) {
                        item {
                            Text(
                                "兼容录音设备",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        items(compatibleDevices, key = { it.address }) { device ->
                            CompactScanDeviceRow(
                                device = device,
                                onConnect = {
                                    page = DevicePage.HOME
                                    viewModel.connect(it)
                                },
                            )
                        }
                    }
                    if (otherDevices.isNotEmpty()) {
                        item {
                            Text(
                                "其它蓝牙设备",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(otherDevices, key = { it.address }) { device ->
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
            }

            DevicePage.DIAGNOSTICS -> {
                FullDiagnosticsScreen(
                    padding = padding,
                    diagnostics = diagnostics,
                    fileTransfer = fileTransferDiagnostics,
                    remoteDelete = remoteDeleteDiagnostics,
                    rangeProbe = rangeProbeDiagnostics,
                    onBack = { page = DevicePage.HOME },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
        modifier =
            Modifier
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

private val PHONE_MIC_STARTABLE_PHASES =
    setOf(
        PhoneMicrophoneRecordingPhase.IDLE,
        PhoneMicrophoneRecordingPhase.COMPLETED,
        PhoneMicrophoneRecordingPhase.EMPTY,
        PhoneMicrophoneRecordingPhase.FAILED,
    )
