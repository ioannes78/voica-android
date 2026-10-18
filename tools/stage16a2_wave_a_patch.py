from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    target = ROOT / path
    text = target.read_text()
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f'{path}: expected at least {count} occurrences, found {actual}: {old[:120]!r}')
    target.write_text(text.replace(old, new, count))


def replace_all(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text()
    if old not in text:
        raise RuntimeError(f'{path}: anchor not found: {old[:120]!r}')
    target.write_text(text.replace(old, new))


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


# 1) Durable pending-owner support for ordinary recorder-card capture.
replace(
    'core/database/src/main/java/io/github/ioannes78/voica/database/TimelineAnnotationEntities.kt',
    '    const val PHONE_CAPTURE = "PHONE_CAPTURE"\n    const val REALTIME_RUN = "REALTIME_RUN"',
    '    const val PHONE_CAPTURE = "PHONE_CAPTURE"\n    const val RECORDER_CAPTURE = "RECORDER_CAPTURE"\n    const val REALTIME_RUN = "REALTIME_RUN"',
)

repo = 'core/database/src/main/java/io/github/ioannes78/voica/database/TimelineAnnotationRepository.kt'
replace(
    repo,
    '''                TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ->
                    require(ownerId == recordingId) {
                        "phone capture pending owner must promote to its future recordingId"
                    }

                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN -> {''',
    '''                TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ->
                    require(ownerId == recordingId) {
                        "phone capture pending owner must promote to its future recordingId"
                    }

                TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE -> {
                    val recording = recordingDao.findByRemoteIdentity(ownerId)
                    require(recording?.id == recordingId) {
                        "recorder capture pending owner must promote to its exact remote recording identity"
                    }
                }

                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN -> {''',
)
replace(
    repo,
    '''                        kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE &&
                            recordingDao.isRecordingActive(ownerId) -> ownerId
                        kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN ->''',
    '''                        kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE &&
                            recordingDao.isRecordingActive(ownerId) -> ownerId
                        kind == TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE ->
                            recordingDao.findByRemoteIdentity(ownerId)?.id?.takeIf {
                                recordingDao.isRecordingActive(it)
                            }
                        kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN ->''',
)
replace(
    repo,
    '''            kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ||
                kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,''',
    '''            kind == TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE ||
                kind == TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE ||
                kind == TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,''',
)

# 2) Formal phone capture exposes epoch-local samples, while ordinary recording uses global WAV samples.
phone_runtime = 'app/src/main/java/io/github/ioannes78/voica/PhoneMicrophoneRecordingRuntime.kt'
replace(
    phone_runtime,
    'import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration\n',
    'import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration\nimport io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue\nimport io.github.ioannes78.voica.database.TimelineAnnotationRepository\n',
)
replace(
    phone_runtime,
    '    val writtenSamples: Long = 0L,\n    val postWriteOffers: Long = 0L,',
    '    val writtenSamples: Long = 0L,\n    val activeEpochSamples: Long = 0L,\n    val postWriteOffers: Long = 0L,',
)
replace(
    phone_runtime,
    '''    private val registerRecording: suspend (PhoneMicrophoneRecordingRegistration) -> String,
    private val nowInstant: () -> Instant = Instant::now,''',
    '''    private val registerRecording: suspend (PhoneMicrophoneRecordingRegistration) -> String,
    private val timelineAnnotationRepository: TimelineAnnotationRepository? = null,
    private val nowInstant: () -> Instant = Instant::now,''',
)
replace(
    phone_runtime,
    '''        try {
            registerRecording(registration)
        } catch (failure: Throwable) {
            return@withContext failStop(
                context,
                "录音文件已保存，但写入录音库失败：${failure.message.orEmpty()}",
                finalized.file,
            )
        }

        val interruptedMessage =''',
    '''        val registeredRecordingId =
            try {
                registerRecording(registration)
            } catch (failure: Throwable) {
                return@withContext failStop(
                    context,
                    "录音文件已保存，但写入录音库失败：${failure.message.orEmpty()}",
                    finalized.file,
                )
            }
        // Annotation promotion is best-effort here: a failure preserves PENDING_SESSION so the
        // startup reconciler can retry without damaging the successfully finalized recording.
        runCatching {
            timelineAnnotationRepository?.attachPendingOwner(
                ownerKind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                ownerId = context.recordingId,
                recordingId = registeredRecordingId,
            )
        }

        val interruptedMessage =''',
)
replace(
    phone_runtime,
    '                    writtenSamples = finalized.sampleCount,\n                    postWriteOffers = context.postWriteOffers,',
    '                    writtenSamples = finalized.sampleCount,\n                    activeEpochSamples = context.activeEpochSamples,\n                    postWriteOffers = context.postWriteOffers,',
)
replace_all(
    phone_runtime,
    '                writtenSamples = context.writtenSamples,\n                postWriteOffers = context.postWriteOffers,',
    '                writtenSamples = context.writtenSamples,\n                activeEpochSamples = context.activeEpochSamples,\n                postWriteOffers = context.postWriteOffers,',
)

realtime_capture = 'app/src/main/java/io/github/ioannes78/voica/RealtimeTimelineAnnotationCapture.kt'
replace(
    realtime_capture,
    '    if (recording.writtenSamples <= 0L) return null',
    '    if (recording.activeEpochSamples <= 0L) return null',
)
replace(
    realtime_capture,
    '        sampleIndex = recording.writtenSamples,',
    '        sampleIndex = recording.activeEpochSamples,',
)

write(
    'app/src/main/java/io/github/ioannes78/voica/ActiveTimelineAnnotationCapture.kt',
    '''package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.protocol.RecordingStatus

/** Exact active-phone target: writtenSamples is the final WAV coordinate and excludes pause time. */
internal fun phoneRecordingAnnotationTarget(
    recording: PhoneMicrophoneRecordingSnapshot,
): RealtimeAnnotationCaptureTarget? {
    if (
        recording.phase != PhoneMicrophoneRecordingPhase.RECORDING &&
        recording.phase != PhoneMicrophoneRecordingPhase.PAUSED
    ) {
        return null
    }
    val recordingId = recording.recordingId?.takeIf { it.isNotBlank() } ?: return null
    if (recording.writtenSamples <= 0L) return null
    return RealtimeAnnotationCaptureTarget(
        owner =
            TimelineAnnotationOwner.Pending(
                kind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                ownerId = recordingId,
            ),
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                sampleIndex = recording.writtenSamples,
            ),
    )
}

/**
 * Ordinary QS668 capture has no phone PCM clock. Use only fresh device-confirmed media duration and
 * exact active filename identity; never synthesize a position from UI/wall-clock elapsed time.
 * Device duration is second-granularity, so this is intentionally coarser than phone PCM capture.
 */
internal fun recorderRecordingAnnotationTarget(
    recording: RecordingDeviceState,
): RealtimeAnnotationCaptureTarget? {
    if (recording.freshness != RecordingFreshness.FRESH) return null
    if (
        recording.status != RecordingStatus.Recording &&
        recording.status != RecordingStatus.Paused
    ) {
        return null
    }
    val remoteIdentity = recording.filename?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val durationSeconds = recording.durationSeconds?.takeIf { it >= 0 } ?: return null
    val sampleIndex =
        runCatching {
            Math.multiplyExact(
                durationSeconds.toLong(),
                CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong(),
            )
        }.getOrNull() ?: return null
    return RealtimeAnnotationCaptureTarget(
        owner =
            TimelineAnnotationOwner.Pending(
                kind = TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE,
                ownerId = remoteIdentity,
            ),
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                sampleIndex = sampleIndex,
            ),
    )
}
''',
)

# 3) One composer for all capture/transcript surfaces.
write(
    'app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationComposerButton.kt',
    '''package io.github.ioannes78.voica.ui.annotation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.database.TimelineAnnotationEntity

private data class PendingAnnotationCamera(
    val target: TimelineAnnotationCameraCaptureTarget,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class PendingAnnotationGallery(
    val annotationId: String,
    val deleteAnnotationIfCancelled: Boolean,
)

/** Shared Stage 16A.2 composer: every + surface exposes the same four annotation actions. */
@Composable
internal fun TimelineAnnotationComposerButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    label: String = "＋",
    viewModel: TimelineAnnotationViewModel,
    onCreate: (
        noteText: String?,
        onCreated: (TimelineAnnotationEntity) -> Unit,
    ) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var pendingCamera by remember { mutableStateOf<PendingAnnotationCamera?>(null) }
    var pendingGallery by remember { mutableStateOf<PendingAnnotationGallery?>(null) }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
            val pending = pendingCamera ?: return@rememberLauncherForActivityResult
            pendingCamera = null
            viewModel.finishCamera(
                target = pending.target,
                accepted = accepted,
                deleteAnnotationIfCancelled = pending.deleteAnnotationIfCancelled,
            )
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val pending = pendingGallery ?: return@rememberLauncherForActivityResult
            pendingGallery = null
            if (uri != null) {
                viewModel.importGallery(pending.annotationId, uri)
            } else if (pending.deleteAnnotationIfCancelled) {
                viewModel.deleteAnnotation(pending.annotationId, silent = true)
            }
        }

    fun openCamera(annotationId: String) {
        viewModel.prepareCamera(annotationId) { target ->
            pendingCamera = PendingAnnotationCamera(target, deleteAnnotationIfCancelled = true)
            cameraLauncher.launch(target.uri)
        }
    }

    fun openGallery(annotationId: String) {
        pendingGallery = PendingAnnotationGallery(annotationId, deleteAnnotationIfCancelled = true)
        galleryLauncher.launch("image/*")
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = enabled,
        ) {
            Text(label)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("快速标记") },
                onClick = {
                    menuOpen = false
                    onCreate(null) {}
                },
            )
            DropdownMenuItem(
                text = { Text("文字笔记") },
                onClick = {
                    menuOpen = false
                    noteText = ""
                    noteOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text("拍照") },
                onClick = {
                    menuOpen = false
                    onCreate(null) { created -> openCamera(created.id) }
                },
            )
            DropdownMenuItem(
                text = { Text("从相册添加") },
                onClick = {
                    menuOpen = false
                    onCreate(null) { created -> openGallery(created.id) }
                },
            )
        }
    }

    if (noteOpen) {
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("文字笔记") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("记录此刻") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = noteText.isNotBlank(),
                    onClick = {
                        val value = noteText
                        noteOpen = false
                        onCreate(value) {}
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text("取消") }
            },
        )
    }
}
''',
)

write(
    'app/src/main/java/io/github/ioannes78/voica/ui/realtime/RealtimeTimelineAnnotationMenuButton.kt',
    '''package io.github.ioannes78.voica.ui.realtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource
import io.github.ioannes78.voica.RealtimeTimelineAnnotationCaptureResolver
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel

@Composable
internal fun RealtimeTimelineAnnotationMenuButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    source: RealtimeAnnotationCaptureSource,
    resolver: RealtimeTimelineAnnotationCaptureResolver,
    viewModel: TimelineAnnotationViewModel,
) {
    TimelineAnnotationComposerButton(
        modifier = modifier,
        enabled = enabled,
        viewModel = viewModel,
        onCreate = { noteText, onCreated ->
            viewModel.createAtRealtime(
                targetProvider = { resolver.resolve(source) },
                noteText = noteText,
                onCreated = onCreated,
            )
        },
    )
}
''',
)

vm = 'app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationViewModel.kt'
replace(
    vm,
    '''    internal fun createAtRealtime(
        targetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
        noteText: String? = null,
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        launchOperation {
            val target =
                targetProvider()
                    ?: error("当前暂无可安全标注的音频位置")
            val created =
                repository.create(
                    owner = target.owner,
                    anchor = target.anchor,
                    noteText = noteText,
                )
            mutableState.update { it.copy(notice = "已添加实时标注") }
            onCreated(created)
        }
    }
''',
    '''    internal fun createAtCapture(
        targetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
        noteText: String? = null,
        successNotice: String = "已添加标注",
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        launchOperation {
            val target =
                targetProvider()
                    ?: error("当前暂无可安全标注的音频位置")
            val created =
                repository.create(
                    owner = target.owner,
                    anchor = target.anchor,
                    noteText = noteText,
                )
            mutableState.update { current ->
                val item = TimelineAnnotationUiItem(created, emptyList())
                current.copy(
                    items = current.items.filterNot { it.annotation.id == created.id } + item,
                    notice = successNotice,
                )
            }
            onCreated(created)
        }
    }

    internal fun createAtRealtime(
        targetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
        noteText: String? = null,
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        createAtCapture(
            targetProvider = targetProvider,
            noteText = noteText,
            successNotice = "已添加实时标注",
            onCreated = onCreated,
        )
    }

    internal fun resetTransientItems() {
        mutableState.update { current ->
            if (current.recordingId == null) {
                current.copy(items = emptyList(), notice = null)
            } else {
                current
            }
        }
    }
''',
)

# 4) Realtime controls: + on the far right and visible in-page annotation feedback.
realtime_ui = 'app/src/main/java/io/github/ioannes78/voica/ui/realtime/RealtimeTranscriptionScreen.kt'
replace(
    realtime_ui,
    'import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel\n',
    'import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationUiItem\nimport io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel\n',
)
replace(
    realtime_ui,
    '''    LaunchedEffect(state.runId) {
        readerFollowMode = RealtimeReaderFollowMode.FOLLOW_LIVE
    }''',
    '''    LaunchedEffect(state.runId) {
        readerFollowMode = RealtimeReaderFollowMode.FOLLOW_LIVE
        annotationViewModel.resetTransientItems()
    }''',
)
replace(
    realtime_ui,
    '''        val visibleMessage = actionMessage ?: state.errorMessage
        if (!visibleMessage.isNullOrBlank()) {''',
    '''        if (annotationState.items.isNotEmpty()) {
            RealtimeAnnotationFeedback(annotationState.items)
            Spacer(modifier = Modifier.height(8.dp))
        }

        val visibleMessage = actionMessage ?: state.errorMessage
        if (!visibleMessage.isNullOrBlank()) {''',
)
replace(
    realtime_ui,
    '''        -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RealtimeTimelineAnnotationMenuButton(
                modifier = Modifier.weight(0.58f),
                enabled = !actionInFlight && !annotationBusy,
                source = annotationSource,
                resolver = annotationResolver,
                viewModel = annotationViewModel,
            )
            Button(
                onClick = if (state.phase == Stage16ARealtimeUiPhase.ACTIVE) onPause else onResume,''',
    '''        -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (state.phase == Stage16ARealtimeUiPhase.ACTIVE) onPause else onResume,''',
)
replace(
    realtime_ui,
    '''            ) { Text("停止") }
        }

        Stage16ARealtimeUiPhase.PREPARING,''',
    '''            ) { Text("停止") }
            RealtimeTimelineAnnotationMenuButton(
                modifier = Modifier.weight(0.58f),
                enabled = !actionInFlight && !annotationBusy,
                source = annotationSource,
                resolver = annotationResolver,
                viewModel = annotationViewModel,
            )
        }

        Stage16ARealtimeUiPhase.PREPARING,''',
)
replace(
    realtime_ui,
    '''internal fun realtimeStatusLabel(state: Stage16ARealtimeUiState): String = when {''',
    '''@Composable
private fun RealtimeAnnotationFeedback(items: List<TimelineAnnotationUiItem>) {
    val recent = items.takeLast(3)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "本次标注 · ${items.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            recent.forEach { item ->
                val seconds = item.annotation.anchorSampleIndex.coerceAtLeast(0L) / 16_000L
                val time = String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
                val content =
                    when {
                        !item.annotation.noteText.isNullOrBlank() -> item.annotation.noteText.orEmpty()
                        item.photoCount > 0 -> "图片 ${item.photoCount} 张"
                        else -> "快速标记"
                    }
                Text(
                    "$time · $content",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun realtimeStatusLabel(state: Stage16ARealtimeUiState): String = when {''',
)

# 5) Home active-capture surfaces get the same composer and lightweight feedback.
home_ui = 'app/src/main/java/io/github/ioannes78/voica/ui/device/HomeActivityPanel.kt'
replace(
    home_ui,
    'import androidx.compose.runtime.collectAsState\n',
    'import androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.collectAsState\n',
)
replace(
    home_ui,
    'import io.github.ioannes78.voica.protocol.RecordingGain\n',
    'import io.github.ioannes78.voica.RealtimeAnnotationCaptureTarget\nimport io.github.ioannes78.voica.protocol.RecordingGain\nimport io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton\nimport io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel\n',
)
replace(
    home_ui,
    '''    onOpenActivity: (AppActivityFact) -> Unit,
    onSelectSecondaryActivity: (AppActivityFact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val primary = projection.primaryActivity''',
    '''    onOpenActivity: (AppActivityFact) -> Unit,
    onSelectSecondaryActivity: (AppActivityFact) -> Unit,
    annotationViewModel: TimelineAnnotationViewModel,
    annotationTargetProvider: suspend (AppActivityFact) -> RealtimeAnnotationCaptureTarget?,
    modifier: Modifier = Modifier,
) {
    val annotationState by annotationViewModel.state.collectAsState()
    val primary = projection.primaryActivity
    LaunchedEffect(primary?.source, primary?.kind, primary?.mediaDisplayName) {
        annotationViewModel.resetTransientItems()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {''',
)
replace(
    home_ui,
    '''                onAction = onAction,
                onOpen = { onOpenActivity(primary) },
            )''',
    '''                onAction = onAction,
                onOpen = { onOpenActivity(primary) },
                annotationViewModel = annotationViewModel,
                annotationTargetProvider = annotationTargetProvider,
                annotationNotice = annotationState.notice,
            )''',
)
replace(
    home_ui,
    '''    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    onOpen: () -> Unit,
) {''',
    '''    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    onOpen: () -> Unit,
    annotationViewModel: TimelineAnnotationViewModel,
    annotationTargetProvider: suspend (AppActivityFact) -> RealtimeAnnotationCaptureTarget?,
    annotationNotice: String?,
) {''',
)
replace(
    home_ui,
    '''            TransportActions(activity, onAction)

            if (AppActivityAction.OPEN in activity.availableActions && clickable) {''',
    '''            TransportActions(
                activity = activity,
                onAction = onAction,
                annotationViewModel = annotationViewModel,
                annotationTargetProvider = annotationTargetProvider,
            )
            if (!annotationNotice.isNullOrBlank()) {
                Text(
                    annotationNotice,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (AppActivityAction.OPEN in activity.availableActions && clickable) {''',
)
replace(
    home_ui,
    '''private fun TransportActions(
    activity: AppActivityFact,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
) {''',
    '''private fun TransportActions(
    activity: AppActivityFact,
    onAction: (AppActivityFact, AppActivityAction) -> Unit,
    annotationViewModel: TimelineAnnotationViewModel,
    annotationTargetProvider: suspend (AppActivityFact) -> RealtimeAnnotationCaptureTarget?,
) {''',
)
replace(
    home_ui,
    '''            ) { Text(label) }
        }
    }
}''',
    '''            ) { Text(label) }
        }
        val supportsAnnotation =
            activity.kind == AppActivityKind.RECORDING ||
                activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION
        if (supportsAnnotation) {
            TimelineAnnotationComposerButton(
                modifier = Modifier.weight(0.58f),
                enabled = true,
                viewModel = annotationViewModel,
                onCreate = { noteText, onCreated ->
                    annotationViewModel.createAtCapture(
                        targetProvider = { annotationTargetProvider(activity) },
                        noteText = noteText,
                        onCreated = onCreated,
                    )
                },
            )
        }
    }
}''',
)

# 6) Wire one capture annotation VM and safe target providers into product surfaces.
device_screen = 'app/src/main/java/io/github/ioannes78/voica/ui/device/DeviceProductScreen.kt'
replace(
    device_screen,
    'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\n',
    'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\nimport androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel\n',
)
replace(
    device_screen,
    'import io.github.ioannes78.voica.AppRecordingNavigation\n',
    '''import io.github.ioannes78.voica.AppRecordingNavigation
import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource
import io.github.ioannes78.voica.RealtimeAnnotationCaptureTarget
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.phoneRecordingAnnotationTarget
import io.github.ioannes78.voica.recorderRecordingAnnotationTarget
''',
)
replace(
    device_screen,
    'import io.github.ioannes78.voica.ui.files.CompactDeviceFilesScreen\n',
    '''import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel
import io.github.ioannes78.voica.ui.files.CompactDeviceFilesScreen
''',
)
replace(
    device_screen,
    'import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionUiController\n',
    '''import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionUiController
import io.github.ioannes78.voica.ui.playback.TimelineAnnotationPlaybackCoordinator
''',
)
replace(
    device_screen,
    '''    val realtimeRuntime = LocalStage16ARealtimeRuntime.current
    val realtimeState by realtimeRuntime.state.collectAsState()

    val phoneRecordingRuntime =''',
    '''    val realtimeRuntime = LocalStage16ARealtimeRuntime.current
    val realtimeState by realtimeRuntime.state.collectAsState()
    val application = LocalContext.current.applicationContext as VoicaApplication
    val captureAnnotationPlaybackCoordinator =
        remember(
            application.container.playbackController,
            application.container.timelineAnnotationAnchorResolver,
        ) {
            TimelineAnnotationPlaybackCoordinator(
                controller = application.container.playbackController,
                resolver = application.container.timelineAnnotationAnchorResolver,
            )
        }
    val captureAnnotationViewModel: TimelineAnnotationViewModel =
        composeViewModel(
            key = "active-capture-annotation",
            factory =
                remember(
                    application.container.timelineAnnotationRepository,
                    captureAnnotationPlaybackCoordinator,
                    application.container.timelineAnnotationAttachmentStore,
                    application.container.timelineAnnotationContentCoordinator,
                ) {
                    TimelineAnnotationViewModel.Factory(
                        repository = application.container.timelineAnnotationRepository,
                        playbackCoordinator = captureAnnotationPlaybackCoordinator,
                        attachmentStore = application.container.timelineAnnotationAttachmentStore,
                        contentCoordinator = application.container.timelineAnnotationContentCoordinator,
                    )
                },
        )

    val phoneRecordingRuntime =''',
)
replace(
    device_screen,
    '''    fun showActionMessage(message: String?) {
        if (!message.isNullOrBlank()) phoneUiScope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun launchRealtimeAction''',
    '''    fun showActionMessage(message: String?) {
        if (!message.isNullOrBlank()) phoneUiScope.launch { snackbarHostState.showSnackbar(message) }
    }

    suspend fun resolveActivityAnnotationTarget(
        activity: AppActivityFact,
    ): RealtimeAnnotationCaptureTarget? =
        when {
            activity.kind == AppActivityKind.RECORDING &&
                activity.source == AppActivitySource.PHONE_MICROPHONE ->
                phoneRecordingAnnotationTarget(phoneRecordingRuntime.state.value)
            activity.kind == AppActivityKind.RECORDING &&
                activity.source == AppActivitySource.RECORDER_CARD ->
                recorderRecordingAnnotationTarget(viewModel.recordingState.value)
            activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION &&
                activity.source == AppActivitySource.PHONE_MICROPHONE ->
                application.container.realtimeTimelineAnnotationCaptureResolver.resolve(
                    RealtimeAnnotationCaptureSource.PHONE_MICROPHONE,
                )
            activity.kind == AppActivityKind.REALTIME_TRANSCRIPTION &&
                activity.source == AppActivitySource.RECORDER_CARD ->
                application.container.realtimeTimelineAnnotationCaptureResolver.resolve(
                    RealtimeAnnotationCaptureSource.RECORDER_CARD,
                )
            else -> null
        }

    fun launchRealtimeAction''',
)
replace(
    device_screen,
    '''                                onSelectSecondaryActivity = { activity ->
                                    homeSourcePreference = when (activity.source) {''',
    '''                                onSelectSecondaryActivity = { activity ->
                                    homeSourcePreference = when (activity.source) {''',
)
replace(
    device_screen,
    '''                                        else -> effectiveSourcePreference
                                    }
                                },
                            )''',
    '''                                        else -> effectiveSourcePreference
                                    }
                                },
                                annotationViewModel = captureAnnotationViewModel,
                                annotationTargetProvider = ::resolveActivityAnnotationTarget,
                            )''',
)
replace(
    device_screen,
    '''            DevicePage.PHONE_RECORDING -> PhoneMicrophoneRecordingScreen(
                padding = padding,
                controller = phoneRecordingController,
                onBack = { page = DevicePage.HOME },
            )''',
    '''            DevicePage.PHONE_RECORDING -> PhoneMicrophoneRecordingScreen(
                padding = padding,
                controller = phoneRecordingController,
                annotationViewModel = captureAnnotationViewModel,
                annotationTargetProvider = {
                    phoneRecordingAnnotationTarget(phoneRecordingRuntime.state.value)
                },
                onBack = { page = DevicePage.HOME },
            )''',
)

# 7) Detailed ordinary phone recording screen mirrors Home: pause/stop/+ and feedback.
phone_ui = 'app/src/main/java/io/github/ioannes78/voica/ui/phone/PhoneMicrophoneProductUi.kt'
replace(
    phone_ui,
    'import androidx.compose.runtime.DisposableEffect\n',
    'import androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.LaunchedEffect\n',
)
replace(
    phone_ui,
    'import io.github.ioannes78.voica.PhoneMicrophoneCanonicalPostWriteSink\n',
    'import io.github.ioannes78.voica.PhoneMicrophoneCanonicalPostWriteSink\nimport io.github.ioannes78.voica.RealtimeAnnotationCaptureTarget\n',
)
replace(
    phone_ui,
    'import io.github.ioannes78.voica.ui.realtime.RealtimeAudioActivityWaveform\n',
    '''import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel
import io.github.ioannes78.voica.ui.realtime.RealtimeAudioActivityWaveform
''',
)
replace(
    phone_ui,
    '''internal fun PhoneMicrophoneRecordingScreen(
    padding: PaddingValues,
    controller: PhoneMicrophoneRecordingUiController,
    onBack: () -> Unit,
) {''',
    '''internal fun PhoneMicrophoneRecordingScreen(
    padding: PaddingValues,
    controller: PhoneMicrophoneRecordingUiController,
    annotationViewModel: TimelineAnnotationViewModel,
    annotationTargetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
    onBack: () -> Unit,
) {''',
)
replace(
    phone_ui,
    '''    val captureInterruption = phoneMicrophoneCaptureInterruptionMessage(recording, capture)
    val scope = rememberCoroutineScope()''',
    '''    val captureInterruption = phoneMicrophoneCaptureInterruptionMessage(recording, capture)
    val annotationState by annotationViewModel.state.collectAsState()
    LaunchedEffect(recording.recordingId) {
        if (recording.recordingId != null) annotationViewModel.resetTransientItems()
    }
    val scope = rememberCoroutineScope()''',
)
replace(
    phone_ui,
    '''                !permissionMessage.isNullOrBlank() -> permissionMessage
                else -> actionMessage
            }''',
    '''                !permissionMessage.isNullOrBlank() -> permissionMessage
                !actionMessage.isNullOrBlank() -> actionMessage
                else -> annotationState.notice
            }''',
)
replace(
    phone_ui,
    '''                onResume = { launch(controller::resume) },
                onStop = { launch(controller::stop) },
            )''',
    '''                onResume = { launch(controller::resume) },
                onStop = { launch(controller::stop) },
                annotationViewModel = annotationViewModel,
                annotationTargetProvider = annotationTargetProvider,
            )''',
)
replace(
    phone_ui,
    '''private fun PhoneMicrophoneRecordingControls(
    phase: PhoneMicrophoneRecordingPhase,
    enabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {''',
    '''private fun PhoneMicrophoneRecordingControls(
    phase: PhoneMicrophoneRecordingPhase,
    enabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    annotationViewModel: TimelineAnnotationViewModel,
    annotationTargetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
) {''',
)
replace(
    phone_ui,
    '''            ) { Text("停止") }
        }

        PhoneMicrophoneRecordingPhase.STARTING,''',
    '''            ) { Text("停止") }
            TimelineAnnotationComposerButton(
                modifier = Modifier.weight(0.58f),
                enabled = enabled,
                viewModel = annotationViewModel,
                onCreate = { noteText, onCreated ->
                    annotationViewModel.createAtCapture(
                        targetProvider = annotationTargetProvider,
                        noteText = noteText,
                        onCreated = onCreated,
                    )
                },
            )
        }

        PhoneMicrophoneRecordingPhase.STARTING,''',
)

# 8) Container promotion wiring for ordinary phone and recorder captures.
container = 'app/src/main/java/io/github/ioannes78/voica/VoicaApplication.kt'
replace(
    container,
    '''            onRegistered = canonicalAudioCoordinator::requestAutomatic,
        )''',
    '''            onRegistered = { recordingId ->
                canonicalAudioCoordinator.requestAutomatic(recordingId)
                applicationScope.launch {
                    runCatching { timelineAnnotationRepository.reconcilePending() }
                }
            },
        )''',
)
replace(
    container,
    '''            recordingsRoot = recordingsRoot,
            registerRecording = phoneMicrophoneRecordingRegistrar::register,
        )''',
    '''            recordingsRoot = recordingsRoot,
            registerRecording = phoneMicrophoneRecordingRegistrar::register,
            timelineAnnotationRepository = timelineAnnotationRepository,
        )''',
)

# 9) Tests freeze epoch-local phone realtime and global ordinary-capture semantics.
qa_test = 'app/src/test/java/io/github/ioannes78/voica/RealtimeTimelineAnnotationCaptureTest.kt'
replace(
    qa_test,
    '    fun phoneUsesFormalWavWrittenSamplesAndPhoneCapturePendingOwner() {',
    '    fun phoneRealtimeUsesCurrentEpochLocalSamplesAndPhoneCapturePendingOwner() {',
)
replace(
    qa_test,
    '''                        activeEpochId = 4L,
                        writtenSamples = 48_000L,
                    ),''',
    '''                        activeEpochId = 4L,
                        writtenSamples = 48_000L,
                        activeEpochSamples = 16_000L,
                    ),''',
)
replace(
    qa_test,
    '        assertEquals(48_000L, boundary.sampleIndex)',
    '        assertEquals(16_000L, boundary.sampleIndex)',
)
replace(
    qa_test,
    '        assertEquals(48_000L, target.anchor.sampleIndex)',
    '        assertEquals(16_000L, target.anchor.sampleIndex)',
)

write(
    'app/src/test/java/io/github/ioannes78/voica/ActiveTimelineAnnotationCaptureTest.kt',
    '''package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTimelineAnnotationCaptureTest {
    @Test
    fun ordinaryPhoneUsesFinalWavGlobalSampleCoordinateAcrossPauseResume() {
        val target =
            phoneRecordingAnnotationTarget(
                PhoneMicrophoneRecordingSnapshot(
                    phase = PhoneMicrophoneRecordingPhase.RECORDING,
                    recordingId = "phone-1",
                    writtenSamples = 48_000L,
                    activeEpochSamples = 16_000L,
                ),
            )
        requireNotNull(target)
        assertEquals(TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE, target.owner.kind)
        assertEquals("phone-1", target.owner.ownerId)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, target.anchor.domain)
        assertEquals(48_000L, target.anchor.sampleIndex)
    }

    @Test
    fun ordinaryRecorderUsesOnlyFreshDeviceMediaProgressAndExactFilenameIdentity() {
        val target =
            recorderRecordingAnnotationTarget(
                RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note20261018-120000.wav",
                    freshness = RecordingFreshness.FRESH,
                ),
            )
        requireNotNull(target)
        assertEquals(TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE, target.owner.kind)
        assertEquals("note20261018-120000.wav", target.owner.ownerId)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, target.anchor.domain)
        assertEquals(192_000L, target.anchor.sampleIndex)
    }

    @Test
    fun ordinaryRecorderRejectsStaleOrIdentityLessStateInsteadOfUsingUiElapsedTime() {
        assertNull(
            recorderRecordingAnnotationTarget(
                RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note.wav",
                    freshness = RecordingFreshness.STALE,
                ),
            ),
        )
        assertNull(
            recorderRecordingAnnotationTarget(
                RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = null,
                    freshness = RecordingFreshness.FRESH,
                ),
            ),
        )
    }
}
''',
)

print('Stage 16A.2 revision wave A patch applied')
