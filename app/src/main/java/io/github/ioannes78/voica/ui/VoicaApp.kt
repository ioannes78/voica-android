package io.github.ioannes78.voica.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.AiSummaryCoordinator
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.DiarizationCoordinator
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.ModelUpdateController
import io.github.ioannes78.voica.LocalAudioImportCoordinator
import io.github.ioannes78.voica.LocalAudioExportCoordinator
import io.github.ioannes78.voica.LocalAudioShareOutcome
import io.github.ioannes78.voica.AudioExportVariant
import io.github.ioannes78.voica.LocalRecordingDeleteCoordinator
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.StorageManagementCoordinator
import io.github.ioannes78.voica.TranscriptionCoordinator
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
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
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderConfigurationRepository
import io.github.ioannes78.voica.llm.ProviderProfileStore
import io.github.ioannes78.voica.protocol.BatteryState
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.ui.ai.AiSummaryCard
import io.github.ioannes78.voica.ui.ai.AiSummaryViewModel
import io.github.ioannes78.voica.ui.ai.ProviderSettingsCard
import io.github.ioannes78.voica.ui.ai.ProviderSettingsViewModel
import io.github.ioannes78.voica.ui.diarization.DiarizationStatusCard
import io.github.ioannes78.voica.ui.diarization.DiarizationViewModel
import io.github.ioannes78.voica.ui.files.DeviceFilesCard
import io.github.ioannes78.voica.ui.device.CompactScanDeviceRow
import io.github.ioannes78.voica.ui.device.DeviceProductScreen
import io.github.ioannes78.voica.ui.device.DeviceStatusPanel
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import io.github.ioannes78.voica.ui.library.RecordingDetailScreen
import io.github.ioannes78.voica.ui.library.RecordingLibraryRoute
import io.github.ioannes78.voica.ui.library.RecordingLibraryViewModel
import io.github.ioannes78.voica.ui.playback.PlaybackCard
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.playback.formatPlaybackTime
import io.github.ioannes78.voica.ui.recording.GlobalRecordingStatusBar
import io.github.ioannes78.voica.ui.recording.RecordingCard
import io.github.ioannes78.voica.ui.settings.ProductSettingsScreen
import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader
import io.github.ioannes78.voica.ui.transcript.TranscriptFollowMode
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptSegmentCard
import io.github.ioannes78.voica.ui.transcript.TranscriptVersionListCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionStatusCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel
import io.github.ioannes78.voica.ui.theme.ThemeSettingsCard
import io.github.ioannes78.voica.ui.theme.ThemeSettingsStore
import io.github.ioannes78.voica.ui.model.ModelManagerCard
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GlobalRecordingOpenRequest(
    val token: Int,
    val recordingId: String,
    val destination: RecordingDetailDestination,
)

private data class GlobalTaskItem(
    val recordingId: String,
    val recordingName: String,
    val label: String,
    val progress: String?,
    val destination: RecordingDetailDestination,
)

