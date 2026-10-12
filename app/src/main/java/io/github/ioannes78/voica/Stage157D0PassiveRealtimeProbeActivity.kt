package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stage 15.7-D0 QA-only fact probe.
 *
 * This surface deliberately never calls RealtimeAudioRuntime START/STOP/PAUSE/RESUME. It only
 * drives the existing TYPE=3 recording lifecycle through DeviceRepository and observes whether
 * TYPE=1 audio still arrives at the runtime's default-closed command fence.
 */
class Stage157D0PassiveRealtimeProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime

    private val mutableBaseline = MutableStateFlow<Stage157D0Baseline?>(null)
    private val mutableSaveObservation = MutableStateFlow<Stage157D0SaveObservation?>(null)
    private val mutableAction = MutableStateFlow("等待设备连接")
    private val mutableRuntimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime

        probeScope.launch {
            while (isActive) {
                mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage157D0Screen(
                    repository = deviceRepository,
                    runtimeSnapshot = mutableRuntimeSnapshot,
                    baseline = mutableBaseline,
                    saveObservation = mutableSaveObservation,
                    action = mutableAction,
                    onStartRecording = ::startDeviceRecording,
                    onSaveRecording = ::saveDeviceRecording,
                    onSyncRecording = ::syncRecording,
                    onRefreshFiles = ::refreshFiles,
                    onReset = ::resetObservation,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        deviceRepository.setForeground(true)
    }

    override fun onStop() {
        deviceRepository.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        probeScope.cancel()
        super.onDestroy()
    }

    private fun startDeviceRecording() {
        if (deviceRepository.connectionState.value !is DeviceConnectionState.Ready) {
            mutableAction.value = "请先连接录音卡并等待 Ready"
            return
        }
        probeScope.launch {
            val runtime = realtimeRuntime.snapshot()
            mutableBaseline.value = Stage157D0Baseline(
                transportGeneration = runtime.transportGeneration,
                gatedReject = runtime.fence.gatedRejectCount,
                startSuccess = runtime.command.startSuccess,
                pauseSuccess = runtime.command.pauseSuccess,
                resumeSuccess = runtime.command.resumeSuccess,
                stopSuccess = runtime.command.stopSuccess,
                sourceSessionStarted = runtime.source.sessionStartedEvents,
                sourceSessionEnded = runtime.source.sessionEndedEvents,
                fileCount = deviceRepository.deviceFileListState.value.files.size,
            )
            mutableSaveObservation.value = null
            mutableAction.value = "D0：仅发送 TYPE3 APP_RECORD_START；未调用 realtime START"
            deviceRepository.startRecording()
            delay(RECORDING_SETTLE_MS)
            deviceRepository.syncRecordingState()
            mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
            mutableAction.value = "D0：TYPE3 录音已发起；继续录音 8–10 秒后保存"
        }
    }

    private fun saveDeviceRecording() {
        if (mutableBaseline.value == null) {
            mutableAction.value = "请先用 D0 TYPE3 开始设备录音"
            return
        }
        probeScope.launch {
            val before = realtimeRuntime.snapshot()
            mutableAction.value = "D0：仅发送 TYPE3 APP_RECORD_SAVE；未调用 realtime STOP"
            deviceRepository.saveRecording()
            delay(SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)
            val after = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = after
            mutableSaveObservation.value = Stage157D0SaveObservation(
                gatedRejectAtSaveRequest = before.fence.gatedRejectCount,
                gatedRejectAfterSaveSettled = after.fence.gatedRejectCount,
                fileCountAfterSave = deviceRepository.deviceFileListState.value.files.size,
            )
            mutableAction.value = "D0：TYPE3 SAVE 已完成；可复制 Gate"
        }
    }

    private fun syncRecording() {
        probeScope.launch {
            deviceRepository.syncRecordingState()
            mutableAction.value = "已同步设备录音状态"
        }
    }

    private fun refreshFiles() {
        probeScope.launch {
            deviceRepository.refreshDeviceFiles()
            mutableAction.value = "已刷新设备文件列表"
        }
    }

    private fun resetObservation() {
        mutableBaseline.value = null
        mutableSaveObservation.value = null
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableAction.value = "已重置 D0 观察；runtime 进程级累计计数不会清零"
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val RECORDING_SETTLE_MS = 500L
        const val SAVE_SETTLE_MS = 1_000L
        const val FILE_LIST_SETTLE_MS = 500L
    }
}

private data class Stage157D0Baseline(
    val transportGeneration: Long?,
    val gatedReject: Long,
    val startSuccess: Long,
    val pauseSuccess: Long,
    val resumeSuccess: Long,
    val stopSuccess: Long,
    val sourceSessionStarted: Long,
    val sourceSessionEnded: Long,
    val fileCount: Int,
)

private data class Stage157D0SaveObservation(
    val gatedRejectAtSaveRequest: Long,
    val gatedRejectAfterSaveSettled: Long,
    val fileCountAfterSave: Int,
)

@Composable
private fun Stage157D0Screen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    baseline: MutableStateFlow<Stage157D0Baseline?>,
    saveObservation: MutableStateFlow<Stage157D0SaveObservation?>,
    action: MutableStateFlow<String>,
    onStartRecording: () -> Unit,
    onSaveRecording: () -> Unit,
    onSyncRecording: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReset: () -> Unit,
) {
    val connection by repository.connectionState.collectAsState()
    val recording by repository.recordingState.collectAsState()
    val files by repository.deviceFileListState.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val baselineValue by baseline.collectAsState()
    val saveValue by saveObservation.collectAsState()
    val actionText by action.collectAsState()
    val clipboard = LocalClipboardManager.current

    val gate = renderStage157D0Gate(
        connection = connection,
        recordingStatus = recording.status?.name,
        recordingDurationSeconds = recording.durationSeconds,
        recordingSizeBytes = recording.currentSizeBytes,
        recordingFilename = recording.filename,
        recordingFreshness = recording.freshness.name,
        recordingCommandState = recording.commandState.name,
        recordingError = recording.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
        fileCount = files.files.size,
        runtime = runtime,
        baseline = baselineValue,
        saveObservation = saveValue,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stage 15.7-D0 Passive TYPE1 Fact Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接：${connection.javaClass.simpleName}")
                Text("操作：$actionText")
                Text("规则：本页面不会调用 realtime START/STOP/PAUSE/RESUME")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartRecording) { Text("TYPE3开始设备录音") }
                Button(onClick = onSaveRecording) { Text("TYPE3保存设备录音") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSyncRecording) { Text("同步录音状态") }
                OutlinedButton(onClick = onRefreshFiles) { Text("刷新设备文件") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("重置D0观察") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) {
                    Text("复制D0 Gate")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = gate,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun renderStage157D0Gate(
    connection: DeviceConnectionState,
    recordingStatus: String?,
    recordingDurationSeconds: Int?,
    recordingSizeBytes: Long?,
    recordingFilename: String?,
    recordingFreshness: String,
    recordingCommandState: String,
    recordingError: String?,
    fileCount: Int,
    runtime: RealtimeAudioRuntimeSnapshot,
    baseline: Stage157D0Baseline?,
    saveObservation: Stage157D0SaveObservation?,
): String = buildString {
    val gatedRejectDelta = baseline?.let { runtime.fence.gatedRejectCount - it.gatedReject }
    val startDelta = baseline?.let { runtime.command.startSuccess - it.startSuccess }
    val pauseDelta = baseline?.let { runtime.command.pauseSuccess - it.pauseSuccess }
    val resumeDelta = baseline?.let { runtime.command.resumeSuccess - it.resumeSuccess }
    val stopDelta = baseline?.let { runtime.command.stopSuccess - it.stopSuccess }
    val sourceStartedDelta = baseline?.let { runtime.source.sessionStartedEvents - it.sourceSessionStarted }
    val sourceEndedDelta = baseline?.let { runtime.source.sessionEndedEvents - it.sourceSessionEnded }
    val fileCountDelta = baseline?.let { fileCount - it.fileCount }

    appendLine("Stage 15.7-D0 Passive TYPE1 Fact Gate")
    appendLine("connection=${connection.javaClass.simpleName}")
    appendLine(
        "recording status=${recordingStatus ?: "--"} durationSec=${recordingDurationSeconds ?: "--"} " +
            "sizeBytes=${recordingSizeBytes ?: "--"} filename=${recordingFilename ?: "--"}",
    )
    appendLine(
        "recording freshness=$recordingFreshness commandState=$recordingCommandState error=${recordingError ?: "--"}",
    )
    appendLine(
        "runtime phase=${runtime.phase} generation=${runtime.transportGeneration ?: "--"} " +
            "gateOpen=${runtime.fence.audioGateOpen}",
    )
    appendLine(
        "runtime realtimeCommands start=${runtime.command.startSuccess}/${runtime.command.startFailure} " +
            "pause=${runtime.command.pauseSuccess}/${runtime.command.pauseFailure} " +
            "resume=${runtime.command.resumeSuccess}/${runtime.command.resumeFailure} " +
            "stop=${runtime.command.stopSuccess}/${runtime.command.stopFailure}",
    )
    appendLine(
        "runtime fence gatedReject=${runtime.fence.gatedRejectCount} staleOffer=${runtime.fence.staleOfferCount} " +
            "quarantineReject=${runtime.fence.quarantinedRejectCount} overflow=${runtime.fence.overflowCount}",
    )
    appendLine(
        "runtime source started=${runtime.source.sessionStartedEvents} ended=${runtime.source.sessionEndedEvents} " +
            "offeredFrames=${runtime.source.offeredFrames} offeredSamples=${runtime.source.offeredSamples}",
    )
    appendLine(
        "D0 baseline generation=${baseline?.transportGeneration ?: "--"} gatedReject=${baseline?.gatedReject ?: "--"} " +
            "fileCount=${baseline?.fileCount ?: "--"}",
    )
    appendLine(
        "D0 delta gatedReject=${gatedRejectDelta ?: "--"} realtimeStart=${startDelta ?: "--"} " +
            "realtimePause=${pauseDelta ?: "--"} realtimeResume=${resumeDelta ?: "--"} realtimeStop=${stopDelta ?: "--"}",
    )
    appendLine(
        "D0 sourceDelta started=${sourceStartedDelta ?: "--"} ended=${sourceEndedDelta ?: "--"} " +
            "fileCount=$fileCount fileCountDelta=${fileCountDelta ?: "--"}",
    )
    appendLine(
        "D0 save gatedRejectAtRequest=${saveObservation?.gatedRejectAtSaveRequest ?: "--"} " +
            "gatedRejectAfterSettled=${saveObservation?.gatedRejectAfterSaveSettled ?: "--"} " +
            "fileCountAfterSave=${saveObservation?.fileCountAfterSave ?: "--"}",
    )
    appendLine(
        "D0 invariant noRealtimeControl=${startDelta == 0L && pauseDelta == 0L && resumeDelta == 0L && stopDelta == 0L} " +
            "passiveType1Observed=${gatedRejectDelta?.let { it > 0L } ?: false}",
    )
}
