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
 * Stage 15.7-D1A/D1B QA-only fact probe.
 *
 * D0 proved that TYPE=3 recording does not passively start TYPE=1 realtime audio. The first D1
 * true-device run then proved that realtime START can coexist with TYPE3 Recording, while realtime
 * STOP transitions the device recording state to Idle. D1A now resolves whether that STOP also
 * finalizes device files. D1B confirms the behavior of the existing production TYPE3 SAVE entry
 * point after the device has already become Idle.
 *
 * The probe never bypasses DeviceRepository or RealtimeAudioRuntime and never sends raw protocol
 * commands directly.
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

        // This is not a downstream backpressure test. Drain canonical events so the QA surface
        // cannot manufacture slow-consumer discontinuities while control-plane facts are measured.
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
                    onStopRealtime = ::stopRealtimeAndRefreshFiles,
                    onPostStopSave = ::attemptPostStopType3Save,
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
            mutableAction.value = "D1A-1：先刷新文件列表并建立基线"
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val runtime = realtimeRuntime.snapshot()
            mutableBaseline.value = Stage157D1Baseline(
                transportGeneration = runtime.transportGeneration,
                files = currentFileSnapshot(),
                realtimeStartSuccess = runtime.command.startSuccess,
                realtimeStopSuccess = runtime.command.stopSuccess,
            )
            mutableObservation.value = Stage157D1Observation()

            mutableAction.value = "D1A-1：发送 TYPE3 APP_RECORD_START"
            deviceRepository.startRecording()
            delay(RECORDING_SETTLE_MS)
            deviceRepository.syncRecordingState()
            mutableObservation.value = mutableObservation.value.copy(
                recordingStatusAfterType3Start = currentRecordingStatus(),
                recordingSizeAfterType3Start = deviceRepository.recordingState.value.currentSizeBytes,
                filesAfterType3Start = currentFileSnapshot(),
            )
            mutableAction.value = "D1A-1 完成：下一步点击 realtime START"
        }
    }

    private fun startRealtime() {
        if (mutableBaseline.value == null) {
            mutableAction.value = "请先执行 D1A-1 TYPE3 开始"
            return
        }
        probeScope.launch {
            deviceRepository.syncRecordingState()
            mutableAction.value = "D1A-2：显式发送 realtime START"
            val outcome = realtimeRuntime.start()
            delay(REALTIME_START_SETTLE_MS)
            val runtime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStartSuccess = outcome.success,
                realtimeStartFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStart = currentRecordingStatus(),
                recordingSizeAfterRealtimeStart = deviceRepository.recordingState.value.currentSizeBytes,
                filesAfterRealtimeStart = currentFileSnapshot(),
                runtimeAfterRealtimeStart = runtime.toD1Marker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1A-2 完成：讲话 5–8 秒后点击 realtime STOP"
            } else {
                "D1A-2 realtime START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun stopRealtimeAndRefreshFiles() {
        if (mutableObservation.value.realtimeStartSuccess != true) {
            mutableAction.value = "请先成功执行 D1A-2 realtime START"
            return
        }
        probeScope.launch {
            mutableAction.value = "D1A-3：发送 realtime STOP；随后立即刷新设备文件"
            val outcome = realtimeRuntime.stop()
            delay(REALTIME_STOP_SETTLE_MS)
            deviceRepository.syncRecordingState()

            val statusAfterStop = currentRecordingStatus()
            val sizeAfterStop = deviceRepository.recordingState.value.currentSizeBytes

            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val runtime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStopSuccess = outcome.success,
                realtimeStopFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStop = statusAfterStop,
                recordingSizeAfterRealtimeStop = sizeAfterStop,
                filesAfterRealtimeStop = currentFileSnapshot(),
                runtimeAfterRealtimeStop = runtime.toD1Marker(),
            )
            mutableAction.value = if (outcome.success) {
                "D1A 已完成：已记录 STOP 后状态和新增文件；可继续 D1B"
            } else {
                "D1A-3 realtime STOP 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun attemptPostStopType3Save() {
        if (mutableObservation.value.realtimeStopSuccess != true) {
            mutableAction.value = "请先完成 D1A-3 realtime STOP"
            return
        }
        if (mutableObservation.value.filesAfterRealtimeStop == null) {
            mutableAction.value = "D1A STOP 后文件快照尚未完成"
            return
        }
        probeScope.launch {
            val beforeStatus = currentRecordingStatus()
            val saveEligible = beforeStatus.equals("Recording", ignoreCase = true) ||
                beforeStatus.equals("Paused", ignoreCase = true)
            val filesBeforeSave = currentFileSnapshot()

            mutableAction.value = if (saveEligible) {
                "D1B：通过生产 DeviceRepository 调用 TYPE3 SAVE"
            } else {
                "D1B：当前为 $beforeStatus；生产 DeviceRepository SAVE 应为 NO-OP"
            }

            deviceRepository.saveRecording()
            delay(SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            mutableObservation.value = mutableObservation.value.copy(
                postStopSaveAttempted = true,
                postStopSaveEligibleAtRequest = saveEligible,
                recordingStatusBeforePostStopSave = beforeStatus,
                recordingStatusAfterPostStopSave = currentRecordingStatus(),
                filesBeforePostStopSave = filesBeforeSave,
                filesAfterPostStopSave = currentFileSnapshot(),
            )
            mutableAction.value = "D1B 完成：可复制 D1A/D1B Gate"
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
        mutableObservation.value = Stage157D1Observation()
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableAction.value = "已重置 D1A/D1B；runtime 进程级累计计数不会清零"
    }

    private fun currentRecordingStatus(): String? =
        deviceRepository.recordingState.value.status?.name

    private fun currentFileSnapshot(): Stage157D1FileSnapshot {
        val state = deviceRepository.deviceFileListState.value
        return Stage157D1FileSnapshot(
            count = state.files.size,
            names = state.files.map { it.displayFilename }.toSet(),
            freshness = state.freshness.name,
            error = state.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
        )
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val RECORDING_SETTLE_MS = 500L
        const val REALTIME_START_SETTLE_MS = 500L
        const val REALTIME_STOP_SETTLE_MS = 350L
        const val SAVE_SETTLE_MS = 500L
        const val FILE_LIST_SETTLE_MS = 750L
    }
}

private data class Stage157D1Baseline(
    val transportGeneration: Long?,
    val files: Stage157D1FileSnapshot,
    val realtimeStartSuccess: Long,
    val realtimeStopSuccess: Long,
)

private data class Stage157D1FileSnapshot(
    val count: Int,
    val names: Set<String>,
    val freshness: String,
    val error: String?,
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
    val filesAfterType3Start: Stage157D1FileSnapshot? = null,
    val realtimeStartSuccess: Boolean? = null,
    val realtimeStartFailure: String? = null,
    val recordingStatusAfterRealtimeStart: String? = null,
    val recordingSizeAfterRealtimeStart: Long? = null,
    val filesAfterRealtimeStart: Stage157D1FileSnapshot? = null,
    val runtimeAfterRealtimeStart: Stage157D1RuntimeMarker? = null,
    val realtimeStopSuccess: Boolean? = null,
    val realtimeStopFailure: String? = null,
    val recordingStatusAfterRealtimeStop: String? = null,
    val recordingSizeAfterRealtimeStop: Long? = null,
    val filesAfterRealtimeStop: Stage157D1FileSnapshot? = null,
    val runtimeAfterRealtimeStop: Stage157D1RuntimeMarker? = null,
    val postStopSaveAttempted: Boolean = false,
    val postStopSaveEligibleAtRequest: Boolean? = null,
    val recordingStatusBeforePostStopSave: String? = null,
    val recordingStatusAfterPostStopSave: String? = null,
    val filesBeforePostStopSave: Stage157D1FileSnapshot? = null,
    val filesAfterPostStopSave: Stage157D1FileSnapshot? = null,
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
    onPostStopSave: () -> Unit,
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

    val gate = renderStage157D1Gate(
        connectionReady = connection is DeviceConnectionState.Ready,
        recordingStatus = recording.status?.name,
        recordingDurationSeconds = recording.durationSeconds,
        recordingSizeBytes = recording.currentSizeBytes,
        recordingFilename = recording.filename,
        recordingFreshness = recording.freshness.name,
        recordingCommandState = recording.commandState.name,
        recordingError = recording.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
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
                Text("Stage 15.7-D1A / D1B Fact Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Ready：${connection is DeviceConnectionState.Ready}")
                Text("操作：$actionText")
                Text("D1A：TYPE3 START → realtime START → realtime STOP → 立即刷新文件")
                Text("D1B：STOP 后通过生产 DeviceRepository 再调用 SAVE，确认是否 NO-OP")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartType3) { Text("D1A-1 TYPE3开始") }
                Button(onClick = onStartRealtime) { Text("D1A-2 realtime START") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStopRealtime) { Text("D1A-3 realtime STOP") }
                Button(onClick = onPostStopSave) { Text("D1B STOP后SAVE") }
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
                OutlinedButton(onClick = onReset) { Text("重置D1A/D1B") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) {
                    Text("复制D1A/D1B Gate")
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
    val commandStartDelta = baseline?.let { runtime.command.startSuccess - it.realtimeStartSuccess }
    val commandStopDelta = baseline?.let { runtime.command.stopSuccess - it.realtimeStopSuccess }

    val baselineFiles = baseline?.files
    val stopFiles = observation.filesAfterRealtimeStop
    val postSaveFiles = observation.filesAfterPostStopSave
    val newFilesAfterStop = addedFileNames(baselineFiles, stopFiles)
    val newFilesAfterPostStopSave = addedFileNames(stopFiles, postSaveFiles)
    val stopFileCountDelta = fileCountDelta(baselineFiles, stopFiles)
    val postStopSaveFileCountDelta = fileCountDelta(stopFiles, postSaveFiles)

    val coexistenceStart =
        observation.realtimeStartSuccess == true &&
            isRecordingState(observation.recordingStatusAfterType3Start) &&
            isRecordingState(observation.recordingStatusAfterRealtimeStart)
    val realtimeStopPreservedType3 =
        isRecordingState(observation.recordingStatusAfterRealtimeStop)
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
    val d1aResolved = observation.realtimeStopSuccess != null && stopFiles != null
    val realtimeStopCreatedFiles = stopFileCountDelta?.let { it > 0 } == true
    val d1bResolved = observation.postStopSaveAttempted && postSaveFiles != null
    val postStopSaveCreatedFiles = postStopSaveFileCountDelta?.let { it > 0 } == true
    val postStopSaveNoOp =
        d1bResolved && observation.postStopSaveEligibleAtRequest == false && !postStopSaveCreatedFiles

    appendLine("Stage 15.7-D1A/D1B TYPE3 + TYPE1 Fact Gate")
    appendLine("connectionReady=$connectionReady")
    appendLine(
        "recording currentStatus=${recordingStatus ?: "--"} durationSec=${recordingDurationSeconds ?: "--"} " +
            "sizeBytes=${recordingSizeBytes ?: "--"} filename=${recordingFilename ?: "--"}",
    )
    appendLine(
        "recording freshness=$recordingFreshness commandState=$recordingCommandState error=${recordingError ?: "--"}",
    )
    appendLine(
        "D1A TYPE3 status afterStart=${observation.recordingStatusAfterType3Start ?: "--"} " +
            "afterRealtimeStart=${observation.recordingStatusAfterRealtimeStart ?: "--"} " +
            "afterRealtimeStop=${observation.recordingStatusAfterRealtimeStop ?: "--"}",
    )
    appendLine(
        "D1A TYPE3 size afterStart=${observation.recordingSizeAfterType3Start ?: "--"} " +
            "afterRealtimeStart=${observation.recordingSizeAfterRealtimeStart ?: "--"} " +
            "afterRealtimeStop=${observation.recordingSizeAfterRealtimeStop ?: "--"}",
    )
    appendLine(
        "D1A realtime outcomes start=${observation.realtimeStartSuccess ?: "--"}/${observation.realtimeStartFailure ?: "--"} " +
            "stop=${observation.realtimeStopSuccess ?: "--"}/${observation.realtimeStopFailure ?: "--"}",
    )
    appendLine(
        "D1A baseline generation=${baseline?.transportGeneration ?: "--"} " +
            "fileCount=${baselineFiles?.count ?: "--"} fileFreshness=${baselineFiles?.freshness ?: "--"} " +
            "fileError=${baselineFiles?.error ?: "--"}",
    )
    appendLine(
        "D1A cachedFileCount afterType3Start=${observation.filesAfterType3Start?.count ?: "--"} " +
            "afterRealtimeStart=${observation.filesAfterRealtimeStart?.count ?: "--"}",
    )
    appendLine(
        "D1A stopFiles count=${stopFiles?.count ?: "--"} deltaFromBaseline=${stopFileCountDelta ?: "--"} " +
            "freshness=${stopFiles?.freshness ?: "--"} error=${stopFiles?.error ?: "--"}",
    )
    appendLine("D1A newFilesAfterRealtimeStop=${formatFileNames(newFilesAfterStop)}")
    appendLine(
        "D1A commandDelta start=${commandStartDelta ?: "--"} stop=${commandStopDelta ?: "--"} " +
            "runtimePhase=${runtime.phase} gateOpen=${runtime.fence.audioGateOpen}",
    )
    appendLine(
        "D1A mediaDelta acceptedBlocks=${acceptedDuringRealtime ?: "--"} packets=${packetsDuringRealtime ?: "--"} " +
            "canonicalFrames=${framesDuringRealtime ?: "--"} canonicalSamples=${samplesDuringRealtime ?: "--"}",
    )
    appendLine(
        "D1A anomalyDelta ingressOverflow=${ingressOverflowDelta ?: "--"} malformed=${malformedDelta ?: "--"} " +
            "dropped=${droppedDelta ?: "--"} decodeFailures=${decodeFailureDelta ?: "--"} " +
            "sourceOverflow=${sourceOverflowDelta ?: "--"}",
    )
    appendLine(
        "D1A source afterStop active=${stopMarker?.sourceSessionActive ?: "--"} " +
            "lastEnd=${stopMarker?.sourceLastEndReason ?: "--"} drainTimedOut=${stopMarker?.sourceDrainTimedOut ?: "--"}",
    )
    appendLine(
        "D1A facts coexistenceStart=$coexistenceStart realtimeStopPreservedType3=$realtimeStopPreservedType3 " +
            "realtimeStopCreatedFiles=$realtimeStopCreatedFiles realtimeMediaObserved=$realtimeMediaObserved " +
            "cleanRealtimeWindow=$cleanRealtimeWindow lifecycleClosedByStop=$lifecycleClosedByStop",
    )
    appendLine("D1A factStatus=${if (d1aResolved) "RESOLVED" else "PENDING"}")
    appendLine(
        "D1B save attempted=${observation.postStopSaveAttempted} " +
            "eligibleAtRequest=${observation.postStopSaveEligibleAtRequest ?: "--"} " +
            "statusBefore=${observation.recordingStatusBeforePostStopSave ?: "--"} " +
            "statusAfter=${observation.recordingStatusAfterPostStopSave ?: "--"}",
    )
    appendLine(
        "D1B files before=${observation.filesBeforePostStopSave?.count ?: "--"} " +
            "after=${postSaveFiles?.count ?: "--"} delta=${postStopSaveFileCountDelta ?: "--"} " +
            "freshness=${postSaveFiles?.freshness ?: "--"} error=${postSaveFiles?.error ?: "--"}",
    )
    appendLine("D1B newFilesAfterSaveCall=${formatFileNames(newFilesAfterPostStopSave)}")
    appendLine(
        "D1B facts productionSaveExpectedNoOp=${observation.postStopSaveEligibleAtRequest == false} " +
            "postStopSaveCreatedFiles=$postStopSaveCreatedFiles postStopSaveNoOp=$postStopSaveNoOp",
    )
    appendLine("D1B factStatus=${if (d1bResolved) "RESOLVED" else "PENDING"}")
}

private fun markerDelta(
    start: Long?,
    end: Long?,
): Long? = if (start != null && end != null) end - start else null

private fun fileCountDelta(
    before: Stage157D1FileSnapshot?,
    after: Stage157D1FileSnapshot?,
): Int? = if (before != null && after != null) after.count - before.count else null

private fun addedFileNames(
    before: Stage157D1FileSnapshot?,
    after: Stage157D1FileSnapshot?,
): List<String> = if (before != null && after != null) {
    (after.names - before.names).sorted()
} else {
    emptyList()
}

private fun formatFileNames(names: List<String>): String =
    if (names.isEmpty()) "[]" else names.joinToString(prefix = "[", postfix = "]")

private fun isRecordingState(status: String?): Boolean =
    status.equals("Recording", ignoreCase = true) || status.equals("Paused", ignoreCase = true)
