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
 * Stage 15.7-D2 QA-only production reverse-control fact probe.
 *
 * D1C proved that TYPE1 realtime START itself changes the device recording state from Idle to
 * Recording. Consequently the production DeviceRepository.startRecording() guard rejects a later
 * TYPE3 START as a no-op, while DeviceRepository.saveRecording() is eligible and can send TYPE3
 * SAVE. D2 intentionally preserves those production guards and measures whether that SAVE affects
 * an already-active TYPE1 realtime stream.
 *
 * No raw TYPE3 command is sent and no production BLE/realtime behavior is bypassed.
 */
class Stage157D2ReverseControlProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime

    private val mutableBaseline = MutableStateFlow<Stage157D2Baseline?>(null)
    private val mutableObservation = MutableStateFlow(Stage157D2Observation())
    private val mutableRuntimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())
    private val mutableAction = MutableStateFlow("等待设备连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime

        // D2 is a control-plane fact gate, not a downstream backpressure test. Drain canonical
        // events so this QA surface cannot manufacture slow-consumer discontinuities.
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
                Stage157D2Screen(
                    repository = deviceRepository,
                    runtimeSnapshot = mutableRuntimeSnapshot,
                    baseline = mutableBaseline,
                    observation = mutableObservation,
                    action = mutableAction,
                    onStartRealtime = ::startRealtime,
                    onAttemptType3Start = ::attemptProductionType3Start,
                    onSaveType3 = ::saveType3WhileRealtime,
                    onCleanupStop = ::cleanupRealtimeStop,
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

    private fun startRealtime() {
        if (deviceRepository.connectionState.value !is DeviceConnectionState.Ready) {
            mutableAction.value = "请先连接录音卡并等待 Ready"
            return
        }
        probeScope.launch {
            mutableAction.value = "D2-1：同步状态并刷新文件基线"
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val beforeRuntime = realtimeRuntime.snapshot()
            val beforeRecording = deviceRepository.recordingState.value
            mutableBaseline.value = Stage157D2Baseline(
                transportGeneration = beforeRuntime.transportGeneration,
                files = currentFileSnapshot(),
                recordingStatus = beforeRecording.status?.name,
                recordingFilename = beforeRecording.filename,
                recordingSizeBytes = beforeRecording.currentSizeBytes,
                realtimeStartSuccess = beforeRuntime.command.startSuccess,
                realtimeStopSuccess = beforeRuntime.command.stopSuccess,
            )
            mutableObservation.value = Stage157D2Observation()

            mutableAction.value = "D2-1：发送 realtime START"
            val outcome = realtimeRuntime.start()
            delay(REALTIME_START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            val runtime = realtimeRuntime.snapshot()
            val recording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                realtimeStartSuccess = outcome.success,
                realtimeStartFailure = outcome.failure?.toString(),
                recordingStatusAfterRealtimeStart = recording.status?.name,
                recordingFilenameAfterRealtimeStart = recording.filename,
                recordingSizeAfterRealtimeStart = recording.currentSizeBytes,
                runtimeAfterRealtimeStart = runtime.toD2Marker(),
            )
            mutableAction.value = if (outcome.success) {
                "D2-1 完成：讲话约 3 秒，再点击 D2-2 TYPE3开始尝试"
            } else {
                "D2 realtime START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun attemptProductionType3Start() {
        if (mutableObservation.value.realtimeStartSuccess != true) {
            mutableAction.value = "请先成功执行 D2-1 realtime START"
            return
        }
        probeScope.launch {
            deviceRepository.syncRecordingState()
            val statusBefore = currentRecordingStatus()
            val eligible = statusBefore.equals("Idle", ignoreCase = true)

            mutableAction.value = if (eligible) {
                "D2-2：production TYPE3 START 当前满足 Idle guard"
            } else {
                "D2-2：当前为 $statusBefore；production TYPE3 START 应被 guard NO-OP"
            }

            deviceRepository.startRecording()
            delay(TYPE3_START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            val runtime = realtimeRuntime.snapshot()
            val recording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                type3StartAttempted = true,
                type3StartEligibleAtRequest = eligible,
                recordingStatusBeforeType3Start = statusBefore,
                recordingStatusAfterType3Start = recording.status?.name,
                recordingFilenameAfterType3Start = recording.filename,
                recordingSizeAfterType3Start = recording.currentSizeBytes,
                runtimeAfterType3StartAttempt = runtime.toD2Marker(),
            )
            mutableAction.value = "D2-2 完成：继续讲话 5–8 秒，再点击 D2-3 TYPE3保存"
        }
    }

    private fun saveType3WhileRealtime() {
        if (!mutableObservation.value.type3StartAttempted) {
            mutableAction.value = "请先执行 D2-2 production TYPE3 START 尝试"
            return
        }
        probeScope.launch {
            deviceRepository.syncRecordingState()
            val statusBefore = currentRecordingStatus()
            val saveEligible = isRecordingOrPaused(statusBefore)
            val beforeSaveRuntime = realtimeRuntime.snapshot()
            mutableRuntimeSnapshot.value = beforeSaveRuntime

            mutableObservation.value = mutableObservation.value.copy(
                type3SaveAttempted = true,
                type3SaveEligibleAtRequest = saveEligible,
                recordingStatusBeforeType3Save = statusBefore,
                runtimeBeforeType3Save = beforeSaveRuntime.toD2Marker(),
            )

            mutableAction.value = if (saveEligible) {
                "D2-3：通过 production DeviceRepository 发送 TYPE3 SAVE"
            } else {
                "D2-3：当前为 $statusBefore；production SAVE 应被 guard NO-OP"
            }

            deviceRepository.saveRecording()
            delay(TYPE3_SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()

            val immediateRuntime = realtimeRuntime.snapshot()
            val immediateRecording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = immediateRuntime
            mutableObservation.value = mutableObservation.value.copy(
                recordingStatusAfterType3Save = immediateRecording.status?.name,
                recordingFilenameAfterType3Save = immediateRecording.filename,
                recordingSizeAfterType3Save = immediateRecording.currentSizeBytes,
                runtimeImmediatelyAfterType3Save = immediateRuntime.toD2Marker(),
            )

            mutableAction.value = "D2-3 SAVE 已完成：继续讲话 3 秒，正在观察 realtime 是否继续出流"
            delay(POST_SAVE_MEDIA_WINDOW_MS)

            val postWindowRuntime = realtimeRuntime.snapshot()
            deviceRepository.syncRecordingState()
            val postWindowRecording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = postWindowRuntime
            mutableObservation.value = mutableObservation.value.copy(
                recordingStatusAfterPostSaveWindow = postWindowRecording.status?.name,
                recordingFilenameAfterPostSaveWindow = postWindowRecording.filename,
                recordingSizeAfterPostSaveWindow = postWindowRecording.currentSizeBytes,
                runtimeAfterPostSaveWindow = postWindowRuntime.toD2Marker(),
            )
            mutableAction.value = "D2-3 观察完成：点击 D2-4 realtime STOP 做清理并刷新最终文件"
        }
    }

    private fun cleanupRealtimeStop() {
        if (mutableObservation.value.runtimeAfterPostSaveWindow == null) {
            mutableAction.value = "请先完成 D2-3 SAVE 后 3 秒媒体观察"
            return
        }
        probeScope.launch {
            mutableAction.value = "D2-4：显式 realtime STOP 清理"
            val outcome = realtimeRuntime.stop()
            delay(REALTIME_STOP_SETTLE_MS)
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_LIST_SETTLE_MS)

            val runtime = realtimeRuntime.snapshot()
            val recording = deviceRepository.recordingState.value
            mutableRuntimeSnapshot.value = runtime
            mutableObservation.value = mutableObservation.value.copy(
                cleanupStopAttempted = true,
                cleanupRealtimeStopSuccess = outcome.success,
                cleanupRealtimeStopFailure = outcome.failure?.toString(),
                recordingStatusAfterCleanupStop = recording.status?.name,
                finalFiles = currentFileSnapshot(),
                runtimeAfterCleanupStop = runtime.toD2Marker(),
            )
            mutableAction.value = "D2 完成：可复制 D2 Gate"
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
        mutableObservation.value = Stage157D2Observation()
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableAction.value = "已重置 D2；runtime 进程级累计计数不会清零"
    }

    private fun currentRecordingStatus(): String? =
        deviceRepository.recordingState.value.status?.name

    private fun currentFileSnapshot(): Stage157D2FileSnapshot {
        val state = deviceRepository.deviceFileListState.value
        return Stage157D2FileSnapshot(
            count = state.files.size,
            names = state.files.map { it.displayFilename }.toSet(),
            freshness = state.freshness.name,
            error = state.lastError?.let { "${it.code}:${it.detail ?: "--"}" },
        )
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val REALTIME_START_SETTLE_MS = 500L
        const val TYPE3_START_SETTLE_MS = 350L
        const val TYPE3_SAVE_SETTLE_MS = 600L
        const val POST_SAVE_MEDIA_WINDOW_MS = 3_000L
        const val REALTIME_STOP_SETTLE_MS = 350L
        const val FILE_LIST_SETTLE_MS = 750L
    }
}

private data class Stage157D2Baseline(
    val transportGeneration: Long?,
    val files: Stage157D2FileSnapshot,
    val recordingStatus: String?,
    val recordingFilename: String?,
    val recordingSizeBytes: Long?,
    val realtimeStartSuccess: Long,
    val realtimeStopSuccess: Long,
)

private data class Stage157D2FileSnapshot(
    val count: Int,
    val names: Set<String>,
    val freshness: String,
    val error: String?,
)

private data class Stage157D2RuntimeMarker(
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
    val sourceOverflow: Long,
    val sourceLastEndReason: String?,
    val sourceDrainTimedOut: Boolean,
)

private data class Stage157D2Observation(
    val realtimeStartSuccess: Boolean? = null,
    val realtimeStartFailure: String? = null,
    val recordingStatusAfterRealtimeStart: String? = null,
    val recordingFilenameAfterRealtimeStart: String? = null,
    val recordingSizeAfterRealtimeStart: Long? = null,
    val runtimeAfterRealtimeStart: Stage157D2RuntimeMarker? = null,
    val type3StartAttempted: Boolean = false,
    val type3StartEligibleAtRequest: Boolean? = null,
    val recordingStatusBeforeType3Start: String? = null,
    val recordingStatusAfterType3Start: String? = null,
    val recordingFilenameAfterType3Start: String? = null,
    val recordingSizeAfterType3Start: Long? = null,
    val runtimeAfterType3StartAttempt: Stage157D2RuntimeMarker? = null,
    val type3SaveAttempted: Boolean = false,
    val type3SaveEligibleAtRequest: Boolean? = null,
    val recordingStatusBeforeType3Save: String? = null,
    val runtimeBeforeType3Save: Stage157D2RuntimeMarker? = null,
    val recordingStatusAfterType3Save: String? = null,
    val recordingFilenameAfterType3Save: String? = null,
    val recordingSizeAfterType3Save: Long? = null,
    val runtimeImmediatelyAfterType3Save: Stage157D2RuntimeMarker? = null,
    val recordingStatusAfterPostSaveWindow: String? = null,
    val recordingFilenameAfterPostSaveWindow: String? = null,
    val recordingSizeAfterPostSaveWindow: Long? = null,
    val runtimeAfterPostSaveWindow: Stage157D2RuntimeMarker? = null,
    val cleanupStopAttempted: Boolean = false,
    val cleanupRealtimeStopSuccess: Boolean? = null,
    val cleanupRealtimeStopFailure: String? = null,
    val recordingStatusAfterCleanupStop: String? = null,
    val finalFiles: Stage157D2FileSnapshot? = null,
    val runtimeAfterCleanupStop: Stage157D2RuntimeMarker? = null,
)

private fun RealtimeAudioRuntimeSnapshot.toD2Marker(): Stage157D2RuntimeMarker =
    Stage157D2RuntimeMarker(
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
        sourceOverflow = source.overflowEvents,
        sourceLastEndReason = source.lastSessionEndReason?.name,
        sourceDrainTimedOut = source.lastSessionEndDrainTimedOut,
    )

@Composable
private fun Stage157D2Screen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    baseline: MutableStateFlow<Stage157D2Baseline?>,
    observation: MutableStateFlow<Stage157D2Observation>,
    action: MutableStateFlow<String>,
    onStartRealtime: () -> Unit,
    onAttemptType3Start: () -> Unit,
    onSaveType3: () -> Unit,
    onCleanupStop: () -> Unit,
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

    val gate = renderStage157D2Gate(
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
                Text("Stage 15.7-D2 Reverse-Control Fact Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Ready：${connection is DeviceConnectionState.Ready}")
                Text("操作：$actionText")
                Text("生产顺序：realtime START → TYPE3 START尝试 → TYPE3 SAVE → 观察3秒 → realtime STOP")
                Text("TYPE3 START/SAVE 均保留 production DeviceRepository guard；不发送 raw 命令")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartRealtime) { Text("D2-1 realtime START") }
                Button(onClick = onAttemptType3Start) { Text("D2-2 TYPE3开始尝试") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveType3) { Text("D2-3 TYPE3保存") }
                Button(onClick = onCleanupStop) { Text("D2-4 realtime STOP") }
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
                OutlinedButton(onClick = onReset) { Text("重置D2") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) {
                    Text("复制D2 Gate")
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

private fun renderStage157D2Gate(
    connectionReady: Boolean,
    currentRecordingStatus: String?,
    currentRecordingFilename: String?,
    currentRecordingSizeBytes: Long?,
    currentRecordingFreshness: String,
    currentRecordingCommandState: String,
    currentRecordingError: String?,
    runtime: RealtimeAudioRuntimeSnapshot,
    baseline: Stage157D2Baseline?,
    observation: Stage157D2Observation,
): String = buildString {
    val startMarker = observation.runtimeAfterRealtimeStart
    val beforeSave = observation.runtimeBeforeType3Save
    val immediateAfterSave = observation.runtimeImmediatelyAfterType3Save
    val postSaveWindow = observation.runtimeAfterPostSaveWindow
    val cleanupMarker = observation.runtimeAfterCleanupStop

    val preSaveAccepted = d2Delta(startMarker?.acceptedBlocks, beforeSave?.acceptedBlocks)
    val preSaveSamples = d2Delta(startMarker?.canonicalSamples, beforeSave?.canonicalSamples)
    val postSaveAccepted = d2Delta(immediateAfterSave?.acceptedBlocks, postSaveWindow?.acceptedBlocks)
    val postSavePackets = d2Delta(immediateAfterSave?.packetizedPackets, postSaveWindow?.packetizedPackets)
    val postSaveFrames = d2Delta(immediateAfterSave?.canonicalFrames, postSaveWindow?.canonicalFrames)
    val postSaveSamples = d2Delta(immediateAfterSave?.canonicalSamples, postSaveWindow?.canonicalSamples)
    val postSaveIngressOverflow = d2Delta(immediateAfterSave?.ingressOverflow, postSaveWindow?.ingressOverflow)
    val postSaveMalformed = d2Delta(immediateAfterSave?.malformedBlocks, postSaveWindow?.malformedBlocks)
    val postSaveDropped = d2Delta(immediateAfterSave?.droppedBlocks, postSaveWindow?.droppedBlocks)
    val postSaveDecodeFailures = d2Delta(immediateAfterSave?.decodeFailures, postSaveWindow?.decodeFailures)
    val postSaveSourceOverflow = d2Delta(immediateAfterSave?.sourceOverflow, postSaveWindow?.sourceOverflow)

    val finalFiles = observation.finalFiles
    val fileCountDelta = d2FileCountDelta(baseline?.files, finalFiles)
    val newFiles = d2AddedFileNames(baseline?.files, finalFiles)
    val commandStartDelta = baseline?.let { runtime.command.startSuccess - it.realtimeStartSuccess }
    val commandStopDelta = baseline?.let { runtime.command.stopSuccess - it.realtimeStopSuccess }

    val baselineIdle = baseline?.recordingStatus.equals("Idle", ignoreCase = true)
    val realtimeStartChangedRecordingState =
        baselineIdle && isRecordingOrPaused(observation.recordingStatusAfterRealtimeStart)
    val productionType3StartNoOp =
        observation.type3StartAttempted &&
            observation.type3StartEligibleAtRequest == false &&
            observation.recordingStatusBeforeType3Start == observation.recordingStatusAfterType3Start
    val type3SaveEligible = observation.type3SaveEligibleAtRequest == true
    val type3SaveReturnedIdle = observation.recordingStatusAfterType3Save.equals("Idle", ignoreCase = true)
    val preSaveMediaObserved =
        preSaveAccepted?.let { it > 0L } == true && preSaveSamples?.let { it > 0L } == true
    val postSaveMediaContinued =
        postSaveAccepted?.let { it > 0L } == true &&
            postSaveFrames?.let { it > 0L } == true &&
            postSaveSamples?.let { it > 0L } == true
    val runtimeStayedActiveAfterSave =
        postSaveWindow?.phase == "ACTIVE" &&
            postSaveWindow.gateOpen &&
            postSaveWindow.sourceSessionActive
    val cleanPostSaveWindow =
        postSaveIngressOverflow == 0L &&
            postSaveMalformed == 0L &&
            postSaveDropped == 0L &&
            postSaveDecodeFailures == 0L &&
            postSaveSourceOverflow == 0L
    val type3SaveReverseStoppedMedia =
        observation.runtimeAfterPostSaveWindow != null && preSaveMediaObserved && !postSaveMediaContinued
    val finalLifecycleClosed =
        cleanupMarker?.sourceSessionActive == false &&
            cleanupMarker.sourceLastEndReason != null &&
            !cleanupMarker.sourceDrainTimedOut
    val executionValid =
        connectionReady &&
            baselineIdle &&
            observation.realtimeStartSuccess == true &&
            observation.type3StartAttempted &&
            observation.type3SaveAttempted &&
            type3SaveEligible &&
            preSaveMediaObserved &&
            postSaveWindow != null &&
            observation.cleanupStopAttempted &&
            finalFiles != null

    val reverseEffect = when {
        postSaveWindow == null -> "PENDING"
        postSaveMediaContinued && runtimeStayedActiveAfterSave -> "STREAM_CONTINUES"
        !postSaveMediaContinued && runtimeStayedActiveAfterSave -> "DEVICE_MEDIA_STOPS_RUNTIME_STAYS_ACTIVE"
        !runtimeStayedActiveAfterSave -> "RUNTIME_NOT_ACTIVE_AFTER_SAVE"
        else -> "MIXED"
    }

    appendLine("Stage 15.7-D2 Production Reverse-Control Fact Gate")
    appendLine("connectionReady=$connectionReady")
    appendLine(
        "recording currentStatus=${currentRecordingStatus ?: "--"} sizeBytes=${currentRecordingSizeBytes ?: "--"} " +
            "filename=${currentRecordingFilename ?: "--"}",
    )
    appendLine(
        "recording freshness=$currentRecordingFreshness commandState=$currentRecordingCommandState error=${currentRecordingError ?: "--"}",
    )
    appendLine(
        "D2 baseline generation=${baseline?.transportGeneration ?: "--"} recordingStatus=${baseline?.recordingStatus ?: "--"} " +
            "sizeBytes=${baseline?.recordingSizeBytes ?: "--"} filename=${baseline?.recordingFilename ?: "--"}",
    )
    appendLine(
        "D2 baselineFiles count=${baseline?.files?.count ?: "--"} freshness=${baseline?.files?.freshness ?: "--"} " +
            "error=${baseline?.files?.error ?: "--"}",
    )
    appendLine(
        "D2 realtime start=${observation.realtimeStartSuccess ?: "--"}/${observation.realtimeStartFailure ?: "--"} " +
            "afterStartStatus=${observation.recordingStatusAfterRealtimeStart ?: "--"} " +
            "filename=${observation.recordingFilenameAfterRealtimeStart ?: "--"} " +
            "sizeBytes=${observation.recordingSizeAfterRealtimeStart ?: "--"}",
    )
    appendLine(
        "D2 TYPE3 start attempted=${observation.type3StartAttempted} eligibleAtRequest=${observation.type3StartEligibleAtRequest ?: "--"} " +
            "statusBefore=${observation.recordingStatusBeforeType3Start ?: "--"} " +
            "statusAfter=${observation.recordingStatusAfterType3Start ?: "--"}",
    )
    appendLine(
        "D2 TYPE3 start after filename=${observation.recordingFilenameAfterType3Start ?: "--"} " +
            "sizeBytes=${observation.recordingSizeAfterType3Start ?: "--"} productionStartExpectedNoOp=${observation.type3StartEligibleAtRequest == false}",
    )
    appendLine(
        "D2 TYPE3 save attempted=${observation.type3SaveAttempted} eligibleAtRequest=${observation.type3SaveEligibleAtRequest ?: "--"} " +
            "statusBefore=${observation.recordingStatusBeforeType3Save ?: "--"} " +
            "statusAfter=${observation.recordingStatusAfterType3Save ?: "--"}",
    )
    appendLine(
        "D2 afterSave recording filename=${observation.recordingFilenameAfterType3Save ?: "--"} " +
            "sizeBytes=${observation.recordingSizeAfterType3Save ?: "--"} " +
            "postWindowStatus=${observation.recordingStatusAfterPostSaveWindow ?: "--"}",
    )
    appendLine(
        "D2 runtime immediateAfterSave phase=${immediateAfterSave?.phase ?: "--"} gateOpen=${immediateAfterSave?.gateOpen ?: "--"} " +
            "sourceActive=${immediateAfterSave?.sourceSessionActive ?: "--"}",
    )
    appendLine(
        "D2 runtime postSaveWindow phase=${postSaveWindow?.phase ?: "--"} gateOpen=${postSaveWindow?.gateOpen ?: "--"} " +
            "sourceActive=${postSaveWindow?.sourceSessionActive ?: "--"}",
    )
    appendLine(
        "D2 media preSave acceptedBlocks=${preSaveAccepted ?: "--"} canonicalSamples=${preSaveSamples ?: "--"}",
    )
    appendLine(
        "D2 media postSaveWindow acceptedBlocks=${postSaveAccepted ?: "--"} packets=${postSavePackets ?: "--"} " +
            "canonicalFrames=${postSaveFrames ?: "--"} canonicalSamples=${postSaveSamples ?: "--"}",
    )
    appendLine(
        "D2 anomaly postSaveWindow ingressOverflow=${postSaveIngressOverflow ?: "--"} malformed=${postSaveMalformed ?: "--"} " +
            "dropped=${postSaveDropped ?: "--"} decodeFailures=${postSaveDecodeFailures ?: "--"} " +
            "sourceOverflow=${postSaveSourceOverflow ?: "--"}",
    )
    appendLine(
        "D2 cleanup realtimeStop attempted=${observation.cleanupStopAttempted} " +
            "outcome=${observation.cleanupRealtimeStopSuccess ?: "--"}/${observation.cleanupRealtimeStopFailure ?: "--"} " +
            "recordingStatus=${observation.recordingStatusAfterCleanupStop ?: "--"}",
    )
    appendLine(
        "D2 commandDelta start=${commandStartDelta ?: "--"} stop=${commandStopDelta ?: "--"} " +
            "finalRuntimePhase=${runtime.phase} gateOpen=${runtime.fence.audioGateOpen}",
    )
    appendLine(
        "D2 files finalCount=${finalFiles?.count ?: "--"} deltaFromBaseline=${fileCountDelta ?: "--"} " +
            "freshness=${finalFiles?.freshness ?: "--"} error=${finalFiles?.error ?: "--"}",
    )
    appendLine("D2 newFiles=${d2FormatFileNames(newFiles)}")
    appendLine(
        "D2 facts baselineIdle=$baselineIdle realtimeStartChangedRecordingState=$realtimeStartChangedRecordingState " +
            "productionType3StartNoOp=$productionType3StartNoOp type3SaveEligible=$type3SaveEligible " +
            "type3SaveReturnedIdle=$type3SaveReturnedIdle",
    )
    appendLine(
        "D2 facts preSaveMediaObserved=$preSaveMediaObserved postSaveMediaContinued=$postSaveMediaContinued " +
            "runtimeStayedActiveAfterSave=$runtimeStayedActiveAfterSave cleanPostSaveWindow=$cleanPostSaveWindow " +
            "type3SaveReverseStoppedMedia=$type3SaveReverseStoppedMedia finalLifecycleClosed=$finalLifecycleClosed",
    )
    appendLine("D2 reverseEffect=$reverseEffect")
    appendLine("D2 executionValid=$executionValid")
    appendLine("D2 factStatus=${if (executionValid) "RESOLVED" else "PENDING"}")
}

private fun isRecordingOrPaused(status: String?): Boolean =
    status.equals("Recording", ignoreCase = true) || status.equals("Paused", ignoreCase = true)

private fun d2Delta(start: Long?, end: Long?): Long? =
    if (start != null && end != null) end - start else null

private fun d2FileCountDelta(start: Stage157D2FileSnapshot?, end: Stage157D2FileSnapshot?): Int? =
    if (start != null && end != null) end.count - start.count else null

private fun d2AddedFileNames(
    start: Stage157D2FileSnapshot?,
    end: Stage157D2FileSnapshot?,
): List<String>? =
    if (start != null && end != null) (end.names - start.names).sorted() else null

private fun d2FormatFileNames(names: List<String>?): String =
    names?.joinToString(prefix = "[", postfix = "]") ?: "--"
