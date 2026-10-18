from pathlib import Path


def one(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return source.replace(old, new, 1)


# 1) Reuse TimelineAnnotationViewModel durable create path for pending realtime owners.
path = Path("app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationViewModel.kt")
s = path.read_text()
s = one(
    s,
    'import io.github.ioannes78.voica.TimelineAnnotationContentCoordinator\n',
    'import io.github.ioannes78.voica.RealtimeAnnotationCaptureTarget\nimport io.github.ioannes78.voica.TimelineAnnotationContentCoordinator\n',
    'realtime target import',
)
realtime_method = '''    fun createAtRealtime(
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

'''
s = one(
    s,
    '    fun updateNote(annotationId: String, noteText: String?) {',
    realtime_method + '    fun updateNote(annotationId: String, noteText: String?) {',
    'realtime create method',
)
path.write_text(s)


# 2) Keep realtime attachment/menu UI isolated from transcript reader/control implementation.
menu = Path("app/src/main/java/io/github/ioannes78/voica/ui/realtime/RealtimeTimelineAnnotationMenuButton.kt")
if menu.exists():
    raise SystemExit("RealtimeTimelineAnnotationMenuButton.kt already exists")
menu.write_text('''package io.github.ioannes78.voica.ui.realtime

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
import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource
import io.github.ioannes78.voica.RealtimeTimelineAnnotationCaptureResolver
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel

private data class RealtimePendingCamera(
    val target: TimelineAnnotationCameraCaptureTarget,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class RealtimePendingGallery(
    val annotationId: String,
    val deleteAnnotationIfCancelled: Boolean,
)

@Composable
internal fun RealtimeTimelineAnnotationMenuButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    source: RealtimeAnnotationCaptureSource,
    resolver: RealtimeTimelineAnnotationCaptureResolver,
    viewModel: TimelineAnnotationViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var pendingCamera by remember { mutableStateOf<RealtimePendingCamera?>(null) }
    var pendingGallery by remember { mutableStateOf<RealtimePendingGallery?>(null) }

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

    fun resolveTarget() = resolver.resolve(source)

    fun openCamera(annotationId: String) {
        viewModel.prepareCamera(annotationId) { target ->
            pendingCamera =
                RealtimePendingCamera(
                    target = target,
                    deleteAnnotationIfCancelled = true,
                )
            cameraLauncher.launch(target.uri)
        }
    }

    fun openGallery(annotationId: String) {
        pendingGallery =
            RealtimePendingGallery(
                annotationId = annotationId,
                deleteAnnotationIfCancelled = true,
            )
        galleryLauncher.launch("image/*")
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = enabled,
        ) {
            Text("＋")
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("快速标记") },
                onClick = {
                    menuOpen = false
                    viewModel.createAtRealtime(::resolveTarget)
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
                    viewModel.createAtRealtime(::resolveTarget) { created ->
                        openCamera(created.id)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("从相册添加") },
                onClick = {
                    menuOpen = false
                    viewModel.createAtRealtime(::resolveTarget) { created ->
                        openGallery(created.id)
                    }
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
                        viewModel.createAtRealtime(
                            targetProvider = ::resolveTarget,
                            noteText = value,
                        )
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text("取消") }
            },
        )
    }
}
''')


# 3) Wire a unique-key annotation VM into realtime screen and add the plus control only in ACTIVE/PAUSED.
path = Path("app/src/main/java/io/github/ioannes78/voica/ui/realtime/RealtimeTranscriptionScreen.kt")
s = path.read_text()
s = one(
    s,
    'import androidx.compose.runtime.setValue\nimport androidx.compose.ui.Alignment\n',
    'import androidx.compose.runtime.setValue\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.platform.LocalContext\nimport androidx.lifecycle.viewmodel.compose.viewModel\n',
    'compose viewmodel imports',
)
s = one(
    s,
    'import io.github.ioannes78.voica.Stage16ARealtimeUiPhase\nimport io.github.ioannes78.voica.Stage16ARealtimeUiState\n',
    'import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource\nimport io.github.ioannes78.voica.Stage16ARealtimeUiPhase\nimport io.github.ioannes78.voica.Stage16ARealtimeUiState\nimport io.github.ioannes78.voica.VoicaApplication\n',
    'realtime annotation imports',
)
s = one(
    s,
    'import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRecordingRuntime\n',
    'import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel\nimport io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRecordingRuntime\nimport io.github.ioannes78.voica.ui.playback.TimelineAnnotationPlaybackCoordinator\n',
    'annotation vm imports',
)
old_activity = '''    val activitySource =
        if (controller is PhoneMicrophoneRealtimeUiController) {
            AppActivitySource.PHONE_MICROPHONE
        } else {
            AppActivitySource.RECORDER_CARD
        }
    val detailSourceLabel ='''
new_activity = '''    val activitySource =
        if (controller is PhoneMicrophoneRealtimeUiController) {
            AppActivitySource.PHONE_MICROPHONE
        } else {
            AppActivitySource.RECORDER_CARD
        }
    val annotationSource =
        if (activitySource == AppActivitySource.PHONE_MICROPHONE) {
            RealtimeAnnotationCaptureSource.PHONE_MICROPHONE
        } else {
            RealtimeAnnotationCaptureSource.RECORDER_CARD
        }
    val application = LocalContext.current.applicationContext as VoicaApplication
    val annotationPlaybackCoordinator =
        remember(
            application.container.playbackController,
            application.container.timelineAnnotationAnchorResolver,
        ) {
            TimelineAnnotationPlaybackCoordinator(
                controller = application.container.playbackController,
                resolver = application.container.timelineAnnotationAnchorResolver,
            )
        }
    val annotationViewModel: TimelineAnnotationViewModel =
        viewModel(
            key = "realtime-annotation-${activitySource.name}",
            factory =
                remember(
                    application.container.timelineAnnotationRepository,
                    annotationPlaybackCoordinator,
                    application.container.timelineAnnotationAttachmentStore,
                    application.container.timelineAnnotationContentCoordinator,
                ) {
                    TimelineAnnotationViewModel.Factory(
                        repository = application.container.timelineAnnotationRepository,
                        playbackCoordinator = annotationPlaybackCoordinator,
                        attachmentStore = application.container.timelineAnnotationAttachmentStore,
                        contentCoordinator = application.container.timelineAnnotationContentCoordinator,
                    )
                },
        )
    val annotationState by annotationViewModel.state.collectAsState()
    val detailSourceLabel ='''
s = one(s, old_activity, new_activity, 'realtime annotation vm wiring')
old_message = '''        val visibleMessage = actionMessage ?: state.errorMessage
        if (!visibleMessage.isNullOrBlank()) {
            Text(visibleMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }

        RealtimeControls('''
new_message = '''        val visibleMessage = actionMessage ?: state.errorMessage
        if (!visibleMessage.isNullOrBlank()) {
            Text(visibleMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }
        annotationState.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { annotationViewModel.dismissNotice() },
            )
        }

        RealtimeControls('''
s = one(s, old_message, new_message, 'annotation notice')
s = one(
    s,
    '''            startButtonLabel = startButtonLabel,
            onStart = { launchAction(controller::start) },''',
    '''            startButtonLabel = startButtonLabel,
            annotationBusy = annotationState.busy,
            annotationSource = annotationSource,
            annotationResolver = application.container.realtimeTimelineAnnotationCaptureResolver,
            annotationViewModel = annotationViewModel,
            onStart = { launchAction(controller::start) },''',
    'control annotation args',
)
s = one(
    s,
    '''    startButtonLabel: String,
    onStart: () -> Unit,''',
    '''    startButtonLabel: String,
    annotationBusy: Boolean,
    annotationSource: RealtimeAnnotationCaptureSource,
    annotationResolver: io.github.ioannes78.voica.RealtimeTimelineAnnotationCaptureResolver,
    annotationViewModel: TimelineAnnotationViewModel,
    onStart: () -> Unit,''',
    'control signature',
)
old_active_row = '''        -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (state.phase == Stage16ARealtimeUiPhase.ACTIVE) onPause else onResume,
                enabled = !actionInFlight,
                modifier = Modifier.weight(1f),'''
new_active_row = '''        -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RealtimeTimelineAnnotationMenuButton(
                modifier = Modifier.weight(0.58f),
                enabled = !actionInFlight && !annotationBusy,
                source = annotationSource,
                resolver = annotationResolver,
                viewModel = annotationViewModel,
            )
            Button(
                onClick = if (state.phase == Stage16ARealtimeUiPhase.ACTIVE) onPause else onResume,
                enabled = !actionInFlight,
                modifier = Modifier.weight(1f),'''
s = one(s, old_active_row, new_active_row, 'three control row')
path.write_text(s)
