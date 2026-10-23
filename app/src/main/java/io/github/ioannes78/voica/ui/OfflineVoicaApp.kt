package io.github.ioannes78.voica.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.AppRecordingNavigation
import io.github.ioannes78.voica.AudioExportVariant
import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.DiarizationBenchmarkRunner
import io.github.ioannes78.voica.DiarizationCoordinator
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.LocalAudioExportCoordinator
import io.github.ioannes78.voica.LocalAudioImportCoordinator
import io.github.ioannes78.voica.LocalAudioShareOutcome
import io.github.ioannes78.voica.LocalQa4GlobalAttentionProjection
import io.github.ioannes78.voica.LocalRecordingDeleteCoordinator
import io.github.ioannes78.voica.LocalSpeechSettingsStore
import io.github.ioannes78.voica.ModelUpdateController
import io.github.ioannes78.voica.Qa4GlobalAttentionItem
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.StorageManagementCoordinator
import io.github.ioannes78.voica.TranscriptionCoordinator
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.database.SearchDocumentTypeValue
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.UnifiedSearchRepository
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.ai.AiSummaryContentViewModel
import io.github.ioannes78.voica.ui.ai.AiSummaryViewModel
import io.github.ioannes78.voica.ui.device.DeviceProductScreen
import io.github.ioannes78.voica.ui.diarization.DiarizationViewModel
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import io.github.ioannes78.voica.ui.library.RecordingDetailScreen
import io.github.ioannes78.voica.ui.library.RecordingLibraryRoute
import io.github.ioannes78.voica.ui.library.RecordingLibraryViewModel
import io.github.ioannes78.voica.ui.model.ModelManagerCard
import io.github.ioannes78.voica.ui.playback.GlobalMiniPlaybackBar
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.recording.GlobalRecordingStatusBar
import io.github.ioannes78.voica.ui.search.UnifiedSearchScreen
import io.github.ioannes78.voica.ui.search.UnifiedSearchViewModel
import io.github.ioannes78.voica.ui.settings.ProductSettingsScreen
import io.github.ioannes78.voica.ui.theme.ThemeSettingsStore
import io.github.ioannes78.voica.ui.transcript.TranscriptContentViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class OfflineGlobalRecordingOpenRequest(
    val token: Int,
    val recordingId: String,
    val destination: RecordingDetailDestination,
)

private data class OfflineGlobalDetailContext(
    val recordingId: String,
    val destination: RecordingDetailDestination,
)

internal data class OfflineGlobalTaskItem(
    val key: String,
    val recordingId: String,
    val recordingName: String,
    val label: String,
    val progress: String?,
    val destination: RecordingDetailDestination,
    val terminal: Boolean,
)

