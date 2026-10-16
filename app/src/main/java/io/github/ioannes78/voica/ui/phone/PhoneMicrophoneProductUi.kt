package io.github.ioannes78.voica.ui.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ioannes78.voica.PhoneMicrophoneCanonicalPostWriteSink
import io.github.ioannes78.voica.PhoneMicrophoneCapturePhase
import io.github.ioannes78.voica.PhoneMicrophoneCaptureSnapshot
import io.github.ioannes78.voica.PhoneMicrophonePermissionAction
import io.github.ioannes78.voica.PhoneMicrophonePermissionPolicy
import io.github.ioannes78.voica.PhoneMicrophonePermissionRequestStore
import io.github.ioannes78.voica.PhoneMicrophoneRealtimeRuntime
import io.github.ioannes78.voica.PhoneMicrophoneRecordingActionResult
import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingRuntime
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.PhoneMicrophoneRecordingStartResult
import io.github.ioannes78.voica.PhoneMicrophoneRecordingStopResult
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.ui.realtime.RealtimeAudioActivityWaveform
import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionEntryCard
import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionScreen
import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionUiController
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal val LocalPhoneMicrophoneRecordingRuntime = staticCompositionLocalOf<PhoneMicrophoneRecordingRuntime> {
    error("PhoneMicrophoneRecordingRuntime was not provided")
}

internal val LocalPhoneMicrophoneRealtimeRuntime = staticCompositionLocalOf<PhoneMicrophoneRealtimeRuntime> {
    error("PhoneMicrophoneRealtimeRuntime was not provided")
}

internal data class PhoneMicrophoneRecordingUiState(
    val recording: PhoneMicrophoneRecordingSnapshot = PhoneMicrophoneRecordingSnapshot(),
    val capture: PhoneMicrophoneCaptureSnapshot = PhoneMicrophoneCaptureSnapshot(),
    val audioActivityLevels: List<Float> = emptyList(),
    val ownedByRealtime: Boolean = false,
)

internal data class PhoneMicrophoneRecordingUiActionOutcome(
    val success: Boolean,
    val message: String? = null,
)

