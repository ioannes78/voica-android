package io.github.ioannes78.voica.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.DiarizationBenchmarkRunner
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.ui.ai.AiSummaryContentViewModel
import io.github.ioannes78.voica.ui.ai.AiSummaryProductCard
import io.github.ioannes78.voica.ui.ai.AiSummaryTransientHeader
import io.github.ioannes78.voica.ui.ai.AiSummaryViewModel
import io.github.ioannes78.voica.ui.diarization.DiarizationStatusCard
import io.github.ioannes78.voica.ui.diarization.DiarizationViewModel
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.playback.RecordingPlaybackCard
import io.github.ioannes78.voica.ui.transcript.TranscriptContinuousReading
import io.github.ioannes78.voica.ui.transcript.TranscriptContentViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader
import io.github.ioannes78.voica.ui.transcript.TranscriptFollowMode
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptProductActionBar
import io.github.ioannes78.voica.ui.transcript.TranscriptReadingParagraph
import io.github.ioannes78.voica.ui.transcript.TranscriptSegmentCard
import io.github.ioannes78.voica.ui.transcript.TranscriptViewMode
import io.github.ioannes78.voica.ui.transcript.TranscriptionStatusCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel
import java.util.Locale

private enum class ProductDetailTab {
    RECORDING,
    TRANSCRIPT,
    SUMMARY,
}