@Composable
fun OfflineVoicaApp(
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
    stage12CContentRepository: Stage12CContentRepository,
    unifiedSearchRepository: UnifiedSearchRepository,
    searchIndexRebuilder: SearchIndexRebuilder,
    diarizationCoordinator: DiarizationCoordinator,
    diarizationRepository: DiarizationRepository,
    aiSummaryRepository: AiSummaryRepository,
    themeSettingsStore: ThemeSettingsStore,
    localSpeechSettingsStore: LocalSpeechSettingsStore,
    speechBenchmarkRunner: SpeechBenchmarkRunner?,
    diarizationBenchmarkRunner: DiarizationBenchmarkRunner?,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var secondaryPageActive by rememberSaveable { mutableStateOf(false) }
    var deviceHomeRequest by rememberSaveable { mutableIntStateOf(0) }

    val deviceViewModel: DeviceViewModel =
        viewModel(
            factory =
                remember(repository, recordingLibraryRepository, canonicalAudioCoordinator) {
                    DeviceViewModel.Factory(
                        repository,
                        recordingLibraryRepository,
                        canonicalAudioCoordinator,
                    )
                },
        )
    val recordingLibraryViewModel: RecordingLibraryViewModel =
        viewModel(
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
    val playbackViewModel: PlaybackViewModel =
        viewModel(
            factory =
                remember(playbackController, localRecordingDeleteCoordinator) {
                    PlaybackViewModel.Factory(
                        playbackController,
                        localRecordingDeleteCoordinator,
                    )
                },
        )
    val diarizationViewModel: DiarizationViewModel =
        viewModel(
            factory =
                remember(diarizationCoordinator) {
                    DiarizationViewModel.Factory(diarizationCoordinator)
                },
        )
    val transcriptionViewModel: TranscriptionViewModel =
        viewModel(
            factory =
                remember(
                    transcriptionCoordinator,
                    transcriptionRepository,
                    diarizationCoordinator,
                    diarizationRepository,
                    recordingLibraryRepository,
                    stage12CContentRepository,
                ) {
                    TranscriptionViewModel.Factory(
                        transcriptionCoordinator,
                        transcriptionRepository,
                        diarizationCoordinator,
                        diarizationRepository,
                        recordingLibraryRepository,
                        stage12CContentRepository,
                    )
                },
        )
    val transcriptContentViewModel: TranscriptContentViewModel =
        viewModel(
            factory =
                remember(stage12CContentRepository) {
                    TranscriptContentViewModel.Factory(stage12CContentRepository)
                },
        )
    val unifiedSearchViewModel: UnifiedSearchViewModel =
        viewModel(
            factory =
                remember(unifiedSearchRepository, searchIndexRebuilder) {
                    UnifiedSearchViewModel.Factory(
                        repository = unifiedSearchRepository,
                        rebuilder = searchIndexRebuilder,
                    )
                },
        )
    val transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel =
        viewModel(
            factory =
                remember(playbackController) {
                    TranscriptPlaybackSyncViewModel.Factory(playbackController)
                },
        )
    val aiSummaryContentViewModel: AiSummaryContentViewModel =
        viewModel(
            factory =
                remember(stage12CContentRepository) {
                    AiSummaryContentViewModel.Factory(stage12CContentRepository)
                },
        )
    val aiSummaryViewModel: AiSummaryViewModel =
        viewModel(
            factory =
                remember(aiSummaryRepository, stage12CContentRepository) {
                    AiSummaryViewModel.Factory(
                        repository = aiSummaryRepository,
                        contentRepository = stage12CContentRepository,
                    )
                },
        )

    val qa4AttentionProjection = LocalQa4GlobalAttentionProjection.current
    val globalRecording by deviceViewModel.recordingState.collectAsState()
    val libraryRecordings by deviceViewModel.libraryRecordings.collectAsState(initial = emptyList())
    val globalTranscription by transcriptionViewModel.runState.collectAsState()
    val globalDiarization by diarizationViewModel.runState.collectAsState()
    val globalPlayback by playbackViewModel.snapshot.collectAsState()
    val externalOpenRequest by AppRecordingNavigation.request.collectAsState()
    var openRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var libraryOpenRequest by remember { mutableStateOf<OfflineGlobalRecordingOpenRequest?>(null) }
    var activeDetailContext by remember { mutableStateOf<OfflineGlobalDetailContext?>(null) }
    var lastExternalOpenToken by rememberSaveable { mutableStateOf<Long?>(null) }

    val liveRunningTasks =
        buildOfflineGlobalTaskItems(
            transcription = globalTranscription,
            diarization = globalDiarization,
            recordings = libraryRecordings,
        ).filterNot { it.terminal }
    val attentionByKey = qa4AttentionProjection.items.associateBy { it.key }
    val globalTasks =
        buildOfflineConsolidatedGlobalTaskItems(
            runningTasks = liveRunningTasks,
            attentionItems = qa4AttentionProjection.items,
            visibleRecordingId = activeDetailContext?.recordingId,
            visibleDestination = activeDetailContext?.destination,
        )
    val requestOpenRecording: (String, RecordingDetailDestination) -> Unit =
        { recordingId, destination ->
            openRequestToken += 1
            libraryOpenRequest =
                OfflineGlobalRecordingOpenRequest(
                    token = openRequestToken,
                    recordingId = recordingId,
                    destination = destination,
                )
            activeDetailContext = OfflineGlobalDetailContext(recordingId, destination)
            secondaryPageActive = true
            selectedTab = 1
        }

    LaunchedEffect(externalOpenRequest?.token) {
        val request = externalOpenRequest ?: return@LaunchedEffect
        if (lastExternalOpenToken != request.token) {
            lastExternalOpenToken = request.token
            requestOpenRecording(request.recordingId, request.destination)
        }
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
                            activeDetailContext = null
                            secondaryPageActive = false
                            selectedTab = 0
                            deviceHomeRequest += 1
                        },
                    )
                }
                if (globalTasks.isNotEmpty()) {
                    OfflineGlobalTaskStatusBar(
                        tasks = globalTasks,
                        onOpen = { task ->
                            val attentionItem = attentionByKey[task.key]
                            if (attentionItem != null) {
                                qa4AttentionProjection.onOpen(attentionItem)
                            } else {
                                requestOpenRecording(task.recordingId, task.destination)
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            Column {
                val suppressForFullPlayer =
                    activeDetailContext?.let { detail ->
                        detail.destination == RecordingDetailDestination.PLAYBACK &&
                            detail.recordingId == globalPlayback.recordingId
                    } == true
                if (shouldShowOfflineGlobalPlayback(globalPlayback) && !suppressForFullPlayer) {
                    GlobalMiniPlaybackBar(
                        snapshot = globalPlayback,
                        recordingName =
                            libraryRecordings.firstOrNull {
                                it.id == globalPlayback.recordingId
                            }?.displayName,
                        onPlay = playbackViewModel::play,
                        onPause = playbackViewModel::pause,
                        onStop = playbackViewModel::closePlayer,
                        onOpen = {
                            globalPlayback.recordingId?.let { id ->
                                requestOpenRecording(id, RecordingDetailDestination.PLAYBACK)
                            }
                        },
                    )
                }
                if (!secondaryPageActive) {
                    NavigationBar(modifier = Modifier.height(64.dp)) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = {
                                activeDetailContext = null
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
                                activeDetailContext = null
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
                                activeDetailContext = null
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
            0 ->
                DeviceProductScreen(
                    padding = padding,
                    viewModel = deviceViewModel,
                    homeRequestToken = deviceHomeRequest,
                    onSecondaryPageChanged = { secondaryPageActive = it },
                )

            1 ->
                OfflineLocalFilesScreen(
                    padding = padding,
                    viewModel = deviceViewModel,
                    recordingLibraryViewModel = recordingLibraryViewModel,
                    playbackViewModel = playbackViewModel,
                    localAudioExportCoordinator = localAudioExportCoordinator,
                    transcriptionViewModel = transcriptionViewModel,
                    transcriptContentViewModel = transcriptContentViewModel,
                    diarizationViewModel = diarizationViewModel,
                    transcriptPlaybackSyncViewModel = transcriptPlaybackSyncViewModel,
                    aiSummaryViewModel = aiSummaryViewModel,
                    aiSummaryContentViewModel = aiSummaryContentViewModel,
                    unifiedSearchViewModel = unifiedSearchViewModel,
                    speechBenchmarkRunner = speechBenchmarkRunner,
                    diarizationBenchmarkRunner = diarizationBenchmarkRunner,
                    openRequest = libraryOpenRequest,
                    onOpenRequestConsumed = { request ->
                        if (libraryOpenRequest?.token == request.token) {
                            libraryOpenRequest = null
                        }
                    },
                    onOpenSettings = {
                        activeDetailContext = null
                        secondaryPageActive = false
                        selectedTab = 2
                    },
                    onSecondaryPageChanged = { secondaryPageActive = it },
                    onDetailContextChanged = { recordingId, destination ->
                        activeDetailContext =
                            if (recordingId != null && destination != null) {
                                OfflineGlobalDetailContext(recordingId, destination)
                            } else {
                                null
                            }
                    },
                )

            else ->
                ProductSettingsScreen(
                    padding = padding,
                    modelManager = modelManager,
                    modelUpdateController = modelUpdateController,
                    storageManagementCoordinator = storageManagementCoordinator,
                    themeSettingsStore = themeSettingsStore,
                    localSpeechSettingsStore = localSpeechSettingsStore,
                    onSecondaryPageChanged = { secondaryPageActive = it },
                )
        }
    }
}

private data class OfflinePendingDetailAudioExport(
    val recordingId: String,
    val variant: AudioExportVariant,
)

@Composable
private fun OfflineLocalFilesScreen(
    padding: PaddingValues,
    viewModel: DeviceViewModel,
    recordingLibraryViewModel: RecordingLibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    localAudioExportCoordinator: LocalAudioExportCoordinator,
    transcriptionViewModel: TranscriptionViewModel,
    transcriptContentViewModel: TranscriptContentViewModel,
    diarizationViewModel: DiarizationViewModel,
    transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel,
    aiSummaryViewModel: AiSummaryViewModel,
    aiSummaryContentViewModel: AiSummaryContentViewModel,
    unifiedSearchViewModel: UnifiedSearchViewModel,
    speechBenchmarkRunner: SpeechBenchmarkRunner?,
    diarizationBenchmarkRunner: DiarizationBenchmarkRunner?,
    openRequest: OfflineGlobalRecordingOpenRequest?,
    onOpenRequestConsumed: (OfflineGlobalRecordingOpenRequest) -> Unit,
    onOpenSettings: () -> Unit,
    onSecondaryPageChanged: (Boolean) -> Unit,
    onDetailContextChanged: (String?, RecordingDetailDestination?) -> Unit,
) {
    val recordings by viewModel.libraryRecordings.collectAsState(initial = emptyList())
    var selectedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedDestination by remember { mutableStateOf(RecordingDetailDestination.PLAYBACK) }
    var detailNavigationToken by rememberSaveable { mutableIntStateOf(0) }
    val selectedRecording = selectedRecordingId?.let { id -> recordings.firstOrNull { it.id == id } }
    val deviceRecording by viewModel.recordingState.collectAsState()
    val context = LocalContext.current
    val actionScope = rememberCoroutineScope()
    var pendingSafExport by remember { mutableStateOf<OfflinePendingDetailAudioExport?>(null) }
    var unifiedSearchOpen by rememberSaveable { mutableStateOf(false) }
    var pendingSearchTarget by remember { mutableStateOf<SearchDocumentEntity?>(null) }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingSafExport
            pendingSafExport = null
            val destination = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && pending != null && destination != null) {
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

    val exportDetailAudio: (String, AudioExportVariant) -> Unit = { recordingId, variant ->
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
                    if (item?.exported == true) "已导出到 Downloads/WENZHI" else item?.error ?: "导出失败",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                val descriptor = localAudioExportCoordinator.describe(recordingId, variant)
                if (descriptor == null) {
                    Toast.makeText(context, "没有可导出的音频", Toast.LENGTH_SHORT).show()
                } else {
                    pendingSafExport = OfflinePendingDetailAudioExport(recordingId, variant)
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

    val shareDetailAudio: (String, AudioExportVariant) -> Unit = { recordingId, variant ->
        actionScope.launch {
            when (
                val outcome = localAudioExportCoordinator.prepareShare(recordingId, variant)
            ) {
                is LocalAudioShareOutcome.Ready -> context.startActivity(outcome.intent)
                is LocalAudioShareOutcome.Failed ->
                    Toast.makeText(context, outcome.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(openRequest?.token) {
        val request = openRequest ?: return@LaunchedEffect
        requestedDestination = request.destination
        pendingSearchTarget = null
        unifiedSearchOpen = false
        selectedRecordingId = request.recordingId
        detailNavigationToken = request.token
        onSecondaryPageChanged(true)
        onOpenRequestConsumed(request)
    }

    LaunchedEffect(selectedRecording != null, unifiedSearchOpen) {
        onSecondaryPageChanged(selectedRecording != null || unifiedSearchOpen)
        if (selectedRecording == null) onDetailContextChanged(null, null)
    }

    if (selectedRecording == null && !unifiedSearchOpen) {
        RecordingLibraryRoute(
            padding = padding,
            viewModel = recordingLibraryViewModel,
            onOpenRecording = {
                pendingSearchTarget = null
                requestedDestination = RecordingDetailDestination.PLAYBACK
                detailNavigationToken += 1
                selectedRecordingId = it
            },
            onOpenUnifiedSearch = { unifiedSearchOpen = true },
        )
    } else if (selectedRecording == null) {
        UnifiedSearchScreen(
            padding = padding,
            viewModel = unifiedSearchViewModel,
            onBack = { unifiedSearchOpen = false },
            onOpen = { result ->
                when (result.documentType) {
                    SearchDocumentTypeValue.FOLDER -> {
                        unifiedSearchOpen = false
                        result.folderId?.let(recordingLibraryViewModel::setFolderFilter)
                    }
                    SearchDocumentTypeValue.TAG -> {
                        unifiedSearchOpen = false
                        recordingLibraryViewModel.clearFilters()
                        result.tagId?.let(recordingLibraryViewModel::toggleTagFilter)
                    }
                    else -> {
                        val recordingId = result.recordingId
                        if (recordingId != null) {
                            pendingSearchTarget = result
                            requestedDestination =
                                when (result.documentType) {
                                    SearchDocumentTypeValue.TRANSCRIPT_UNIT -> RecordingDetailDestination.TRANSCRIPT
                                    SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                                    SearchDocumentTypeValue.SUMMARY_ITEM,
                                    -> RecordingDetailDestination.SUMMARY
                                    else -> RecordingDetailDestination.PLAYBACK
                                }
                            detailNavigationToken += 1
                            unifiedSearchOpen = false
                            selectedRecordingId = recordingId
                        }
                    }
                }
            },
        )
    } else {
        RecordingDetailScreen(
            padding = padding,
            recording = selectedRecording,
            playbackViewModel = playbackViewModel,
            transcriptionViewModel = transcriptionViewModel,
            transcriptContentViewModel = transcriptContentViewModel,
            diarizationViewModel = diarizationViewModel,
            transcriptPlaybackSyncViewModel = transcriptPlaybackSyncViewModel,
            aiSummaryViewModel = aiSummaryViewModel,
            aiSummaryContentViewModel = aiSummaryContentViewModel,
            speechBenchmarkRunner = speechBenchmarkRunner,
            diarizationBenchmarkRunner = diarizationBenchmarkRunner,
            initialSearchTarget = pendingSearchTarget,
            onBack = {
                selectedRecordingId = null
                pendingSearchTarget = null
                requestedDestination = RecordingDetailDestination.PLAYBACK
            },
            onOpenSettings = onOpenSettings,
            onRename = viewModel::renameLocalRecording,
            onDelete = playbackViewModel::deleteRecording,
            onExportCanonical = { exportDetailAudio(it, AudioExportVariant.CANONICAL_WAV) },
            onExportOriginal = { exportDetailAudio(it, AudioExportVariant.ORIGINAL) },
            onShareCanonical = { shareDetailAudio(it, AudioExportVariant.CANONICAL_WAV) },
            onShareOriginal = { shareDetailAudio(it, AudioExportVariant.ORIGINAL) },
            onGenerateCanonical = viewModel::generateCanonicalAudio,
            onCancelCanonical = viewModel::cancelCanonicalAudio,
            deviceRecordingActive =
                deviceRecording.status == RecordingStatus.Recording ||
                    deviceRecording.status == RecordingStatus.Paused,
            initialDestination = requestedDestination,
            navigationRequestToken = detailNavigationToken,
            onDestinationChanged = { destination ->
                if (destination != null) requestedDestination = destination
                onDetailContextChanged(selectedRecording.id, destination)
            },
        )
    }
}

internal fun shouldShowOfflineGlobalPlayback(snapshot: PlaybackSnapshot): Boolean =
    snapshot.recordingId != null && snapshot.state in setOf(PlaybackState.PLAYING, PlaybackState.PAUSED)

internal fun buildOfflineConsolidatedGlobalTaskItems(
    runningTasks: List<OfflineGlobalTaskItem>,
    attentionItems: List<Qa4GlobalAttentionItem>,
    visibleRecordingId: String? = null,
    visibleDestination: RecordingDetailDestination? = null,
): List<OfflineGlobalTaskItem> {
    fun visible(recordingId: String, destination: RecordingDetailDestination): Boolean =
        visibleRecordingId != null &&
            visibleDestination != null &&
            recordingId == visibleRecordingId &&
            destination == visibleDestination

    val projectedAttention =
        attentionItems
            .filterNot { it.destination == RecordingDetailDestination.SUMMARY }
            .map { item ->
                OfflineGlobalTaskItem(
                    key = item.key,
                    recordingId = item.recordingId,
                    recordingName = item.recordingName,
                    label = item.label,
                    progress = if (item.completionKind != null) "完成" else "查看",
                    destination = item.destination,
                    terminal = true,
                )
            }
    return (runningTasks + projectedAttention)
        .filterNot { task -> visible(task.recordingId, task.destination) }
        .distinctBy(OfflineGlobalTaskItem::key)
}

internal fun buildOfflineGlobalTaskItems(
    transcription: TranscriptionRunState,
    diarization: DiarizationRunState,
    recordings: List<RecordingLibraryItem>,
): List<OfflineGlobalTaskItem> {
    fun recordingName(recordingId: String): String =
        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"

    return buildList {
        when (transcription) {
            is TranscriptionRunState.Running -> {
                val progress =
                    transcription.progress.fraction?.let {
                        (it * 100.0).roundToInt().coerceIn(0, 100).toString() + "%"
                    }
                add(
                    OfflineGlobalTaskItem(
                        key = "transcription-running-" + transcription.recordingId,
                        recordingId = transcription.recordingId,
                        recordingName = recordingName(transcription.recordingId),
                        label = "转写中",
                        progress = progress,
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = false,
                    ),
                )
            }
            is TranscriptionRunState.Completed ->
                add(
                    OfflineGlobalTaskItem(
                        key = "transcription-completed-" + transcription.transcriptionId,
                        recordingId = transcription.recordingId,
                        recordingName = recordingName(transcription.recordingId),
                        label = "转写完成",
                        progress = "完成",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = true,
                    ),
                )
            is TranscriptionRunState.Failed ->
                add(
                    OfflineGlobalTaskItem(
                        key = "transcription-failed-" + transcription.recordingId + "-" + transcription.mode.name,
                        recordingId = transcription.recordingId,
                        recordingName = recordingName(transcription.recordingId),
                        label = "转写失败",
                        progress = "查看",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = true,
                    ),
                )
            else -> Unit
        }

        when (diarization) {
            is DiarizationRunState.Running -> {
                val progress =
                    diarization.progress.fraction?.let {
                        (it * 100.0).roundToInt().coerceIn(0, 100).toString() + "%"
                    }
                add(
                    OfflineGlobalTaskItem(
                        key = "diarization-running-" + diarization.recordingId,
                        recordingId = diarization.recordingId,
                        recordingName = recordingName(diarization.recordingId),
                        label = "说话人分离",
                        progress = progress,
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = false,
                    ),
                )
            }
            is DiarizationRunState.Completed ->
                add(
                    OfflineGlobalTaskItem(
                        key = "diarization-completed-" + diarization.runId,
                        recordingId = diarization.recordingId,
                        recordingName = recordingName(diarization.recordingId),
                        label = "说话人分离完成",
                        progress = "完成",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = true,
                    ),
                )
            is DiarizationRunState.Failed ->
                add(
                    OfflineGlobalTaskItem(
                        key = "diarization-failed-" + diarization.recordingId,
                        recordingId = diarization.recordingId,
                        recordingName = recordingName(diarization.recordingId),
                        label = "说话人分离失败",
                        progress = "查看",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                        terminal = true,
                    ),
                )
            else -> Unit
        }
    }
}

@Composable
private fun OfflineGlobalTaskStatusBar(
    tasks: List<OfflineGlobalTaskItem>,
    onOpen: (OfflineGlobalTaskItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                when {
                    tasks.all { it.terminal } -> if (tasks.size == 1) "任务通知" else tasks.size.toString() + " 条任务通知"
                    tasks.none { it.terminal } -> if (tasks.size == 1) "正在处理" else tasks.size.toString() + " 个任务正在处理"
                    else -> "任务状态"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tasks.forEach { task ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(task) }
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