internal class PhoneMicrophoneRecordingUiController(
    private val recordingRuntime: PhoneMicrophoneRecordingRuntime,
    private val realtimeRuntime: PhoneMicrophoneRealtimeRuntime,
    scope: CoroutineScope,
) {
    private val waveformSink = PhoneMicrophoneRecordingWaveformSink()
    val state: StateFlow<PhoneMicrophoneRecordingUiState> =
        combine(recordingRuntime.state, recordingRuntime.captureState, waveformSink.levels, realtimeRuntime.state) { recording, capture, levels, _ ->
            PhoneMicrophoneRecordingUiState(
                recording = recording,
                capture = capture,
                audioActivityLevels = levels,
                ownedByRealtime = realtimeRuntime.snapshot().activeRunId != null,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = PhoneMicrophoneRecordingUiState(
                recording = recordingRuntime.state.value,
                capture = recordingRuntime.captureState.value,
                audioActivityLevels = waveformSink.levels.value,
                ownedByRealtime = realtimeRuntime.snapshot().activeRunId != null,
            ),
        )

    suspend fun start(): PhoneMicrophoneRecordingUiActionOutcome {
        if (realtimeOwnsRecording()) return realtimeOwnershipFailure()
        waveformSink.reset()
        return when (val result = recordingRuntime.start(postWriteSink = waveformSink)) {
            is PhoneMicrophoneRecordingStartResult.Started -> PhoneMicrophoneRecordingUiActionOutcome(success = true)
            is PhoneMicrophoneRecordingStartResult.Rejected -> PhoneMicrophoneRecordingUiActionOutcome(false, result.message)
        }
    }

    suspend fun pause(): PhoneMicrophoneRecordingUiActionOutcome {
        if (realtimeOwnsRecording()) return realtimeOwnershipFailure()
        return recordingRuntime.pause().toUiOutcome()
    }

    suspend fun resume(): PhoneMicrophoneRecordingUiActionOutcome {
        if (realtimeOwnsRecording()) return realtimeOwnershipFailure()
        return recordingRuntime.resume().toUiOutcome()
    }

    suspend fun stop(): PhoneMicrophoneRecordingUiActionOutcome {
        if (realtimeOwnsRecording()) return realtimeOwnershipFailure()
        return when (val result = recordingRuntime.stop()) {
            is PhoneMicrophoneRecordingStopResult.Saved -> PhoneMicrophoneRecordingUiActionOutcome(true, "录音已保存到录音库")
            PhoneMicrophoneRecordingStopResult.Empty -> PhoneMicrophoneRecordingUiActionOutcome(false, "没有录到有效音频")
            is PhoneMicrophoneRecordingStopResult.Failed -> PhoneMicrophoneRecordingUiActionOutcome(false, result.message)
        }
    }

    private fun realtimeOwnsRecording(): Boolean = realtimeRuntime.snapshot().activeRunId != null
    private fun realtimeOwnershipFailure() = PhoneMicrophoneRecordingUiActionOutcome(false, "手机实时转写正在使用麦克风，请在实时转写页面操作")
    private fun PhoneMicrophoneRecordingActionResult.toUiOutcome(): PhoneMicrophoneRecordingUiActionOutcome = when (this) {
        PhoneMicrophoneRecordingActionResult.Success -> PhoneMicrophoneRecordingUiActionOutcome(true)
        is PhoneMicrophoneRecordingActionResult.Rejected -> PhoneMicrophoneRecordingUiActionOutcome(false, message)
    }
}

private class PhoneMicrophoneRecordingWaveformSink : PhoneMicrophoneCanonicalPostWriteSink {
    private val mutableLevels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = mutableLevels
    override fun offer(frame: RealtimeCanonicalPcmFrame) {
        if (frame.samples.isEmpty()) return
        var squareSum = 0.0
        frame.samples.forEach { sample ->
            val normalized = sample.toDouble() / Short.MAX_VALUE.toDouble()
            squareSum += normalized * normalized
        }
        val rms = sqrt(squareSum / frame.samples.size.toDouble()).toFloat().coerceIn(0f, 1f)
        mutableLevels.update { current -> if (current.size >= WAVEFORM_WINDOW) current.drop(1) + rms else current + rms }
    }
    fun reset() { mutableLevels.value = emptyList() }
    private companion object { const val WAVEFORM_WINDOW = 28 }
}

internal fun phoneMicrophoneCaptureInterruptionMessage(
    recording: PhoneMicrophoneRecordingSnapshot,
    capture: PhoneMicrophoneCaptureSnapshot,
): String? {
    if (recording.phase != PhoneMicrophoneRecordingPhase.RECORDING || capture.phase != PhoneMicrophoneCapturePhase.FAILED) return null
    val detail = capture.lastFailure?.trim()?.takeIf { it.isNotEmpty() }
    return if (detail == null) "麦克风采集中断，可先暂停后继续，或停止保存已录音频"
    else "麦克风采集中断，可先暂停后继续，或停止保存已录音频：$detail"
}

@Composable
internal fun PhoneMicrophoneRecordingEntryCard(state: PhoneMicrophoneRecordingUiState, onClick: () -> Unit) {
    val recording = state.recording
    val captureInterruption = phoneMicrophoneCaptureInterruptionMessage(recording, state.capture)
    val supporting = when {
        state.ownedByRealtime -> "手机实时转写正在使用麦克风"
        captureInterruption != null -> captureInterruption
        recording.phase == PhoneMicrophoneRecordingPhase.RECORDING -> "录音中 · ${formatPhoneRecordingDuration(recording.durationMs)}"
        recording.phase == PhoneMicrophoneRecordingPhase.PAUSED -> "已暂停 · ${formatPhoneRecordingDuration(recording.durationMs)}"
        recording.phase == PhoneMicrophoneRecordingPhase.STOPPING -> "正在保存录音…"
        recording.phase == PhoneMicrophoneRecordingPhase.COMPLETED -> "已保存到录音库"
        recording.phase == PhoneMicrophoneRecordingPhase.FAILED -> recording.lastFailure ?: "上次录音未完成"
        else -> "本地录音 · 16 kHz · 单声道"
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text("手机录音") },
            supportingContent = { Text(supporting, maxLines = 1) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        )
    }
}

@Composable
internal fun PhoneMicrophoneRealtimeEntryCard(
    controller: RealtimeTranscriptionUiController,
    canStart: Boolean,
    onClick: () -> Unit,
) {
    val state by controller.state.collectAsState()
    RealtimeTranscriptionEntryCard(
        state = state,
        canStart = canStart,
        onClick = onClick,
        headline = "手机实时转写",
        unavailableMessage = "当前手机录音正在使用麦克风，请先完成后再启动实时转写",
        sourceLabel = "手机麦克风",
    )
}