@Composable
internal fun RecordingDetailProductScreen(
    padding: PaddingValues,
    recording: RecordingLibraryItem,
    playbackViewModel: PlaybackViewModel,
    transcriptionViewModel: TranscriptionViewModel,
    transcriptContentViewModel: TranscriptContentViewModel,
    diarizationViewModel: DiarizationViewModel,
    transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel,
    aiSummaryViewModel: AiSummaryViewModel,
    aiSummaryContentViewModel: AiSummaryContentViewModel,
    speechBenchmarkRunner: SpeechBenchmarkRunner?,
    diarizationBenchmarkRunner: DiarizationBenchmarkRunner?,
    initialSearchTarget: SearchDocumentEntity?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onExportCanonical: (String) -> Unit,
    onExportOriginal: (String) -> Unit,
    onShareCanonical: (String) -> Unit,
    onShareOriginal: (String) -> Unit,
    onGenerateCanonical: (String) -> Unit,
    onCancelCanonical: (String) -> Unit,
    deviceRecordingActive: Boolean,
    initialDestination: RecordingDetailDestination,
    navigationRequestToken: Int,
    onDestinationChanged: (RecordingDetailDestination?) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val ignoredSpeechBenchmarkRunner = speechBenchmarkRunner
    @Suppress("UNUSED_VARIABLE") val ignoredDiarizationBenchmarkRunner = diarizationBenchmarkRunner
    @Suppress("UNUSED_VARIABLE") val ignoredSearchTarget = initialSearchTarget

    var selectedTab by rememberSaveable(recording.id, initialDestination) {
        mutableStateOf(initialDestination.toProductTab())
    }
    var transcriptViewMode by rememberSaveable(recording.id) {
        mutableStateOf(TranscriptViewMode.READING)
    }
    var renameOpen by rememberSaveable(recording.id) { mutableStateOf(false) }
    var renameValue by rememberSaveable(recording.displayName) {
        mutableStateOf(recording.displayName)
    }
    var deleteOpen by rememberSaveable(recording.id) { mutableStateOf(false) }
    var transcriptMenuOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    val transcriptionState by transcriptionViewModel.runState.collectAsState()
    val document by transcriptionViewModel.document.collectAsState()
    val candidateId by transcriptionViewModel.candidateId.collectAsState()
    val transcriptionNotice by transcriptionViewModel.notice.collectAsState()
    val transcriptContentState by transcriptContentViewModel.state.collectAsState()
    val diarizationState by diarizationViewModel.runState.collectAsState()
    val diarizationNotice by diarizationViewModel.notice.collectAsState()
    val aiSummaryRunState by aiSummaryViewModel.runState.collectAsState()
    val aiSummaryCandidateId by aiSummaryViewModel.candidateId.collectAsState()
    val syncState by transcriptPlaybackSyncViewModel.state.collectAsState()
    val playbackSnapshot by playbackViewModel.snapshot.collectAsState()

    val recordingDocument = document?.takeIf { it.recordingId == recording.id }
    val candidatePreview =
        candidateId != null && recordingDocument?.transcriptionId == candidateId
    val transcriptionBusy =
        (transcriptionState as? TranscriptionRunState.Running)?.recordingId == recording.id
    val diarizationBusy =
        (diarizationState as? DiarizationRunState.Running)?.recordingId == recording.id
    val summaryRunning =
        (aiSummaryRunState as? AiSummaryRunState.Running)
            ?.takeIf { running ->
                recordingDocument?.transcriptionId == running.transcriptionId
            }
    val canonicalReady =
        recording.assets.any { asset ->
            asset.role == AudioAssetRole.CANONICAL_WAV &&
                asset.integrityState == AudioIntegrityState.VERIFIED &&
                asset.formatValidationState == AudioValidationState.VALID
        }
    val canonicalBusy = recording.derivations.any { it.state in ACTIVE_DERIVATION_STATES }

    LaunchedEffect(recording.id) {
        transcriptionViewModel.viewVersions(recording.id)
    }
    LaunchedEffect(recording.id, navigationRequestToken) {
        if (navigationRequestToken > 0) {
            val target = initialDestination.toProductTab()
            if (target != ProductDetailTab.TRANSCRIPT && candidatePreview) {
                transcriptionViewModel.showCurrent(recording.id)
            }
            selectedTab = target
        }
    }
    LaunchedEffect(recordingDocument?.transcriptionId, recordingDocument?.alignmentId) {
        transcriptContentViewModel.bind(recordingDocument)
    }
    LaunchedEffect(
        recordingDocument?.transcriptionId,
        recordingDocument?.alignmentId,
        recordingDocument?.compatiblePlaybackAssetId,
    ) {
        transcriptPlaybackSyncViewModel.bind(
            timeline = recordingDocument?.timeline,
            compatiblePlaybackAssetId = recordingDocument?.compatiblePlaybackAssetId,
        )
    }
    LaunchedEffect(selectedTab, candidatePreview) {
        if (selectedTab != ProductDetailTab.TRANSCRIPT && candidatePreview) {
            transcriptionViewModel.showCurrent(recording.id)
        }
    }
    LaunchedEffect(recording.id, selectedTab) {
        onDestinationChanged(selectedTab.toDestination())
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recording.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (recording.mediaDurationMs ?: recording.deviceReportedDurationMs)
                        ?.let(::formatDetailDuration)
                        ?: "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedTab == ProductDetailTab.TRANSCRIPT && canonicalReady) {
                Box {
                    IconButton(onClick = { transcriptMenuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多转写操作")
                    }
                    DropdownMenu(
                        expanded = transcriptMenuOpen,
                        onDismissRequest = { transcriptMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重新进行说话人分离") },
                            enabled = !transcriptionBusy && !diarizationBusy,
                            onClick = {
                                transcriptMenuOpen = false
                                diarizationViewModel.start(recording.id)
                            },
                        )
                    }
                }
            }
        }

        val tabs = ProductDetailTab.entries
        TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
            tabs.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = {
                        if (tab != ProductDetailTab.TRANSCRIPT && candidatePreview) {
                            transcriptionViewModel.showCurrent(recording.id)
                        }
                        selectedTab = tab
                    },
                    text = {
                        Text(
                            when (tab) {
                                ProductDetailTab.RECORDING -> "录音"
                                ProductDetailTab.TRANSCRIPT -> "转写"
                                ProductDetailTab.SUMMARY -> "总结"
                            },
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            ProductDetailTab.TRANSCRIPT -> {
                if (
                    shouldShowTranscriptionStatus(transcriptionState, recording.id) ||
                    shouldShowDiarizationStatus(diarizationState, recording.id) ||
                    candidateId != null
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (shouldShowTranscriptionStatus(transcriptionState, recording.id)) {
                            TranscriptionStatusCard(
                                state = transcriptionState,
                                recordingName = recording.displayName,
                                notice = transcriptionNotice,
                                onCancel = transcriptionViewModel::cancel,
                            )
                        }
                        if (shouldShowDiarizationStatus(diarizationState, recording.id)) {
                            DiarizationStatusCard(
                                state = diarizationState,
                                recordingName = recording.displayName,
                                notice = diarizationNotice,
                                onCancel = diarizationViewModel::cancel,
                                onRetry = diarizationViewModel::retry,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                        candidateId?.let { newResultId ->
                            CandidateTranscriptionBanner(
                                previewing = candidatePreview,
                                onPreview = {
                                    transcriptViewMode = TranscriptViewMode.READING
                                    transcriptionViewModel.previewCandidate(newResultId)
                                },
                                onReturnCurrent = {
                                    transcriptionViewModel.showCurrent(recording.id)
                                },
                                onAdopt = {
                                    transcriptViewMode = TranscriptViewMode.READING
                                    transcriptionViewModel.adoptCandidate(newResultId)
                                },
                            )
                        }
                    }
                }
            }

            ProductDetailTab.SUMMARY -> {
                if (recordingDocument != null) {
                    AiSummaryTransientHeader(
                        running = summaryRunning,
                        candidateId = aiSummaryCandidateId,
                        recordingName = recording.displayName,
                        onCancel = aiSummaryViewModel::cancel,
                        onViewCandidate = aiSummaryViewModel::selectSummary,
                        onAdoptCandidate = aiSummaryViewModel::adoptSummaryResult,
                    )
                }
            }

            ProductDetailTab.RECORDING -> Unit
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            when (selectedTab) {
                ProductDetailTab.RECORDING ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "player") {
                            RecordingPlaybackCard(
                                recordingId = recording.id,
                                recordingName = recording.displayName,
                                canonicalReady = canonicalReady,
                                deviceRecordingActive = deviceRecordingActive,
                                playbackViewModel = playbackViewModel,
                            )
                        }
                        if (!canonicalReady) {
                            item(key = "canonical-action") {
                                if (canonicalBusy) {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onCancelCanonical(recording.id) },
                                    ) {
                                        Text("取消生成标准 WAV")
                                    }
                                } else {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onGenerateCanonical(recording.id) },
                                    ) {
                                        Text("生成标准 WAV")
                                    }
                                }
                            }
                        }
                        item(key = "recording-actions") {
                            RecordingPlaybackProductActions(
                                recording = recording,
                                canonicalReady = canonicalReady,
                                onRename = {
                                    renameValue = recording.displayName
                                    renameOpen = true
                                },
                                onShareCanonical = { onShareCanonical(recording.id) },
                                onShareOriginal = { onShareOriginal(recording.id) },
                                onExportCanonical = { onExportCanonical(recording.id) },
                                onExportOriginal = { onExportOriginal(recording.id) },
                                onDelete = { deleteOpen = true },
                            )
                        }
                    }

                ProductDetailTab.TRANSCRIPT ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (recordingDocument == null) {
                            item(key = "transcription-empty") {
                                EmptyTranscriptionPanel(
                                    canonicalReady = canonicalReady,
                                    canonicalBusy = canonicalBusy,
                                    transcriptionBusy = transcriptionBusy,
                                    onGenerateCanonical = { onGenerateCanonical(recording.id) },
                                    onCancelCanonical = { onCancelCanonical(recording.id) },
                                    onStart = { transcriptionViewModel.startOffline(recording.id) },
                                )
                            }
                        } else {
                            if (!candidatePreview) {
                                item(key = "transcription-actions") {
                                    TranscriptProductActionBar(
                                        recordingName = recording.displayName,
                                        mode = transcriptViewMode,
                                        state = transcriptContentState,
                                        viewModel = transcriptContentViewModel,
                                        onModeChange = { transcriptViewMode = it },
                                        onRetranscribe = {
                                            transcriptionViewModel.startOffline(recording.id)
                                        },
                                    )
                                }
                            } else {
                                item(key = "candidate-preview-label") {
                                    Text(
                                        "新结果预览 · 当前转写尚未切换",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (transcriptViewMode == TranscriptViewMode.READING) {
                                item(key = "continuous-reading") {
                                    if (transcriptContentState.loading) {
                                        Text(
                                            "正在准备阅读稿…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        TranscriptContinuousReading(
                                            paragraphs = transcriptContentState.paragraphs,
                                        )
                                    }
                                }
                            } else {
                                item(key = "timeline-header") {
                                    TranscriptDocumentHeader(
                                        document = recordingDocument,
                                        recordingName = recording.displayName,
                                        onRenameSpeaker = transcriptionViewModel::renameSpeaker,
                                    )
                                }
                                if (
                                    recordingDocument.timeline != null &&
                                    recordingDocument.compatiblePlaybackAssetId == null
                                ) {
                                    item(key = "timeline-incompatible") {
                                        Text(
                                            "当前标准音频与该转写时间轴不一致，暂不能点击跳转播放。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (syncState.followMode == TranscriptFollowMode.USER_SUSPENDED) {
                                    item(key = "timeline-follow") {
                                        OutlinedButton(
                                            onClick = transcriptPlaybackSyncViewModel::resumeFollowing,
                                        ) {
                                            Text("继续跟随播放")
                                        }
                                    }
                                }

                                if (transcriptContentState.currentRevisionId != null) {
                                    items(
                                        items = transcriptContentState.paragraphs,
                                        key = { "revision-timeline:" + it.stableId },
                                    ) { paragraph ->
                                        RevisionTimelineRow(
                                            paragraph = paragraph,
                                            playbackPosition =
                                                playbackSnapshot.positionSampleIndex
                                                    .takeIf { playbackSnapshot.recordingId == recording.id },
                                            playbackCompatible =
                                                recordingDocument.compatiblePlaybackAssetId != null,
                                            onSeek = { sample ->
                                                playbackViewModel.seekAndPlay(recording.id, sample)
                                            },
                                        )
                                    }
                                } else {
                                    items(
                                        items = recordingDocument.segments,
                                        key = { recordingDocument.transcriptionId + ":" + it.stableId },
                                    ) { segment ->
                                        val syncEnabled =
                                            recordingDocument.compatiblePlaybackAssetId != null
                                        TranscriptSegmentCard(
                                            segment = segment,
                                            isActive =
                                                syncState.playbackCompatible &&
                                                    syncState.activeRowId == segment.stableId,
                                            activeCueId =
                                                syncState.activeCueId.takeIf {
                                                    syncState.playbackCompatible &&
                                                        syncState.activeRowId == segment.stableId
                                                },
                                            syncEnabled = syncEnabled,
                                            onSeek = { sampleIndex ->
                                                if (syncEnabled) {
                                                    playbackViewModel.seekAndPlay(
                                                        recording.id,
                                                        sampleIndex,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                ProductDetailTab.SUMMARY ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        item(key = "summary") {
                            when {
                                candidatePreview ->
                                    Text(
                                        "正在返回当前转写…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                recordingDocument == null ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text("总结", style = MaterialTheme.typography.titleLarge)
                                            Text("请先完成转写，再生成总结。")
                                            Button(onClick = { selectedTab = ProductDetailTab.TRANSCRIPT }) {
                                                Text("前往转写")
                                            }
                                        }
                                    }
                                else ->
                                    AiSummaryProductCard(
                                        transcriptionId = recordingDocument.transcriptionId,
                                        recordingName = recording.displayName,
                                        viewModel = aiSummaryViewModel,
                                        contentViewModel = aiSummaryContentViewModel,
                                        onOpenSettings = onOpenSettings,
                                        onSeekEvidence = { sampleIndex ->
                                            playbackViewModel.seekAndPlay(recording.id, sampleIndex)
                                        },
                                        showTransientHeader = false,
                                    )
                            }
                        }
                    }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名录音") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(120) },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        onRename(recording.id, renameValue.trim())
                        renameOpen = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("取消") }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除本地录音？") },
            text = {
                Text("将删除手机中的录音、标准音频、转写、说话人结果和总结；不会删除录音卡中的文件。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteOpen = false
                        onDelete(recording.id)
                        onBack()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CandidateTranscriptionBanner(
    previewing: Boolean,
    onPreview: () -> Unit,
    onReturnCurrent: () -> Unit,
    onAdopt: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "新的转写结果已生成",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = if (previewing) onReturnCurrent else onPreview) {
                Text(if (previewing) "返回当前" else "查看")
            }
            Button(onClick = onAdopt) { Text("使用新结果") }
        }
    }
}

@Composable
private fun EmptyTranscriptionPanel(
    canonicalReady: Boolean,
    canonicalBusy: Boolean,
    transcriptionBusy: Boolean,
    onGenerateCanonical: () -> Unit,
    onCancelCanonical: () -> Unit,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("转写", style = MaterialTheme.typography.titleLarge)
            Text(
                "转写完成后会直接进入阅读模式；需要校对原音时可切换到时间轴。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !canonicalReady && canonicalBusy ->
                    OutlinedButton(onClick = onCancelCanonical) {
                        Text("取消生成标准 WAV")
                    }
                !canonicalReady ->
                    Button(onClick = onGenerateCanonical) {
                        Text("生成标准 WAV")
                    }
                else ->
                    Button(enabled = !transcriptionBusy, onClick = onStart) {
                        Text("开始离线转写")
                    }
            }
        }
    }
}

@Composable
private fun RevisionTimelineRow(
    paragraph: TranscriptReadingParagraph,
    playbackPosition: Long?,
    playbackCompatible: Boolean,
    onSeek: (Long) -> Unit,
) {
    val start = paragraph.anchorStartSampleIndex
    val end = paragraph.anchorEndSampleIndexExclusive
    val anchored = start != null && end != null
    val active =
        playbackPosition != null && start != null && end != null && playbackPosition in start until end
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = anchored && playbackCompatible,
                    onClick = { start?.let(onSeek) },
                ),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (active) 2.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    start?.let(::formatSampleTime) ?: "无音频定位",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                paragraph.speakerDisplayName?.let { speaker ->
                    Text(
                        speaker,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(paragraph.text, style = MaterialTheme.typography.bodyLarge)
            if (!anchored) {
                Text(
                    "人工新增内容 · 无音频定位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun RecordingDetailDestination.toProductTab(): ProductDetailTab =
    when (this) {
        RecordingDetailDestination.PLAYBACK -> ProductDetailTab.RECORDING
        RecordingDetailDestination.TRANSCRIPT -> ProductDetailTab.TRANSCRIPT
        RecordingDetailDestination.SUMMARY -> ProductDetailTab.SUMMARY
    }

private fun ProductDetailTab.toDestination(): RecordingDetailDestination =
    when (this) {
        ProductDetailTab.RECORDING -> RecordingDetailDestination.PLAYBACK
        ProductDetailTab.TRANSCRIPT -> RecordingDetailDestination.TRANSCRIPT
        ProductDetailTab.SUMMARY -> RecordingDetailDestination.SUMMARY
    }

private fun formatDetailDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatSampleTime(sampleIndex: Long): String {
    val seconds = sampleIndex.coerceAtLeast(0L) / 16_000L
    val minutes = seconds / 60L
    val remainder = seconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, remainder)
}

private val ACTIVE_DERIVATION_STATES =
    setOf(
        "PREPARING",
        "DECODING",
        "NORMALIZING",
        "WRITING",
        "VERIFYING",
        "COMMITTING",
    )
