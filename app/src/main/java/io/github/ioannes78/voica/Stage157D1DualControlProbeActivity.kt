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
 * Stage 15.7-D1 QA-only dual-control fact probe.
 *
 * D0 proved that TYPE=3 ordinary recording does not passively produce TYPE=1 realtime audio.
 * D1 therefore verifies the other half of the independence contract with the production paths:
 * TYPE3 START -> realtime START -> realtime STOP -> TYPE3 SAVE.
 *
 * The probe does not bypass or replace DeviceRepository / RealtimeAudioRuntime behavior. It only
 * sequences the existing production entry points and records low-frequency QA evidence.
 */
class Stage157D1DualControlProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime

    private val mutableBaseline = MutableStateFlow<Stage157D1Baseline?>(null)
    private val mutableObservation = MutableStateFlow(Stage157D1Observation())
    private val mutableRuntimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())
    private val mutableAction = MutableStateFlow("等待设备连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime

        // D1 is not a downstream backpressure test. Drain canonical events so this probe does not
        // intentionally create a slow-consumer discontinuity while it validates TYPE3/TYPE1 coexistence.
        probeScope.launch(Dispatchers.Default) {
            while (isActive) {
                realtimeRuntime.canonicalSource.receive() ?: break
            }
        }

        probeScope.launch {
            while (isActive) {
                mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage157D1Screen(
                    repository = deviceRepository,
                    runtimeSnapshot = mutableRuntimeSnapshot,
                    baseline = mutableBaseline,
                    observation = mutableObservation,
                    action = mutableAction,
                    onStartType3 = ::startType3Recording,
                    onStartRealtime = ::startRealtime,
                    onStopRealtime = ::stopRealtime,
                    onSaveType3 = ::saveType3Recording,
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

    private fun startType3Recording() {
        if (deviceRepository.connectionState.value !is DeviceConnectionState.Ready) {
            mutableAction.value = "请先连接录音卡并等待 Ready"
            return
        }
        probeScope.launch {
            val runtime = realtimeRuntime.snapshot()
            mutableBaseline.value = Stage157D1Baseline(
                transportGeneration = runtime.transportGeneration,
                fileCount = deviceRepository.deviceFileListState.value.files.size,
                realtimeStartSuccess = runtime.command.startSuccess,
                realtimeStopSuccess = runtime.command.stopSuccess,
            )
            mutableObservation.value = Stage157D1Observation()
            mutableAction.value = "D1-1：发送 TYPE3 APP_RECORD_START"
            deviceRepository.startRecording()
            delay(RECORDING_SETTLE_MS)
            deviceRepository.syncRecordingState()
            mutableObservation.value = mutableObservation.value.copy(
                recordingStatusAfterType3Start = currentRecordingStatus(),
                recordingSizeAfterType3Start = deviceRepository.recordingState.value.currentSizeBytes,
            )
            mutableAction.value = "D1-1 完成：下一步点击 realtime START"
        }
    }

    private fun startRealtime() {
        if (mutableBaseline.value == null) {
            mutableAction.value = "请先执行 D1-1 TYPE3 开始设备录音"
            return
        }
        probeScope.launch {
            deviceRepository.syncRecordingState()
            mutableAction.value = "D1-2：显式发送 realtime START"
            val outcome = realtimeRuntime.start()
            delay(REALTIME_START_SETTLE_MS)
            val runtime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStartSuccess = outcome.success,
                realtimeStartFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStart = currentRecordingStatus(),
                recordingSizeAfterRealtimeStart = deviceRepository.recordingState.value.currentSizeBytes,
                runtimeAfterRealtimeStart = runtime.toD1Marker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1-2 完成：保持 TYPE3 录音并讲话 8–10 秒，再点击 realtime STOP"
            } else {
                "D1-2 realtime START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun stopRealtime() {
        if (mutableObservation.value.realtimeStartSuccess != true) {
            mutableAction.value = "请先成功执行 D1-2 realtime START"
            return
        }
        probeScope.launch {
            mutableAction.value = "D1-3：发送 realtime STOP；等待 canonical drain"
            val outcome = realtimeRuntime.stop()
            delay(REALTIME_STOP_SETTLE_MS)
            deviceRepository.syncRecordingState()
            val runtime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStopSuccess = outcome.success,
                realtimeStopFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStop = currentRecordingStatus(),
                recordingSizeAfterRealtimeStop = deviceRepository.recordingState.value.currentSizeBytes,
                runtimeAfterRealtimeStop = runtime.toD1Marker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1-3 完成：确认 TYPE3 仍在录音；下一步点击 TYPE3保存"
            } else {
                "D1-3 realtime STOP 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun saveType3Recording() {
        if (mutableObservation.value.realtimeStopSuccess != true) {
            mutableAction.value = "请先成功执行 D1-3 realtime STOP"
            return
        }
        probeScope.launch {
            mutableAction.value = "D1-4：发送 TYPE3 APP_RECORD_SAVE"
            deviceRepository.saveRecording()
            delay(SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)
            val runtime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                recordingStatusAfterType3Save = currentRecordingStatus(),
                fileCountAfterSave = deviceRepository.deviceFileListState.value.files.size,
                runtimeAfterType3Save = runtime.toD1Marker(),
            )
            mutableAction.value = "D1-4 完成：可复制 D1 Gate"
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
        mutableObservation.value = Stage157D1Observation()
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableAction.value = "已重置 D1 观察；runtime 进程级累计计数不会清零"
    }

    private fun currentRecordingStatus(): String? =
        deviceRepository.recordingState.value.status?.name

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val RECORDING_SETTLE_MS = 500L
        const val REALTIME_START_SETTLE_MS = 500L
        const val REALTIME_STOP_SETTLE_MS = 350L
        const val SAVE_SETTLE_MS = 1_000L
        const val FILE_LIST_SETTLE_MS = 500L
    }
}

private data class Stage157D1Baseline(
    val transportGeneration: Long?,
    val fileCount: Int,
    val realtimeStartSuccess: Long,
    val realtimeStopSuccess: Long,
)

private data class Stage157D1RuntimeMarker(
    val generation: Long?,
    val phase: String,
    val gateOpen: Boolean,
    val acceptedBlocks: Long,
    val ingressOverflow: Long,
    val malformedBlocks: Long,
    val droppedBlocks: Long,
    val packetizedPackets: Long,
    val decodeFailures: Long,
    val canonicalFrames: Long,
    val canonicalSamples: Long,
    val sourceSessionActive: Boolean,
    val sourceStarted: Long,
    val sourceEnded: Long,
    val sourceOfferedFrames: Long,
    val sourceOfferedSamples: Long,
    val sourceOverflow: Long,
    val sourceLastEndReason: String?,
    val sourceDrainTimedOut: Boolean,
)

private data class Stage157D1Observation(
    val recordingStatusAfterType3Start: String? = null,
    val recordingSizeAfterType3Start: Long? = null,
    val realtimeStartSuccess: Boolean? = null,
    val realtimeStartFailure: String? = null,
    val recordingStatusAfterRealtimeStart: String? = null,
    val recordingSizeAfterRealtimeStart: Long? = null,
    val runtimeAfterRealtimeStart: Stage157D1RuntimeMarker? = null,
    val realtimeStopSuccess: Boolean? = null,
    val realtimeStopFailure: String? = null,
    val recordingStatusAfterRealtimeStop: String? = null,
    val recordingSizeAfterRealtimeStop: Long? = null,
    val runtimeAfterRealtimeStop: Stage157D1RuntimeMarker? = null,
    val recordingStatusAfterType3Save: String? = null,
    val fileCountAfterSave: Int? = null,
    val runtimeAfterType3Save: Stage157D1RuntimeMarker? = null,
)

private fun RealtimeAudioRuntimeSnapshot.toD1Marker(): Stage157D1RuntimeMarker =
    Stage157D1RuntimeMarker(
        generation = transportGeneration,
        phase = phase.name,
        gateOpen = fence.audioGateOpen,
        acceptedBlocks = ingress.acceptedBlocks,
        ingressOverflow = ingress.overflowEvents,
        malformedBlocks = ingress.malformedBlocks,
        droppedBlocks = ingress.droppedBlocks,
        packetizedPackets = pipeline.packetizedPackets,
        decodeFailures = pipeline.decodeFailures,
        canonicalFrames = pipeline.canonicalFrames,
        canonicalSamples = pipeline.canonicalSamples,
        sourceSessionActive = source.sessionActive,
        sourceStarted = source.sessionStartedEvents,
        sourceEnded = source.sessionEndedEvents,
        sourceOfferedFrames = source.offeredFrames,
        sourceOfferedSamples = source.offeredSamples,
        sourceOverflow = source.overflowEvents,
        sourceLastEndReason = source.lastSessionEndReason?.name,
        sourceDrainTimedOut = source.lastSessionEndDrainTimedOut,
    )

@Composable
private fun Stage157D1Screen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    baseline: MutableStateFlow<Stage157D1Baseline?>,
    observation: MutableStateFlow<Stage157D1Observation>,
    action: MutableStateFlow<String>,
    onStartType3: () -> Unit,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    onSaveType3: () -> Unit,
    onSyncRecording: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReset: () -> Unit,
) {
    val connection by repository.connectionState.collectAsState()
    val recording by repository.recordingState.collectAsState()
    val files by repository.deviceFileListState.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val baselineValue by baseline.collectAsState()
    val observationValue by observation.collectAsState()
    val actionText by action.collectAsState()
    val clipboard = LocalClipboardManager.current

    val gate = renderStage157D1Gate(
        connectionReady = connection is DeviceConnectionState.Ready,
        recordingStatus = recording.status?.name,
        recordingDurationSeconds = recording.durationSeconds,
        recordingSizeBytes = recording.currentSizeBytes,
        recordingFilename = recording.filename,
        recordingFreshness = recording.freshness.name,
        recordingCommandState = recording.commandState.name,
        recordingError = recording.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
        currentFileCount = files.files.size,
        runtime = runtime,
        baseline = baselineValue,
        observation = observationValue,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stage 15.7-D1 TYPE3 + TYPE1 Dual-Control Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Ready：${connection is DeviceConnectionState.Ready}")
                Text("操作：$actionText")
                Text("固定顺序：TYPE3 START → realtime START → realtime STOP → TYPE3 SAVE")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartType3) { Text("D1-1 TYPE3开始") }
                Button(onClick = onStartRealtime) { Text("D1-2 realtime START") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStopRealtime) { Text("D1-3 realtime STOP") }
                Button(onClick = onSaveType3) { Text("D1-4 TYPE3保存") }
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
                OutlinedButton(onClick = onReset) { Text("重置D1观察") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) {
                    Text("复制D1 Gate")
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

private fun renderStage157D1Gate(
    connectionReady: Boolean,
    recordingStatus: String?,
    recordingDurationSeconds: Int?,
    recordingSizeBytes: Long?,
    recordingFilename: String?,
    recordingFreshness: String,
    recordingCommandState: String,
    recordingError: String?,
    currentFileCount: Int,
    runtime: RealtimeAudioRuntimeSnapshot,
    baseline: Stage157D1Baseline?,
    observation: Stage157D1Observation,
): String = buildString {
    val startMarker = observation.runtimeAfterRealtimeStart
    val stopMarker = observation.runtimeAfterRealtimeStop
    val acceptedDuringRealtime = markerDelta(startMarker?.acceptedBlocks, stopMarker?.acceptedBlocks)
    val packetsDuringRealtime = markerDelta(startMarker?.packetizedPackets, stopMarker?.packetizedPackets)
    val framesDuringRealtime = markerDelta(startMarker?.canonicalFrames, stopMarker?.canonicalFrames)
    val samplesDuringRealtime = markerDelta(startMarker?.canonicalSamples, stopMarker?.canonicalSamples)
    val ingressOverflowDelta = markerDelta(startMarker?.ingressOverflow, stopMarker?.ingressOverflow)
    val malformedDelta = markerDelta(startMarker?.malformedBlocks, stopMarker?.malformedBlocks)
    val droppedDelta = markerDelta(startMarker?.droppedBlocks, stopMarker?.droppedBlocks)
    val decodeFailureDelta = markerDelta(startMarker?.decodeFailures, stopMarker?.decodeFailures)
    val sourceOverflowDelta = markerDelta(startMarker?.sourceOverflow, stopMarker?.sourceOverflow)
    val fileCountDelta = baseline?.let { observation.fileCountAfterSave?.minus(it.fileCount) }
    val commandStartDelta = baseline?.let { runtime.command.startSuccess - it.realtimeStartSuccess }
    val commandStopDelta = baseline?.let { runtime.command.stopSuccess - it.realtimeStopSuccess }

    val type3StatusStableAcrossRealtime =
        observation.recordingStatusAfterType3Start != null &&
            observation.recordingStatusAfterType3Start == observation.recordingStatusAfterRealtimeStart &&
            observation.recordingStatusAfterType3Start == observation.recordingStatusAfterRealtimeStop &&
            !observation.recordingStatusAfterRealtimeStop.equals("Idle", ignoreCase = true)
    val realtimeMediaObserved =
        acceptedDuringRealtime?.let { it > 0L } == true &&
            framesDuringRealtime?.let { it > 0L } == true &&
            samplesDuringRealtime?.let { it > 0L } == true
    val cleanRealtimeWindow =
        ingressOverflowDelta == 0L && malformedDelta == 0L && droppedDelta == 0L &&
            decodeFailureDelta == 0L && sourceOverflowDelta == 0L
    val lifecycleClosedByStop =
        stopMarker?.sourceSessionActive == false &&
            stopMarker.sourceLastEndReason == "STOP" &&
            !stopMarker.sourceDrainTimedOut
    val sequenceComplete = observation.fileCountAfterSave != null
    val factPass =
        sequenceComplete &&
            connectionReady &&
            observation.realtimeStartSuccess == true &&
            observation.realtimeStopSuccess == true &&
            type3StatusStableAcrossRealtime &&
            realtimeMediaObserved &&
            cleanRealtimeWindow &&
            lifecycleClosedByStop &&
            fileCountDelta == 1

    appendLine("Stage 15.7-D1 TYPE3 + TYPE1 Dual-Control Fact Gate")
    appendLine("connectionReady=$connectionReady")
    appendLine(
        "recording currentStatus=${recordingStatus ?: "--"} durationSec=${recordingDurationSeconds ?: "--"} " +
            "sizeBytes=${recordingSizeBytes ?: "--"} filename=${recordingFilename ?: "--"}",
    )
    appendLine(
        "recording freshness=$recordingFreshness commandState=$recordingCommandState error=${recordingError ?: "--"}",
    )
    appendLine(
        "D1 TYPE3 status afterStart=${observation.recordingStatusAfterType3Start ?: "--"} " +
            "afterRealtimeStart=${observation.recordingStatusAfterRealtimeStart ?: "--"} " +
            "afterRealtimeStop=${observation.recordingStatusAfterRealtimeStop ?: "--"} " +
            "afterSave=${observation.recordingStatusAfterType3Save ?: "--"}",
    )
    appendLine(
        "D1 TYPE3 size afterStart=${observation.recordingSizeAfterType3Start ?: "--"} " +
            "afterRealtimeStart=${observation.recordingSizeAfterRealtimeStart ?: "--"} " +
            "afterRealtimeStop=${observation.recordingSizeAfterRealtimeStop ?: "--"}",
    )
    appendLine(
        "D1 realtime outcomes start=${observation.realtimeStartSuccess ?: "--"}/${observation.realtimeStartFailure ?: "--"} " +
            "stop=${observation.realtimeStopSuccess ?: "--"}/${observation.realtimeStopFailure ?: "--"}",
    )
    appendLine(
        "D1 baseline generation=${baseline?.transportGeneration ?: "--"} fileCount=${baseline?.fileCount ?: "--"} " +
            "startSuccess=${baseline?.realtimeStartSuccess ?: "--"} stopSuccess=${baseline?.realtimeStopSuccess ?: "--"}",
    )
    appendLine(
        "D1 commandDelta start=${commandStartDelta ?: "--"} stop=${commandStopDelta ?: "--"} " +
            "runtimePhase=${runtime.phase} gateOpen=${runtime.fence.audioGateOpen}",
    )
    appendLine(
        "D1 mediaDelta acceptedBlocks=${acceptedDuringRealtime ?: "--"} packets=${packetsDuringRealtime ?: "--"} " +
            "canonicalFrames=${framesDuringRealtime ?: "--"} canonicalSamples=${samplesDuringRealtime ?: "--"}",
    )
    appendLine(
        "D1 anomalyDelta ingressOverflow=${ingressOverflowDelta ?: "--"} malformed=${malformedDelta ?: "--"} " +
            "dropped=${droppedDelta ?: "--"} decodeFailures=${decodeFailureDelta ?: "--"} " +
            "sourceOverflow=${sourceOverflowDelta ?: "--"}",
    )
    appendLine(
        "D1 source afterStart active=${startMarker?.sourceSessionActive ?: "--"} " +
            "started=${startMarker?.sourceStarted ?: "--"} ended=${startMarker?.sourceEnded ?: "--"} " +
            "offeredFrames=${startMarker?.sourceOfferedFrames ?: "--"} offeredSamples=${startMarker?.sourceOfferedSamples ?: "--"}",
    )
    appendLine(
        "D1 source afterStop active=${stopMarker?.sourceSessionActive ?: "--"} " +
            "started=${stopMarker?.sourceStarted ?: "--"} ended=${stopMarker?.sourceEnded ?: "--"} " +
            "lastEnd=${stopMarker?.sourceLastEndReason ?: "--"} drainTimedOut=${stopMarker?.sourceDrainTimedOut ?: "--"}",
    )
    appendLine(
        "D1 files current=$currentFileCount afterSave=${observation.fileCountAfterSave ?: "--"} " +
            "fileCountDelta=${fileCountDelta ?: "--"}",
    )
    appendLine(
        "D1 invariant type3StableAcrossRealtime=$type3StatusStableAcrossRealtime " +
            "realtimeMediaObserved=$realtimeMediaObserved cleanRealtimeWindow=$cleanRealtimeWindow " +
            "lifecycleClosedByStop=$lifecycleClosedByStop",
    )
    appendLine(
        "D1 factGate=${if (!sequenceComplete) "PENDING" else if (factPass) "PASS" else "FAIL"}",
    )
}

private fun markerDelta(
    start: Long?,
    end: Long?,
): Long? = if (start != null && end != null) end - start else null