@Composable
internal fun PhoneMicrophoneRecordingScreen(
    padding: PaddingValues,
    controller: PhoneMicrophoneRecordingUiController,
    onBack: () -> Unit,
) {
    val uiState by controller.state.collectAsState()
    val recording = uiState.recording
    val capture = uiState.capture
    val captureInterruption = phoneMicrophoneCaptureInterruptionMessage(recording, capture)
    val scope = rememberCoroutineScope()
    var actionInFlight by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    fun launch(action: suspend () -> PhoneMicrophoneRecordingUiActionOutcome) {
        if (actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = action()
            actionInFlight = false
            actionMessage = result.message
        }
    }
    PhoneMicrophonePermissionGate(onPermissionReady = { launch(controller::start) }) { requestStart, permissionMessage ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            PhoneMicrophoneHeader(
                title = "手机录音",
                status = if (captureInterruption != null) "采集中断" else phoneRecordingStatusLabel(recording.phase),
                onBack = onBack,
            )
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))
            Text(formatPhoneRecordingDuration(recording.durationMs), style = MaterialTheme.typography.displaySmall)
            Text(
                recording.displayName ?: "手机麦克风 · 16 kHz · 单声道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val captureSummary = phoneMicrophoneCaptureSummary(capture)
            if (captureSummary.isNotBlank()) {
                Text(captureSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            RealtimeAudioActivityWaveform(
                levels = uiState.audioActivityLevels,
                active = recording.phase == PhoneMicrophoneRecordingPhase.RECORDING && capture.phase == PhoneMicrophoneCapturePhase.RECORDING,
            )
            Spacer(modifier = Modifier.weight(1f))
            val message = when {
                uiState.ownedByRealtime -> "手机实时转写正在使用麦克风，请在实时转写页面操作"
                captureInterruption != null -> captureInterruption
                !recording.lastFailure.isNullOrBlank() -> recording.lastFailure
                !permissionMessage.isNullOrBlank() -> permissionMessage
                else -> actionMessage
            }
            if (!message.isNullOrBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.ownedByRealtime || captureInterruption != null || recording.phase == PhoneMicrophoneRecordingPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PhoneMicrophoneRecordingControls(
                phase = recording.phase,
                enabled = !actionInFlight && !uiState.ownedByRealtime,
                onStart = requestStart,
                onPause = { launch(controller::pause) },
                onResume = { launch(controller::resume) },
                onStop = { launch(controller::stop) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun PhoneMicrophoneRealtimeScreen(
    padding: PaddingValues,
    controller: RealtimeTranscriptionUiController,
    canStart: Boolean,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var startMessage by remember { mutableStateOf<String?>(null) }
    val needsPermissionPreflight = state.phase in setOf(
        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
    ) && !state.recordingOnlyMode && !PhoneMicrophonePermissionPolicy.hasPermission(LocalContext.current)
    if (!needsPermissionPreflight) {
        RealtimeTranscriptionScreen(
            padding = padding,
            controller = controller,
            canStart = canStart,
            onBack = onBack,
            title = "手机实时转写",
            sourceLabel = "手机麦克风",
            startButtonLabel = "开始手机实时转写",
            qaScenarioLabel = "S8",
        )
        return
    }
    PhoneMicrophonePermissionGate(
        onPermissionReady = { scope.launch { startMessage = controller.start().message } },
    ) { requestStart, permissionMessage ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            PhoneMicrophoneHeader("手机实时转写", "等待麦克风权限", onBack)
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))
            Text("本地录音 · 实时转写", style = MaterialTheme.typography.titleMedium)
            Text(
                "开始后会同时保存正式 WAV，并使用当前已选择的本地实时模型转写。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            val message = permissionMessage ?: startMessage
            if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = requestStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text("允许麦克风并开始") }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PhoneMicrophonePermissionGate(
    onPermissionReady: () -> Unit,
    content: @Composable (requestStart: () -> Unit, message: String?) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context.applicationContext) { PhoneMicrophonePermissionRequestStore(context) }
    val latestOnReady by rememberUpdatedState(onPermissionReady)
    var showRationale by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lifecycleTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) lifecycleTick += 1 }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            message = null
            latestOnReady()
        } else {
            val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false
            message = PhoneMicrophonePermissionPolicy.resolve(false, true, rationale).message
        }
    }
    fun launchPermissionRequest() {
        store.markRequested()
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val requestStart = {
        @Suppress("UNUSED_VARIABLE") val resumedRevision = lifecycleTick
        val granted = PhoneMicrophonePermissionPolicy.hasPermission(context)
        val shouldShowRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false
        val decision = PhoneMicrophonePermissionPolicy.resolve(granted, store.requestedBefore(), shouldShowRationale)
        when (decision.action) {
            PhoneMicrophonePermissionAction.READY -> { message = null; latestOnReady() }
            PhoneMicrophonePermissionAction.REQUEST_PERMISSION -> { message = decision.message; launchPermissionRequest() }
            PhoneMicrophonePermissionAction.SHOW_RATIONALE_AND_REQUEST -> { message = decision.message; showRationale = true }
            PhoneMicrophonePermissionAction.OPEN_APP_SETTINGS -> { message = decision.message; context.startActivity(PhoneMicrophonePermissionPolicy.appSettingsIntent(context)) }
        }
    }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("允许麦克风") },
            text = { Text("Voica 只会在你主动开始手机录音或手机实时转写时使用麦克风。") },
            confirmButton = { TextButton(onClick = { showRationale = false; launchPermissionRequest() }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("暂不") } },
        )
    }
    content(requestStart, message)
}

