package io.github.ioannes78.voica.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.ai.AiSummaryCard
import io.github.ioannes78.voica.ui.ai.AiSummaryViewModel
import io.github.ioannes78.voica.ui.diarization.DiarizationStatusCard
import io.github.ioannes78.voica.ui.diarization.DiarizationViewModel
import io.github.ioannes78.voica.ui.playback.PlaybackCard
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader
import io.github.ioannes78.voica.ui.transcript.TranscriptFollowMode
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptSegmentCard
import io.github.ioannes78.voica.ui.transcript.TranscriptVersionListCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionStatusCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

private enum class DetailTab {
    PLAYBACK,
    TRANSCRIPT,
    SUMMARY,
    INFO,
}

@Composable
fun RecordingDetailScreen(
    padding: PaddingValues,
    recording: RecordingLibraryItem,
    playbackViewModel: PlaybackViewModel,
    transcriptionViewModel: TranscriptionViewModel,
    diarizationViewModel: DiarizationViewModel,
    transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel,
    aiSummaryViewModel: AiSummaryViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onGenerateCanonical: (String) -> Unit,
    onCancelCanonical: (String) -> Unit,
) {
    BackHandler(onBack = onBack)

    var selectedTab by rememberSaveable(recording.id) {
        mutableStateOf(DetailTab.PLAYBACK)
    }
    var selectedTranscriptionId by rememberSaveable(recording.id) {
        mutableStateOf<String?>(null)
    }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var renameValue by rememberSaveable(recording.displayName) {
        mutableStateOf(recording.displayName)
    }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }

    val playback by playbackViewModel.snapshot.collectAsState()
    val transcriptionState by transcriptionViewModel.runState.collectAsState()
    val transcriptDocument by transcriptionViewModel.document.collectAsState()
    val transcriptVersions by transcriptionViewModel.versions.collectAsState()
    val transcriptVersionsRecordingId by transcriptionViewModel.versionsRecordingId.collectAsState()
    val transcriptionNotice by transcriptionViewModel.notice.collectAsState()
    val diarizationState by diarizationViewModel.runState.collectAsState()
    val diarizationNotice by diarizationViewModel.notice.collectAsState()
    val syncState by transcriptPlaybackSyncViewModel.state.collectAsState()

    val document = transcriptDocument?.takeIf { it.recordingId == recording.id }
    val versions =
        if (transcriptVersionsRecordingId == recording.id) {
            transcriptVersions
        } else {
            emptyList()
        }
    val transcriptionBusy = transcriptionState is TranscriptionRunState.Running
    val diarizationBusy = diarizationState is DiarizationRunState.Running
    val canonicalReady = recording.assets.any { it.role == AudioAssetRole.CANONICAL_WAV }
    val activeCanonicalJob = recording.derivations.firstOrNull {
        it.state in ACTIVE_DERIVATION_STATES
    }

    val transcriptListState = rememberLazyListState()
    val programmaticScroll = remember { AtomicBoolean(false) }

    LaunchedEffect(recording.id) {
        transcriptionViewModel.viewVersions(recording.id)
    }
    LaunchedEffect(document?.transcriptionId) {
        document?.transcriptionId?.let { selectedTranscriptionId = it }
    }
    LaunchedEffect(
        document?.transcriptionId,
        document?.alignmentId,
        document?.compatiblePlaybackAssetId,
    ) {
        transcriptPlaybackSyncViewModel.bind(
            timeline = document?.timeline,
            compatiblePlaybackAssetId = document?.compatiblePlaybackAssetId,
        )
    }
    LaunchedEffect(transcriptListState, selectedTab) {
        if (selectedTab != DetailTab.TRANSCRIPT) return@LaunchedEffect
        snapshotFlow { transcriptListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !programmaticScroll.get()) {
                    transcriptPlaybackSyncViewModel.suspendFollowing()
                }
            }
    }
    LaunchedEffect(
        syncState.activeRowId,
        syncState.followMode,
        document?.transcriptionId,
        syncState.discontinuityGeneration,
        selectedTab,
    ) {
        if (
            selectedTab != DetailTab.TRANSCRIPT ||
            syncState.followMode != TranscriptFollowMode.FOLLOWING ||
            syncState.activeRowId == null
        ) {
            return@LaunchedEffect
        }
        val currentDocument = document ?: return@LaunchedEffect
        val rowIndex =
            currentDocument.segments.indexOfFirst { it.stableId == syncState.activeRowId }
        if (rowIndex < 0) return@LaunchedEffect

        val targetIndex = TRANSCRIPT_ROW_START_INDEX + rowIndex
        if (transcriptListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
            return@LaunchedEffect
        }
        val destination = (targetIndex - 2).coerceAtLeast(0)
        val distance = abs(transcriptListState.firstVisibleItemIndex - targetIndex)
        programmaticScroll.set(true)
        try {
            if (distance > 12) {
                transcriptListState.scrollToItem(destination)
            } else {
                transcriptListState.animateScrollToItem(destination)
            }
        } finally {
            programmaticScroll.set(false)
        }
    }

    val idleListState = rememberLazyListState()
    val listState =
        if (selectedTab == DetailTab.TRANSCRIPT) {
            transcriptListState
        } else {
            idleListState
        }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "detail-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Column {
                    Text(
                        recording.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        recording.deviceReportedDurationMs?.let(::formatDurationMs) ?: "--",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "detail-tabs") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tabLabel(tab)) },
                    )
                }
            }
        }

        when (selectedTab) {
            DetailTab.PLAYBACK -> {
                item(key = "playback-actions") {
                    if (playback.recordingId == recording.id) {
                        PlaybackCard(
                            snapshot = playback,
                            recordingName = recording.displayName,
                            onPlay = playbackViewModel::play,
                            onPause = playbackViewModel::pause,
                            onSeek = playbackViewModel::seekToSample,
                            onSpeed = playbackViewModel::setSpeed,
                            onRetry = playbackViewModel::retryCurrent,
                        )
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.playback_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Button(
                                    onClick = { playbackViewModel.loadAndPlay(recording.id) },
                                    enabled = canonicalReady,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.playback_play))
                                }
                                if (!canonicalReady) {
                                    Text(
                                        stringResource(R.string.detail_canonical_required),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            DetailTab.TRANSCRIPT -> {
                item(key = "transcript-status") {
                    if (transcriptionState !is TranscriptionRunState.Idle ||
                        transcriptionNotice != null
                    ) {
                        TranscriptionStatusCard(
                            state = transcriptionState,
                            recordingName = recording.displayName,
                            notice = transcriptionNotice,
                            onCancel = transcriptionViewModel::cancel,
                        )
                    }
                }
                item(key = "diarization-status") {
                    if (diarizationState !is DiarizationRunState.Idle ||
                        diarizationNotice != null
                    ) {
                        DiarizationStatusCard(
                            state = diarizationState,
                            recordingName = recording.displayName,
                            notice = diarizationNotice,
                            onCancel = diarizationViewModel::cancel,
                            onRetry = diarizationViewModel::retry,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                item(key = "transcript-versions") {
                    if (versions.isNotEmpty()) {
                        TranscriptVersionListCard(
                            versions = versions,
                            recordingName = recording.displayName,
                            selectedTranscriptionId = document?.transcriptionId,
                            onSelect = { transcriptionId ->
                                selectedTranscriptionId = transcriptionId
                                transcriptionViewModel.selectVersion(transcriptionId)
                            },
                        )
                    } else {
                        TranscriptionActionsCard(
                            canonicalReady = canonicalReady,
                            canonicalBusy = activeCanonicalJob != null,
                            transcriptionBusy = transcriptionBusy,
                            diarizationBusy = diarizationBusy,
                            onGenerateCanonical = { onGenerateCanonical(recording.id) },
                            onCancelCanonical = { onCancelCanonical(recording.id) },
                            onFast = { transcriptionViewModel.startFast(recording.id) },
                            onHighQuality = {
                                transcriptionViewModel.startHighQuality(recording.id)
                            },
                            onDiarize = { diarizationViewModel.start(recording.id) },
                        )
                    }
                }
                item(key = "transcript-header") {
                    document?.let {
                        TranscriptDocumentHeader(
                            document = it,
                            recordingName = recording.displayName,
                            onRenameSpeaker = transcriptionViewModel::renameSpeaker,
                        )
                    }
                }
                item(key = "transcript-follow") {
                    if (document?.timeline != null &&
                        document.compatiblePlaybackAssetId == null
                    ) {
                        Text(
                            stringResource(R.string.detail_transcript_playback_incompatible),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (syncState.followMode == TranscriptFollowMode.USER_SUSPENDED) {
                        OutlinedButton(
                            onClick = transcriptPlaybackSyncViewModel::resumeFollowing,
                        ) {
                            Text(stringResource(R.string.detail_follow_playback))
                        }
                    }
                }
                item(key = "transcript-action-footer") {
                    if (versions.isNotEmpty()) {
                        TranscriptionActionsCard(
                            canonicalReady = canonicalReady,
                            canonicalBusy = activeCanonicalJob != null,
                            transcriptionBusy = transcriptionBusy,
                            diarizationBusy = diarizationBusy,
                            onGenerateCanonical = { onGenerateCanonical(recording.id) },
                            onCancelCanonical = { onCancelCanonical(recording.id) },
                            onFast = { transcriptionViewModel.startFast(recording.id) },
                            onHighQuality = {
                                transcriptionViewModel.startHighQuality(recording.id)
                            },
                            onDiarize = { diarizationViewModel.start(recording.id) },
                        )
                    }
                }
                val currentDocument = document
                if (currentDocument != null) {
                    items(
                        items = currentDocument.segments,
                        key = { currentDocument.transcriptionId + ":" + it.stableId },
                    ) { segment ->
                        val syncEnabled = currentDocument.compatiblePlaybackAssetId != null
                        TranscriptSegmentCard(
                            segment = segment,
                            isActive =
                                syncState.playbackCompatible &&
                                    syncState.activeRowId == segment.stableId,
                            activeCueId =
                                if (syncState.playbackCompatible &&
                                    syncState.activeRowId == segment.stableId
                                ) {
                                    syncState.activeCueId
                                } else {
                                    null
                                },
                            syncEnabled = syncEnabled,
                            onSeek = { sampleIndex ->
                                if (syncEnabled) {
                                    playbackViewModel.seekAndPlay(
                                        recordingId = recording.id,
                                        sampleIndex = sampleIndex,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            DetailTab.SUMMARY -> {
                item(key = "summary") {
                    if (document == null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.detail_summary_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(stringResource(R.string.detail_summary_requires_transcript))
                                Button(onClick = { selectedTab = DetailTab.TRANSCRIPT }) {
                                    Text(stringResource(R.string.detail_go_transcript))
                                }
                            }
                        }
                    } else {
                        AiSummaryCard(
                            transcriptionId = document.transcriptionId,
                            recordingName = recording.displayName,
                            viewModel = aiSummaryViewModel,
                            onOpenSettings = onOpenSettings,
                            onSeekEvidence = { sampleIndex ->
                                playbackViewModel.seekAndPlay(
                                    recordingId = recording.id,
                                    sampleIndex = sampleIndex,
                                )
                            },
                        )
                    }
                }
            }

            DetailTab.INFO -> {
                item(key = "info") {
                    RecordingInformationCard(
                        recording = recording,
                        canonicalReady = canonicalReady,
                        canonicalBusy = activeCanonicalJob != null,
                        onGenerateCanonical = { onGenerateCanonical(recording.id) },
                        onCancelCanonical = { onCancelCanonical(recording.id) },
                        onRename = {
                            renameValue = recording.displayName
                            renameOpen = true
                        },
                        onDelete = { deleteOpen = true },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.local_file_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.local_file_rename_label)) },
                )
            },
            confirmButton = {
                Button(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        onRename(recording.id, renameValue)
                        renameOpen = false
                    },
                ) {
                    Text(stringResource(R.string.local_file_rename_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.local_file_delete_title)) },
            text = { Text(stringResource(R.string.local_recording_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteOpen = false
                        onDelete(recording.id)
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.local_file_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TranscriptionActionsCard(
    canonicalReady: Boolean,
    canonicalBusy: Boolean,
    transcriptionBusy: Boolean,
    diarizationBusy: Boolean,
    onGenerateCanonical: () -> Unit,
    onCancelCanonical: () -> Unit,
    onFast: () -> Unit,
    onHighQuality: () -> Unit,
    onDiarize: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                stringResource(R.string.detail_transcription_actions),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!canonicalReady) {
                if (canonicalBusy) {
                    OutlinedButton(onClick = onCancelCanonical) {
                        Text(stringResource(R.string.local_standard_audio_cancel))
                    }
                } else {
                    Button(onClick = onGenerateCanonical) {
                        Text(stringResource(R.string.local_standard_audio_generate))
                    }
                }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !transcriptionBusy && !diarizationBusy,
                    onClick = onFast,
                ) {
                    Text(stringResource(R.string.transcription_start_fast))
                }
                OutlinedButton(
                    enabled = !transcriptionBusy && !diarizationBusy,
                    onClick = onHighQuality,
                ) {
                    Text(stringResource(R.string.transcription_start_high_quality))
                }
            }
            OutlinedButton(
                enabled = !transcriptionBusy && !diarizationBusy,
                onClick = onDiarize,
            ) {
                Text(stringResource(R.string.diarization_start))
            }
        }
    }
}

@Composable
private fun RecordingInformationCard(
    recording: RecordingLibraryItem,
    canonicalReady: Boolean,
    canonicalBusy: Boolean,
    onGenerateCanonical: () -> Unit,
    onCancelCanonical: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                stringResource(R.string.detail_info_title),
                style = MaterialTheme.typography.titleLarge,
            )
            InfoLine(stringResource(R.string.local_file_rename_label), recording.displayName)
            InfoLine(
                stringResource(R.string.local_file_duration),
                recording.deviceReportedDurationMs?.let(::formatDurationMs) ?: "--",
            )
            InfoLine(
                stringResource(R.string.local_file_downloaded_at),
                Instant.ofEpochMilli(recording.downloadedAtMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DISPLAY_TIME),
            )
            InfoLine(
                stringResource(R.string.detail_source_device),
                recording.sourceDeviceAddress.ifBlank { "--" },
            )
            recording.assets.forEach { asset ->
                InfoLine(
                    asset.role,
                    asset.container + " · " + formatBytes(asset.sizeBytes),
                )
            }
            Text(
                if (canonicalReady) {
                    stringResource(R.string.local_standard_audio_ready)
                } else {
                    stringResource(R.string.local_standard_audio_not_generated)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canonicalReady) {
                if (canonicalBusy) {
                    OutlinedButton(onClick = onCancelCanonical) {
                        Text(stringResource(R.string.local_standard_audio_cancel))
                    }
                } else {
                    OutlinedButton(onClick = onGenerateCanonical) {
                        Text(stringResource(R.string.local_standard_audio_generate))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRename) {
                    Text(stringResource(R.string.local_file_rename))
                }
                OutlinedButton(onClick = onDelete) {
                    Text(stringResource(R.string.local_file_delete))
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

@Composable
private fun tabLabel(tab: DetailTab): String =
    stringResource(
        when (tab) {
            DetailTab.PLAYBACK -> R.string.detail_tab_playback
            DetailTab.TRANSCRIPT -> R.string.detail_tab_transcript
            DetailTab.SUMMARY -> R.string.detail_tab_summary
            DetailTab.INFO -> R.string.detail_tab_info
        },
    )

private fun formatDurationMs(durationMs: Long): String {
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private const val TRANSCRIPT_ROW_START_INDEX = 7

private val ACTIVE_DERIVATION_STATES = setOf(
    "PREPARING",
    "DECODING",
    "NORMALIZING",
    "WRITING",
    "VERIFYING",
    "COMMITTING",
)

private val DISPLAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
