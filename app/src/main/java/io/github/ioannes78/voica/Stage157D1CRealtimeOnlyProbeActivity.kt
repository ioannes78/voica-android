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
 * Stage 15.7-D1C QA-only TYPE1 realtime file-side-effect probe.
 *
 * Fixed sequence:
 * refresh file baseline -> realtime START -> speak 5-8 s -> realtime STOP -> sync recording ->
 * refresh files.
 *
 * This activity never calls TYPE3 recording START / SAVE / PAUSE / RESUME. It uses only the
 * production RealtimeAudioRuntime and DeviceRepository read/sync/file-list paths so the test can
 * isolate whether TYPE1 realtime itself changes the device recording state or creates a device
 * recording file.
 */
class Stage157D1CRealtimeOnlyProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime

    private val mutableBaseline = MutableStateFlow<Stage157D1CBaseline?>(null)
    private val mutableObservation = MutableStateFlow(Stage157D1CObservation())
    private val mutableRuntimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())
    private val mutableAction = MutableStateFlow("等待设备连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime

        // D1C is not a backpressure test. Drain canonical events so the QA surface itself cannot
        // create a slow-consumer discontinuity.
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
                Stage157D1CScreen(
                    repository = deviceRepository,
                    runtimeSnapshot = mutableRuntimeSnapshot,
                    baseline = mutableBaseline,
                    observation = mutableObservation,
                    action = mutableAction,
                    onStartRealtime = ::startRealtimeOnly,
                    onStopRealtime = ::stopRealtimeOnlyAndRefreshFiles,
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

    private fun startRealtimeOnly() {
        if (deviceRepository.connectionState.value !is DeviceConnectionState.Ready) {
            mutableAction.value = "请先连接录音卡并等待 Ready"
            return
        }
        probeScope.launch {
            mutableAction.value = "D1C-1：同步录音状态并刷新文件基线"
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val beforeRuntime = realtimeRuntime.snapshot()
            val beforeRecording = deviceRepository.recordingState.value
            mutableBaseline.value = Stage157D1CBaseline(
                transportGeneration = beforeRuntime.transportGeneration,
                files = currentFileSnapshot(),
                recordingStatus = beforeRecording.status?.name,
                recordingFilename = beforeRecording.filename,
                recordingSizeBytes = beforeRecording.currentSizeBytes,
                realtimeStartSuccess = beforeRuntime.command.startSuccess,
                realtimeStopSuccess = beforeRuntime.command.stopSuccess,
            )
            mutableObservation.value = Stage157D1CObservation()

            mutableAction.value = "D1C-1：仅发送 realtime START（不发送任何 TYPE3 控制）"
            val outcome = realtimeRuntime.start()
            delay(REALTIME_START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            val afterStartRuntime = realtimeRuntime.snapshot()
            val afterStartRecording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = afterStartRuntime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStartSuccess = outcome.success,
                realtimeStartFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStart = afterStartRecording.status?.name,
                recordingFilenameAfterRealtimeStart = afterStartRecording.filename,
                recordingSizeAfterRealtimeStart = afterStartRecording.currentSizeBytes,
                cachedFileCountAfterRealtimeStart = deviceRepository.deviceFileListState.value.files.size,
                runtimeAfterRealtimeStart = afterStartRuntime.toD1CMarker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1C-1 完成：讲话 5–8 秒后点击 realtime STOP"
            } else {
                "D1C realtime START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun stopRealtimeOnlyAndRefreshFiles() {
        if (mutableObservation.value.realtimeStartSuccess != true) {
            mutableAction.value = "请先成功执行 D1C-1 realtime START"
            return
        }
        probeScope.launch {
            mutableAction.value = "D1C-2：仅发送 realtime STOP；随后同步状态并刷新文件"
            val outcome = realtimeRuntime.stop()
            delay(REALTIME_STOP_SETTLE_MS)
            deviceRepository.syncRecordingState()

            val afterStopRecording = deviceRepository.recordingState.value
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val afterStopRuntime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = afterStopRuntime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStopSuccess = outcome.success,
                realtimeStopFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStop = afterStopRecording.status?.name,
                recordingFilenameAfterRealtimeStop = afterStopRecording.filename,
                recordingSizeAfterRealtimeStop = afterStopRecording.currentSizeBytes,
                filesAfterRealtimeStop = currentFileSnapshot(),
                runtimeAfterRealtimeStop = afterStopRuntime.toD1CMarker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1C 完成：可复制 D1C Gate"
            } else {
                "D1C realtime STOP 失败：${outcome.failure ?: "unknown"}"
            }
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
            delay(FILE_LIST_SETTLE_MS)
            mutableAction.value = "已刷新设备文件列表"
        }
    }

    private fun resetObservation() {
        mutableBaseline.value = null
        mutableObservation.value = Stage157D1CObservation()
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableAction.value = "已重置 D1C；runtime 进程级累计计数不会清零"
    }

    private fun currentFileSnapshot(): Stage157D1CFileSnapshot {
        val state = deviceRepository.deviceFileListState.value
        return Stage157D1CFileSnapshot(
            count = state.files.size,
            names = state.files.map { it.displayFilename }.toSet(),
            freshness = state.freshness.name,
            error = state.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
        )
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val REALTIME_START_SETTLE_MS = 500L
        const val REALTIME_STOP_SETTLE_MS = 350L
        const val FILE_LIST_SETTLE_MS = 750L
    }
}

private data class Stage157D1CBaseline(
    val transportGeneration: Long?,
    val files: Stage157D1CFileSnapshot,
    val recordingStatus: String?,
    val recordingFilename: String?,
    val recordingSizeBytes: Long?,
    val realtimeStartSuccess: Long,
    val realtimeStopSuccess: Long,
)

private data class Stage157D1CFileSnapshot(
    val count: Int,
    val names: Set<String>,
    val freshness: String,
    val error: String?,
)

private data class Stage157D1CRuntimeMarker(
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
    val sourceOverflow: Long,
    val sourceLastEndReason: String?,
    val sourceDrainTimedOut: Boolean,
)

private data class Stage157D1CObservation(
    val realtimeStartSuccess: Boolean? = null,
    val realtimeStartFailure: String? = null,
    val recordingStatusAfterRealtimeStart: String? = null,
    val recordingFilenameAfterRealtimeStart: String? = null,
    val recordingSizeAfterRealtimeStart: Long? = null,
    val cachedFileCountAfterRealtimeStart: Int? = null,
    val runtimeAfterRealtimeStart: Stage157D1CRuntimeMarker? = null,
    val realtimeStopSuccess: Boolean? = null,
    val realtimeStopFailure: String? = null,
    val recordingStatusAfterRealtimeStop: String? = null,
    val recordingFilenameAfterRealtimeStop: String? = null,
    val recordingSizeAfterRealtimeStop: Long? = null,
    val filesAfterRealtimeStop: Stage157D1CFileSnapshot? = null,
    val runtimeAfterRealtimeStop: Stage157D1CRuntimeMarker? = null,
)

private fun RealtimeAudioRuntimeSnapshot.toD1CMarker(): Stage157D1CRuntimeMarker =
    Stage157D1CRuntimeMarker(
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
        sourceOverflow = source.overflowEvents,
        sourceLastEndReason = source.lastSessionEndReason?.name,
        sourceDrainTimedOut = source.lastSessionEndDrainTimedOut,
    )

@Composable
private fun Stage157D1CScreen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    baseline: MutableStateFlow<Stage157D1CBaseline?>,
    observation: MutableStateFlow<Stage157D1CObservation>,
    action: MutableStateFlow<String>,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    onSyncRecording: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReset: () -> Unit,
) {
    val connection by repository.connectionState.collectAsState()
    val recording by repository.recordingState.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val baselineValue by baseline.collectAsState()
    val observationValue by observation.collectAsState()
    val actionText by action.collectAsState()
    val clipboard = LocalClipboardManager.current

    val gate = renderStage157D1CGate(
        connectionReady = connection is DeviceConnectionState.Ready,
        currentRecordingStatus = recording.status?.name,
        currentRecordingFilename = recording.filename,
        currentRecordingSizeBytes = recording.currentSizeBytes,
        currentRecordingFreshness = recording.freshness.name,
        currentRecordingCommandState = recording.commandState.name,
        currentRecordingError = recording.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
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
                Text("Stage 15.7-D1C TYPE1-only Fact Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Ready：${connection is DeviceConnectionState.Ready}")
                Text("操作：$actionText")
                Text("固定顺序：文件基线 → realtime START → 讲话5–8秒 → realtime STOP → 刷新文件")
                Text("本页不会调用 TYPE3 START / SAVE / PAUSE / RESUME")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartRealtime) { Text("D1C-1 realtime START") }
                Button(onClick = onStopRealtime) { Text("D1C-2 realtime STOP") }
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
                OutlinedButton(onClick = onReset) { Text("重置D1C") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) {
                    Text("复制D1C Gate")
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

private fun renderStage157D1CGate(
    connectionReady: Boolean,
    currentRecordingStatus: String?,
    currentRecordingFilename: String?,
    currentRecordingSizeBytes: Long?,
    currentRecordingFreshness: String,
    currentRecordingCommandState: String,
    currentRecordingError: String?,
    runtime: RealtimeAudioRuntimeSnapshot,
    baseline: Stage157D1CBaseline?,
    observation: Stage157D1CObservation,
): String = buildString {
    val startMarker = observation.runtimeAfterRealtimeStart
    val stopMarker = observation.runtimeAfterRealtimeStop
    val stopFiles = observation.filesAfterRealtimeStop

    val acceptedDelta = d1cDelta(startMarker?.acceptedBlocks, stopMarker?.acceptedBlocks)
    val packetsDelta = d1cDelta(startMarker?.packetizedPackets, stopMarker?.packetizedPackets)
    val framesDelta = d1cDelta(startMarker?.canonicalFrames, stopMarker?.canonicalFrames)
    val samplesDelta = d1cDelta(startMarker?.canonicalSamples, stopMarker?.canonicalSamples)
    val ingressOverflowDelta = d1cDelta(startMarker?.ingressOverflow, stopMarker?.ingressOverflow)
    val malformedDelta = d1cDelta(startMarker?.malformedBlocks, stopMarker?.malformedBlocks)
    val droppedDelta = d1cDelta(startMarker?.droppedBlocks, stopMarker?.droppedBlocks)
    val decodeFailureDelta = d1cDelta(startMarker?.decodeFailures, stopMarker?.decodeFailures)
    val sourceOverflowDelta = d1cDelta(startMarker?.sourceOverflow, stopMarker?.sourceOverflow)

    val commandStartDelta = baseline?.let { runtime.command.startSuccess - it.realtimeStartSuccess }
    val commandStopDelta = baseline?.let { runtime.command.stopSuccess - it.realtimeStopSuccess }
    val fileCountDelta = baseline?.let { base -> stopFiles?.count?.minus(base.files.count) }
    val newFiles = if (baseline != null && stopFiles != null) {
        (stopFiles.names - baseline.files.names).sorted()
    } else {
        emptyList()
    }

    val baselineIdle = baseline?.recordingStatus.equals("Idle", ignoreCase = true)
    val type1ChangedRecordingState =
        baselineIdle && observation.recordingStatusAfterRealtimeStart != null &&
            !observation.recordingStatusAfterRealtimeStart.equals("Idle", ignoreCase = true)
    val type1StopReturnedIdle = observation.recordingStatusAfterRealtimeStop.equals("Idle", ignoreCase = true)
    val type1OnlyCreatedFiles = fileCountDelta?.let { it > 0 } == true
    val exactlyOneFileCreated = fileCountDelta == 1
    val realtimeMediaObserved =
        acceptedDelta?.let { it > 0L } == true &&
            framesDelta?.let { it > 0L } == true &&
            samplesDelta?.let { it > 0L } == true
    val cleanRealtimeWindow =
        ingressOverflowDelta == 0L && malformedDelta == 0L && droppedDelta == 0L &&
            decodeFailureDelta == 0L && sourceOverflowDelta == 0L
    val lifecycleClosedByStop =
        stopMarker?.sourceSessionActive == false &&
            stopMarker.sourceLastEndReason == "STOP" &&
            !stopMarker.sourceDrainTimedOut
    val sequenceComplete = observation.realtimeStopSuccess != null && stopFiles != null
    val executionValid =
        sequenceComplete && connectionReady && baseline != null &&
            observation.realtimeStartSuccess == true && observation.realtimeStopSuccess == true &&
            realtimeMediaObserved && cleanRealtimeWindow && lifecycleClosedByStop &&
            stopFiles?.error == null

    appendLine("Stage 15.7-D1C TYPE1-only File Side-Effect Fact Gate")
    appendLine("connectionReady=$connectionReady")
    appendLine(
        "recording currentStatus=${currentRecordingStatus ?: "--"} sizeBytes=${currentRecordingSizeBytes ?: "--"} " +
            "filename=${currentRecordingFilename ?: "--"}",
    )
    appendLine(
        "recording freshness=$currentRecordingFreshness commandState=$currentRecordingCommandState " +
            "error=${currentRecordingError ?: "--"}",
    )
    appendLine(
        "D1C baseline generation=${baseline?.transportGeneration ?: "--"} " +
            "recordingStatus=${baseline?.recordingStatus ?: "--"} sizeBytes=${baseline?.recordingSizeBytes ?: "--"} " +
            "filename=${baseline?.recordingFilename ?: "--"}",
    )
    appendLine(
        "D1C baselineFiles count=${baseline?.files?.count ?: "--"} freshness=${baseline?.files?.freshness ?: "--"} " +
            "error=${baseline?.files?.error ?: "--"}",
    )
    appendLine(
        "D1C afterRealtimeStart status=${observation.recordingStatusAfterRealtimeStart ?: "--"} " +
            "sizeBytes=${observation.recordingSizeAfterRealtimeStart ?: "--"} " +
            "filename=${observation.recordingFilenameAfterRealtimeStart ?: "--"} " +
            "cachedFileCount=${observation.cachedFileCountAfterRealtimeStart ?: "--"}",
    )
    appendLine(
        "D1C afterRealtimeStop status=${observation.recordingStatusAfterRealtimeStop ?: "--"} " +
            "sizeBytes=${observation.recordingSizeAfterRealtimeStop ?: "--"} " +
            "filename=${observation.recordingFilenameAfterRealtimeStop ?: "--"}",
    )
    appendLine(
        "D1C realtime outcomes start=${observation.realtimeStartSuccess ?: "--"}/${observation.realtimeStartFailure ?: "--"} " +
            "stop=${observation.realtimeStopSuccess ?: "--"}/${observation.realtimeStopFailure ?: "--"}",
    )
    appendLine(
        "D1C commandDelta start=${commandStartDelta ?: "--"} stop=${commandStopDelta ?: "--"} " +
            "runtimePhase=${runtime.phase} gateOpen=${runtime.fence.audioGateOpen}",
    )
    appendLine(
        "D1C mediaDelta acceptedBlocks=${acceptedDelta ?: "--"} packets=${packetsDelta ?: "--"} " +
            "canonicalFrames=${framesDelta ?: "--"} canonicalSamples=${samplesDelta ?: "--"}",
    )
    appendLine(
        "D1C anomalyDelta ingressOverflow=${ingressOverflowDelta ?: "--"} malformed=${malformedDelta ?: "--"} " +
            "dropped=${droppedDelta ?: "--"} decodeFailures=${decodeFailureDelta ?: "--"} " +
            "sourceOverflow=${sourceOverflowDelta ?: "--"}",
    )
    appendLine(
        "D1C source afterStop active=${stopMarker?.sourceSessionActive ?: "--"} " +
            "lastEnd=${stopMarker?.sourceLastEndReason ?: "--"} drainTimedOut=${stopMarker?.sourceDrainTimedOut ?: "--"}",
    )
    appendLine(
        "D1C stopFiles count=${stopFiles?.count ?: "--"} deltaFromBaseline=${fileCountDelta ?: "--"} " +
            "freshness=${stopFiles?.freshness ?: "--"} error=${stopFiles?.error ?: "--"}",
    )
    appendLine("D1C newFilesAfterRealtimeStop=${if (newFiles.isEmpty()) "[]" else newFiles.joinToString(prefix = "[", postfix = "]")}")
    appendLine(
        "D1C facts baselineIdle=$baselineIdle type1ChangedRecordingState=$type1ChangedRecordingState " +
            "type1StopReturnedIdle=$type1StopReturnedIdle type1OnlyCreatedFiles=$type1OnlyCreatedFiles " +
            "exactlyOneFileCreated=$exactlyOneFileCreated realtimeMediaObserved=$realtimeMediaObserved " +
            "cleanRealtimeWindow=$cleanRealtimeWindow lifecycleClosedByStop=$lifecycleClosedByStop",
    )
    appendLine("D1C executionValid=$executionValid")
    appendLine("D1C factStatus=${if (!sequenceComplete) "PENDING" else if (executionValid) "RESOLVED" else "INVALID"}")
}

private fun d1cDelta(start: Long?, end: Long?): Long? =
    if (start != null && end != null) end - start else null