@Composable
private fun PhoneMicrophoneHeader(title: String, status: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("手机麦克风 · 16 kHz · 单声道", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhoneMicrophoneRecordingControls(
    phase: PhoneMicrophoneRecordingPhase,
    enabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    when (phase) {
        PhoneMicrophoneRecordingPhase.IDLE,
        PhoneMicrophoneRecordingPhase.COMPLETED,
        PhoneMicrophoneRecordingPhase.EMPTY,
        PhoneMicrophoneRecordingPhase.FAILED,
        -> Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("开始录音") }

        PhoneMicrophoneRecordingPhase.RECORDING,
        PhoneMicrophoneRecordingPhase.PAUSED,
        -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (phase == PhoneMicrophoneRecordingPhase.RECORDING) onPause else onResume,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(if (phase == PhoneMicrophoneRecordingPhase.RECORDING) "暂停" else "继续") }
            Button(
                onClick = onStop,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("停止") }
        }

        PhoneMicrophoneRecordingPhase.STARTING,
        PhoneMicrophoneRecordingPhase.PAUSING,
        PhoneMicrophoneRecordingPhase.RESUMING,
        PhoneMicrophoneRecordingPhase.STOPPING,
        -> Text(
            phoneRecordingStatusLabel(phase),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PhoneMicrophoneRecordingPhase.CLOSED -> Text(
            "手机录音当前不可用",
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

internal fun phoneRecordingStatusLabel(phase: PhoneMicrophoneRecordingPhase): String = when (phase) {
    PhoneMicrophoneRecordingPhase.IDLE -> "未开始"
    PhoneMicrophoneRecordingPhase.STARTING -> "正在启动"
    PhoneMicrophoneRecordingPhase.RECORDING -> "录音中"
    PhoneMicrophoneRecordingPhase.PAUSING -> "正在暂停"
    PhoneMicrophoneRecordingPhase.PAUSED -> "已暂停"
    PhoneMicrophoneRecordingPhase.RESUMING -> "正在继续"
    PhoneMicrophoneRecordingPhase.STOPPING -> "正在保存"
    PhoneMicrophoneRecordingPhase.COMPLETED -> "已完成"
    PhoneMicrophoneRecordingPhase.EMPTY -> "无有效音频"
    PhoneMicrophoneRecordingPhase.FAILED -> "录音异常"
    PhoneMicrophoneRecordingPhase.CLOSED -> "不可用"
}

private fun phoneMicrophoneCaptureSummary(capture: PhoneMicrophoneCaptureSnapshot): String {
    val source = capture.selectedAudioSource?.let { "手机麦克风" }
    val route = capture.route?.productName?.trim()?.takeIf { it.isNotEmpty() }
    val silenced = if (capture.clientSilenced == true) "输入被系统静音" else null
    return listOfNotNull(source, route, silenced).joinToString(" · ")
}

internal fun formatPhoneRecordingDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