@Composable
fun VoicaApp(
    repository: DeviceRepository,
    recordingLibraryRepository: RecordingLibraryRepository,
    canonicalAudioCoordinator: CanonicalAudioCoordinator,
    localAudioImportCoordinator: LocalAudioImportCoordinator,
    localRecordingDeleteCoordinator: LocalRecordingDeleteCoordinator,
    localAudioExportCoordinator: LocalAudioExportCoordinator,
    storageManagementCoordinator: StorageManagementCoordinator,
    playbackController: PlaybackController,
    modelManager: ModelManager,
    modelUpdateController: ModelUpdateController,
    transcriptionCoordinator: TranscriptionCoordinator,
    transcriptionRepository: TranscriptionRepository,
    diarizationCoordinator: DiarizationCoordinator,
    diarizationRepository: DiarizationRepository,
    aiSummaryCoordinator: AiSummaryCoordinator,
    aiSummaryRepository: AiSummaryRepository,
    providerProfileStore: ProviderProfileStore,
    providerConfigurationRepository: ProviderConfigurationRepository,
    providerAdapterRegistry: ProviderAdapterRegistry,
    themeSettingsStore: ThemeSettingsStore,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var secondaryPageActive by rememberSaveable { mutableStateOf(false) }
    var deviceHomeRequest by rememberSaveable { mutableIntStateOf(0) }
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
    val recordingLibraryViewModel: RecordingLibraryViewModel = viewModel(
        factory =
            remember(
                recordingLibraryRepository,
                localAudioImportCoordinator,
                localRecordingDeleteCoordinator,
                localAudioExportCoordinator,
            ) {
                RecordingLibraryViewModel.Factory(
                    repository = recordingLibraryRepository,
                    importCoordinator = localAudioImportCoordinator,
                    deleteCoordinator = localRecordingDeleteCoordinator,
                    exportCoordinator = localAudioExportCoordinator,
                )
            },
    )
    val playbackViewModel: PlaybackViewModel = viewModel(
        factory = remember(
            playbackController,
            localRecordingDeleteCoordinator,
        ) {
            PlaybackViewModel.Factory(
                playbackController,
                localRecordingDeleteCoordinator,
            )
        },
    )

    val diarizationViewModel: DiarizationViewModel = viewModel(
        factory = remember(diarizationCoordinator) {
            DiarizationViewModel.Factory(diarizationCoordinator)
        },
    )

    val transcriptionViewModel: TranscriptionViewModel = viewModel(
        factory = remember(
            transcriptionCoordinator,
            transcriptionRepository,
            diarizationCoordinator,
            diarizationRepository,
            recordingLibraryRepository,
        ) {
            TranscriptionViewModel.Factory(
                transcriptionCoordinator,
                transcriptionRepository,
                diarizationCoordinator,
                diarizationRepository,
                recordingLibraryRepository,
            )
        },
    )
    val transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel = viewModel(
        factory = remember(playbackController) {
            TranscriptPlaybackSyncViewModel.Factory(playbackController)
        },
    )

    val aiSummaryViewModel: AiSummaryViewModel = viewModel(
        factory = remember(
            aiSummaryCoordinator,
            aiSummaryRepository,
            providerProfileStore,
        ) {
            AiSummaryViewModel.Factory(
                coordinator = aiSummaryCoordinator,
                repository = aiSummaryRepository,
                profileStore = providerProfileStore,
            )
        },
    )
    val providerSettingsViewModel: ProviderSettingsViewModel = viewModel(
        factory = remember(
            providerProfileStore,
            providerConfigurationRepository,
            providerAdapterRegistry,
        ) {
            ProviderSettingsViewModel.Factory(
                profileStore = providerProfileStore,
                configurationRepository = providerConfigurationRepository,
                providerRegistry = providerAdapterRegistry,
            )
        },
    )

    val globalRecording by deviceViewModel.recordingState.collectAsState()
    val libraryRecordings by deviceViewModel.libraryRecordings.collectAsState(initial = emptyList())
    val globalTranscription by transcriptionViewModel.runState.collectAsState()
    val globalDiarization by diarizationViewModel.runState.collectAsState()
    val globalAiSummary by aiSummaryViewModel.runState.collectAsState()
    val globalPlayback by playbackViewModel.snapshot.collectAsState()
    var openRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var libraryOpenRequest by remember { mutableStateOf<GlobalRecordingOpenRequest?>(null) }

    val globalTasks =
        buildGlobalTaskItems(
            transcription = globalTranscription,
            diarization = globalDiarization,
            aiSummary = globalAiSummary,
            recordings = libraryRecordings,
        )
    val requestOpenRecording: (String, RecordingDetailDestination) -> Unit =
        { recordingId, destination ->
            openRequestToken += 1
            libraryOpenRequest =
                GlobalRecordingOpenRequest(
                    token = openRequestToken,
                    recordingId = recordingId,
                    destination = destination,
                )
            secondaryPageActive = false
            selectedTab = 1
        }

    val navigationColors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    Scaffold(
        topBar = {
            Column {
                if (selectedTab != 0 || secondaryPageActive) {
                    GlobalRecordingStatusBar(
                        state = globalRecording,
                        onClick = {
                            secondaryPageActive = false
                            selectedTab = 0
                            deviceHomeRequest += 1
                        },
                    )
                }
                if (globalTasks.isNotEmpty()) {
                    GlobalTaskStatusBar(
                        tasks = globalTasks,
                        onOpen = requestOpenRecording,
                    )
                }
            }
        },
        bottomBar = {
            if (!secondaryPageActive) {
                Column {
                    if (
                        globalPlayback.recordingId != null &&
                        globalPlayback.state != PlaybackState.IDLE &&
                        globalPlayback.state != PlaybackState.RELEASED
                    ) {
                        GlobalPlaybackStatusBar(
                            snapshot = globalPlayback,
                            recordingName =
                                libraryRecordings.firstOrNull {
                                    it.id == globalPlayback.recordingId
                                }?.displayName,
                            onPlay = playbackViewModel::play,
                            onPause = playbackViewModel::pause,
                            onOpen = {
                                globalPlayback.recordingId?.let { id ->
                                    requestOpenRecording(
                                        id,
                                        RecordingDetailDestination.PLAYBACK,
                                    )
                                }
                            },
                        )
                    }
                    NavigationBar(modifier = Modifier.height(64.dp)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            secondaryPageActive = false
                            selectedTab = 0
                            deviceHomeRequest += 1
                        },
                        icon = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_device)) },
                        colors = navigationColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            secondaryPageActive = false
                            selectedTab = 1
                        },
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_library)) },
                        colors = navigationColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            secondaryPageActive = false
                            selectedTab = 2
                        },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                        colors = navigationColors,
                    )
                    }
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DeviceProductScreen(
                padding = padding,
                viewModel = deviceViewModel,
                homeRequestToken = deviceHomeRequest,
                onSecondaryPageChanged = { secondaryPageActive = it },
            )
            1 -> LocalFilesScreen(
                padding,
                deviceViewModel,
                recordingLibraryViewModel,
                playbackViewModel,
                localAudioExportCoordinator,
                transcriptionViewModel,
                diarizationViewModel,
                transcriptPlaybackSyncViewModel,
                aiSummaryViewModel,
                openRequest = libraryOpenRequest,
                onOpenRequestConsumed = { request ->
                    if (libraryOpenRequest?.token == request.token) {
                        libraryOpenRequest = null
                    }
                },
                onOpenSettings = {
                    secondaryPageActive = false
                    selectedTab = 2
                },
                onSecondaryPageChanged = { secondaryPageActive = it },
            )
            else -> ProductSettingsScreen(
                padding = padding,
                modelManager = modelManager,
                modelUpdateController = modelUpdateController,
                storageManagementCoordinator = storageManagementCoordinator,
                providerSettingsViewModel = providerSettingsViewModel,
                themeSettingsStore = themeSettingsStore,
                onSecondaryPageChanged = { secondaryPageActive = it },
            )
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.device_screen_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.device_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            scan.error?.let { error ->
                item {
                    Text(
                        errorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(scan.devices, key = { it.address }) { device ->
                CompactScanDeviceRow(
                    device = device,
                    onConnect = viewModel::connect,
                )
            }
        }

        if (connection is DeviceConnectionState.Ready) {
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

private data class PendingDetailAudioExport(
    val recordingId: String,
    val variant: AudioExportVariant,
)

@Composable
private fun LocalFilesScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
    recordingLibraryViewModel: RecordingLibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    localAudioExportCoordinator: LocalAudioExportCoordinator,
    transcriptionViewModel: TranscriptionViewModel,
    diarizationViewModel: DiarizationViewModel,
    transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel,
    aiSummaryViewModel: AiSummaryViewModel,
    openRequest: GlobalRecordingOpenRequest?,
    onOpenRequestConsumed: (GlobalRecordingOpenRequest) -> Unit,
    onOpenSettings: () -> Unit,
    onSecondaryPageChanged: (Boolean) -> Unit,
) {
    val recordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
    var selectedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedDestination by remember {
        mutableStateOf(RecordingDetailDestination.PLAYBACK)
    }
    val selectedRecording =
        selectedRecordingId?.let { id ->
            recordings.firstOrNull { it.id == id }
        }
    val deviceRecording by viewModel.recordingState.collectAsState()
    val context = LocalContext.current
    val actionScope = rememberCoroutineScope()
    var pendingSafExport by remember {
        mutableStateOf<PendingDetailAudioExport?>(null)
    }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val pending = pendingSafExport
            pendingSafExport = null
            val destination = result.data?.data
            if (
                result.resultCode == Activity.RESULT_OK &&
                pending != null &&
                destination != null
            ) {
                actionScope.launch {
                    val exported =
                        localAudioExportCoordinator.exportToUri(
                            recordingId = pending.recordingId,
                            destinationUri = destination,
                            variant = pending.variant,
                        )
                    Toast.makeText(
                        context,
                        if (exported.exported) {
                            "已导出“" + (exported.displayName ?: "录音") + "”"
                        } else {
                            exported.error ?: "导出失败"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    val exportDetailAudio: (String, AudioExportVariant) -> Unit =
        { recordingId, variant ->
            actionScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val result =
                        localAudioExportCoordinator.exportToDownloads(
                            recordingIds = listOf(recordingId),
                            variant = variant,
                        )
                    val item = result.items.firstOrNull()
                    Toast.makeText(
                        context,
                        if (item?.exported == true) {
                            "已导出到 Downloads/Voica"
                        } else {
                            item?.error ?: "导出失败"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val descriptor =
                        localAudioExportCoordinator.describe(
                            recordingId = recordingId,
                            variant = variant,
                        )
                    if (descriptor == null) {
                        Toast.makeText(
                            context,
                            "没有可导出的音频",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        pendingSafExport =
                            PendingDetailAudioExport(
                                recordingId = recordingId,
                                variant = variant,
                            )
                        createDocumentLauncher.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = descriptor.mimeType
                                putExtra(Intent.EXTRA_TITLE, descriptor.displayName)
                            },
                        )
                    }
                }
            }
        }

    val shareDetailAudio: (String, AudioExportVariant) -> Unit =
        { recordingId, variant ->
            actionScope.launch {
                when (
                    val outcome =
                        localAudioExportCoordinator.prepareShare(
                            recordingId = recordingId,
                            variant = variant,
                        )
                ) {
                    is LocalAudioShareOutcome.Ready ->
                        context.startActivity(outcome.intent)

                    is LocalAudioShareOutcome.Failed ->
                        Toast.makeText(
                            context,
                            outcome.reason,
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }

    LaunchedEffect(openRequest?.token) {
        val request = openRequest ?: return@LaunchedEffect
        requestedDestination = request.destination
        selectedRecordingId = request.recordingId
        onOpenRequestConsumed(request)
    }

    LaunchedEffect(selectedRecording != null) {
        onSecondaryPageChanged(selectedRecording != null)
    }

    if (selectedRecording == null) {
        RecordingLibraryRoute(
            padding = padding,
            viewModel = recordingLibraryViewModel,
            onOpenRecording = {
                requestedDestination = RecordingDetailDestination.PLAYBACK
                selectedRecordingId = it
            },
        )
    } else {
        RecordingDetailScreen(
            padding = padding,
            recording = selectedRecording,
            playbackViewModel = playbackViewModel,
            transcriptionViewModel = transcriptionViewModel,
            diarizationViewModel = diarizationViewModel,
            transcriptPlaybackSyncViewModel = transcriptPlaybackSyncViewModel,
            aiSummaryViewModel = aiSummaryViewModel,
            onBack = {
                selectedRecordingId = null
                requestedDestination = RecordingDetailDestination.PLAYBACK
            },
            onOpenSettings = onOpenSettings,
            onRename = viewModel::renameLocalRecording,
            onDelete = playbackViewModel::deleteRecording,
            onExportCanonical = {
                exportDetailAudio(it, AudioExportVariant.CANONICAL_WAV)
            },
            onExportOriginal = {
                exportDetailAudio(it, AudioExportVariant.ORIGINAL)
            },
            onShareCanonical = {
                shareDetailAudio(it, AudioExportVariant.CANONICAL_WAV)
            },
            onShareOriginal = {
                shareDetailAudio(it, AudioExportVariant.ORIGINAL)
            },
            onGenerateCanonical = viewModel::generateCanonicalAudio,
            onCancelCanonical = viewModel::cancelCanonicalAudio,
            deviceRecordingActive =
                deviceRecording.status == RecordingStatus.Recording ||
                    deviceRecording.status == RecordingStatus.Paused,
            initialDestination = requestedDestination,
        )
    }
}

private fun buildGlobalTaskItems(
    transcription: TranscriptionRunState,
    diarization: DiarizationRunState,
    aiSummary: AiSummaryRunState,
    recordings: List<RecordingLibraryItem>,
): List<GlobalTaskItem> {
    fun recordingName(recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName
            ?: "录音"

    return buildList {
        (transcription as? TranscriptionRunState.Running)?.let { state ->
            val progress =
                state.progress.fraction?.let {
                    (it * 100.0).roundToInt().coerceIn(0, 100).toString() + "%"
                }
            add(
                GlobalTaskItem(
                    recordingId = state.recordingId,
                    recordingName = recordingName(state.recordingId),
                    label = "转写中",
                    progress = progress,
                    destination = RecordingDetailDestination.TRANSCRIPT,
                ),
            )
        }
        (diarization as? DiarizationRunState.Running)?.let { state ->
            val progress =
                state.progress.fraction?.let {
                    (it * 100.0).roundToInt().coerceIn(0, 100).toString() + "%"
                }
            add(
                GlobalTaskItem(
                    recordingId = state.recordingId,
                    recordingName = recordingName(state.recordingId),
                    label = "说话人分离",
                    progress = progress,
                    destination = RecordingDetailDestination.TRANSCRIPT,
                ),
            )
        }
        (aiSummary as? AiSummaryRunState.Running)
            ?.recordingId
            ?.let { recordingId ->
                val state = aiSummary as AiSummaryRunState.Running
                val progress =
                    if (state.totalUnits > 0) {
                        ((state.completedUnits.toDouble() / state.totalUnits.toDouble()) * 100.0)
                            .roundToInt()
                            .coerceIn(0, 100)
                            .toString() + "%"
                    } else {
                        when (state.phase.name) {
                            "PREPARING" -> "准备中"
                            "ANALYZING" -> "分析中"
                            "MAPPING" -> "分段总结"
                            "REDUCING" -> "合并总结"
                            "VALIDATING" -> "验证结果"
                            else -> null
                        }
                    }
                add(
                    GlobalTaskItem(
                        recordingId = recordingId,
                        recordingName = recordingName(recordingId),
                        label = "AI 总结",
                        progress = progress,
                        destination = RecordingDetailDestination.SUMMARY,
                    ),
                )
            }
    }
}

@Composable
private fun GlobalTaskStatusBar(
    tasks: List<GlobalTaskItem>,
    onOpen: (String, RecordingDetailDestination) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                if (tasks.size == 1) "正在处理" else tasks.size.toString() + " 个任务正在处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tasks.forEach { task ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpen(task.recordingId, task.destination)
                            }
                            .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        task.label + " · " + task.recordingName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    task.progress?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun GlobalPlaybackStatusBar(
    snapshot: PlaybackSnapshot,
    recordingName: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    if (snapshot.state == PlaybackState.PLAYING) {
                        onPause()
                    } else {
                        onPlay()
                    }
                },
            ) {
                Text(if (snapshot.state == PlaybackState.PLAYING) "暂停" else "播放")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recordingName ?: "当前录音",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    formatPlaybackTime(snapshot.positionSampleIndex) +
                        " / " +
                        formatPlaybackTime(snapshot.durationSampleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PlaybackSlot(
    playbackViewModel: PlaybackViewModel,
    recordings: List<RecordingLibraryItem>,
) {
    val playback by playbackViewModel.snapshot.collectAsState()
    val recordingId = playback.recordingId ?: return
    val playbackName =
        recordings.firstOrNull { it.id == recordingId }?.displayName
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

private const val TRANSCRIPT_ROW_START_INDEX = 7

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
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info.name ?: "CB08 / QS668",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        info.address ?: connection.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceMetric(
                    value = batteryText(info.battery),
                    label = stringResource(R.string.device_metric_battery),
                    modifier = Modifier.weight(1f),
                )
                DeviceMetric(
                    value = formatCapacity(info.remainingKb),
                    label = stringResource(R.string.device_metric_remaining),
                    modifier = Modifier.weight(1f),
                )
                DeviceMetric(
                    value = info.firmwareVersion ?: "--",
                    label = stringResource(R.string.device_metric_firmware),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
                OutlinedButton(onClick = onSyncTime) {
                    Text(stringResource(R.string.sync_time))
                }
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}

@Composable
private fun DeviceMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun SettingsScreen(
    padding: PaddingValues,
    modelManager: ModelManager,
    modelUpdateController: ModelUpdateController,
    providerSettingsViewModel: ProviderSettingsViewModel,
    themeSettingsStore: ThemeSettingsStore,
) {
    val scope = rememberCoroutineScope()
    var runtimeProbeRunning by remember { mutableStateOf(false) }
    var runtimeProbeResult by remember { mutableStateOf<String?>(null) }
    val runtimeAvailableText = stringResource(R.string.settings_sherpa_runtime_available)
    val runtimeUnavailableText = stringResource(R.string.settings_sherpa_runtime_unavailable)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        ThemeSettingsCard(store = themeSettingsStore)
        ProviderSettingsCard(
            viewModel = providerSettingsViewModel,
        )
        ModelManagerCard(
            modelManager = modelManager,
            modelUpdateController = modelUpdateController,
        )
    }
}
