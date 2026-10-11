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
import kotlinx.coroutines.withTimeoutOrNull

/** Stage 15.8 QA-only reconciliation final gate. */
class Stage158ReconciliationProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime
    private lateinit var reconciler: RealtimeRecordingLifecycleReconciler

    private val baseline = MutableStateFlow<Stage158Baseline?>(null)
    private val observation = MutableStateFlow(Stage158Observation())
    private val action = MutableStateFlow("等待设备连接")
    private val runtimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime
        reconciler = container.realtimeRecordingLifecycleReconciler

        // This is a control/lifecycle gate, not a backpressure gate.
        probeScope.launch(Dispatchers.Default) {
            while (isActive) {
                realtimeRuntime.canonicalSource.receive() ?: break
            }
        }
        probeScope.launch {
            while (isActive) {
                runtimeSnapshot.value = realtimeRuntime.snapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage158Screen(
                    repository = deviceRepository,
                    runtimeSnapshot = runtimeSnapshot,
                    baseline = baseline,
                    observation = observation,
                    action = action,
                    reconciler = reconciler,
                    onGateAStart = ::gateAStart,
                    onGateASave = ::gateASave,
                    onGateBCStart = ::gateBCStart,
                    onGateBCStop = ::gateBCStop,
                    onGateDStart = ::gateDStart,
                    onGateDSave = ::gateDSave,
                    onSync = ::syncRecording,
                    onReset = ::reset,
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

    private fun gateAStart() {
        if (!connectionReady()) {
            action.value = "请先连接录音卡并等待 Ready"
            return
        }
        probeScope.launch {
            action.value = "Gate A1：建立基线并启动 realtime"
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_SETTLE_MS)
            baseline.value = Stage158Baseline(
                runtime = runtimeMarker(),
                reconciler = reconcilerMarker(),
                fileCount = deviceRepository.deviceFileListState.value.files.size,
            )
            observation.value = Stage158Observation()

            val outcome = realtimeRuntime.start()
            delay(START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            observation.value = observation.value.copy(
                gateAStartSuccess = outcome.success,
                gateAStartFailure = outcome.failure?.name,
                gateAAfterStart = runtimeMarker(),
                gateARecordingAfterStart = recordingMarker(),
            )
            action.value = if (outcome.success) {
                "Gate A1 完成：讲话约5秒，再点击 Gate A2 TYPE3 SAVE"
            } else {
                "Gate A1 START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun gateASave() {
        if (observation.value.gateAStartSuccess != true) {
            action.value = "请先完成 Gate A1"
            return
        }
        probeScope.launch {
            action.value = "Gate A2：production TYPE3 SAVE；不手工 realtime STOP"
            deviceRepository.syncRecordingState()
            val before = recordingMarker()
            val eligible = before.status == "Recording" || before.status == "Paused"
            val beforeRuntime = runtimeMarker()
            deviceRepository.saveRecording()
            delay(SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()

            withTimeoutOrNull(RECONCILIATION_WAIT_MS) {
                while (true) {
                    val runtime = realtimeRuntime.snapshot()
                    if (runtime.phase == RealtimeRuntimePhase.IDLE && !runtime.source.sessionActive) break
                    delay(50L)
                }
            }
            deviceRepository.syncRecordingState()
            deviceRepository.refreshDeviceFiles()
            delay(FILE_SETTLE_MS)

            observation.value = observation.value.copy(
                gateASaveEligible = eligible,
                gateARecordingBeforeSave = before,
                gateARuntimeBeforeSave = beforeRuntime,
                gateARecordingAfterSave = recordingMarker(),
                gateAAfterSave = runtimeMarker(),
                gateAReconcilerAfterSave = reconcilerMarker(),
                gateAFileCountAfterSave = deviceRepository.deviceFileListState.value.files.size,
            )
            action.value = "Gate A2 完成：不要补 STOP；继续 Gate BC1 第二次 realtime START"
        }
    }

    private fun gateBCStart() {
        if (observation.value.gateAAfterSave == null) {
            action.value = "请先完成 Gate A2"
            return
        }
        probeScope.launch {
            action.value = "Gate BC1：同连接第二次 realtime START"
            val outcome = realtimeRuntime.start()
            delay(START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            observation.value = observation.value.copy(
                gateBCStartSuccess = outcome.success,
                gateBCStartFailure = outcome.failure?.name,
                gateBCAfterStart = runtimeMarker(),
                gateBCRecordingAfterStart = recordingMarker(),
            )
            action.value = if (outcome.success) {
                "Gate BC1 完成：讲话约5秒，再点击 Gate BC2 explicit STOP"
            } else {
                "Gate BC1 START 失败：${outcome.failure ?: "unknown"}"
            }
        }
    }

    private fun gateBCStop() {
        if (observation.value.gateBCStartSuccess != true) {
            action.value = "请先完成 Gate BC1"
            return
        }
        probeScope.launch {
            action.value = "Gate BC2：正常 explicit realtime STOP"
            val beforeReconciler = reconcilerMarker()
            val beforeRuntime = runtimeMarker()
            val outcome = realtimeRuntime.stop()
            delay(STOP_SETTLE_MS)
            deviceRepository.syncRecordingState()
            observation.value = observation.value.copy(
                gateBCStopSuccess = outcome.success,
                gateBCStopFailure = outcome.failure?.name,
                gateBCRuntimeBeforeStop = beforeRuntime,
                gateBCAfterStop = runtimeMarker(),
                gateBCRecordingAfterStop = recordingMarker(),
                gateBCReconcilerBeforeStop = beforeReconciler,
                gateBCReconcilerAfterStop = reconcilerMarker(),
            )
            action.value = "Gate BC2 完成：继续 Gate D1 TYPE3-only START"
        }
    }

    private fun gateDStart() {
        if (observation.value.gateBCAfterStop == null) {
            action.value = "请先完成 Gate BC2"
            return
        }
        probeScope.launch {
            action.value = "Gate D1：TYPE3-only START"
            deviceRepository.syncRecordingState()
            val beforeReconciler = reconcilerMarker()
            deviceRepository.startRecording()
            delay(TYPE3_START_SETTLE_MS)
            deviceRepository.syncRecordingState()
            observation.value = observation.value.copy(
                gateDReconcilerBefore = beforeReconciler,
                gateDRecordingAfterStart = recordingMarker(),
                gateDRuntimeAfterStart = runtimeMarker(),
            )
            action.value = "Gate D1 完成：讲话约5秒，再点击 Gate D2 TYPE3 SAVE"
        }
    }

    private fun gateDSave() {
        if (observation.value.gateDRecordingAfterStart == null) {
            action.value = "请先完成 Gate D1"
            return
        }
        probeScope.launch {
            action.value = "Gate D2：TYPE3-only SAVE"
            deviceRepository.saveRecording()
            delay(SAVE_SETTLE_MS)
            deviceRepository.syncRecordingState()
            delay(RECONCILER_SETTLE_MS)
            observation.value = observation.value.copy(
                gateDRecordingAfterSave = recordingMarker(),
                gateDRuntimeAfterSave = runtimeMarker(),
                gateDReconcilerAfter = reconcilerMarker(),
            )
            action.value = "Stage 15.8 Gate 完成：复制完整 Gate"
        }
    }

    private fun syncRecording() {
        probeScope.launch {
            deviceRepository.syncRecordingState()
            action.value = "已同步设备录音状态"
        }
    }

    private fun reset() {
        baseline.value = null
        observation.value = Stage158Observation()
        runtimeSnapshot.value = realtimeRuntime.snapshot()
        action.value = "已重置 Stage15.8；进程级累计 counters 不清零"
    }

    private fun connectionReady(): Boolean =
        deviceRepository.connectionState.value is DeviceConnectionState.Ready

    private fun recordingMarker(): Stage158RecordingMarker {
        val state = deviceRepository.recordingState.value
        return Stage158RecordingMarker(
            status = state.status?.name,
            freshness = state.freshness.name,
            commandState = state.commandState.name,
            filename = state.filename,
            sizeBytes = state.currentSizeBytes,
        )
    }

    private fun runtimeMarker(): Stage158RuntimeMarker {
        val state = realtimeRuntime.snapshot()
        return Stage158RuntimeMarker(
            phase = state.phase.name,
            gateOpen = state.fence.audioGateOpen,
            acceptedBlocks = state.ingress.acceptedBlocks,
            canonicalSamples = state.pipeline.canonicalSamples,
            sourceActive = state.source.sessionActive,
            sourceStarted = state.source.sessionStartedEvents,
            sourceEnded = state.source.sessionEndedEvents,
            sourceLastEnd = state.source.lastSessionEndReason?.name,
            sourceDrainTimedOut = state.source.lastSessionEndDrainTimedOut,
            startSuccess = state.command.startSuccess,
            stopSuccess = state.command.stopSuccess,
            reconciliationAttempts = state.reconciliation.attempts,
            reconciliationStopWriteSuccess = state.reconciliation.stopWriteSuccess,
            reconciliationStopWriteFailure = state.reconciliation.stopWriteFailure,
            reconciliationFallback = state.reconciliation.fallbackCount,
            reconciliationCompleted = state.reconciliation.completed,
            reconciliationNoOp = state.reconciliation.noOp,
            reconciliationFailure = state.reconciliation.failure,
            reconciliationLastResult = state.reconciliation.lastResult?.name,
            reconciliationLastStopFailure = state.reconciliation.lastStopFailure?.name,
        )
    }

    private fun reconcilerMarker(): Stage158ReconcilerMarker {
        val state = reconciler.snapshot.value
        return Stage158ReconcilerMarker(
            endedTransitions = state.recordingEndedTransitions,
            requested = state.reconciliationRequested,
            completed = state.reconciliationCompleted,
            fallbackCompleted = state.reconciliationFallbackCompleted,
            noOp = state.reconciliationNoOp,
            failure = state.reconciliationFailure,
            lastRuntimePhase = state.lastRuntimePhase?.name,
            lastResult = state.lastResult?.name,
        )
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
        const val START_SETTLE_MS = 500L
        const val STOP_SETTLE_MS = 600L
        const val SAVE_SETTLE_MS = 650L
        const val TYPE3_START_SETTLE_MS = 500L
        const val RECONCILIATION_WAIT_MS = 3_500L
        const val RECONCILER_SETTLE_MS = 800L
        const val FILE_SETTLE_MS = 750L
    }
}

private data class Stage158RecordingMarker(
    val status: String?,
    val freshness: String,
    val commandState: String,
    val filename: String?,
    val sizeBytes: Long?,
)

private data class Stage158RuntimeMarker(
    val phase: String,
    val gateOpen: Boolean,
    val acceptedBlocks: Long,
    val canonicalSamples: Long,
    val sourceActive: Boolean,
    val sourceStarted: Long,
    val sourceEnded: Long,
    val sourceLastEnd: String?,
    val sourceDrainTimedOut: Boolean,
    val startSuccess: Long,
    val stopSuccess: Long,
    val reconciliationAttempts: Long,
    val reconciliationStopWriteSuccess: Long,
    val reconciliationStopWriteFailure: Long,
    val reconciliationFallback: Long,
    val reconciliationCompleted: Long,
    val reconciliationNoOp: Long,
    val reconciliationFailure: Long,
    val reconciliationLastResult: String?,
    val reconciliationLastStopFailure: String?,
)

private data class Stage158ReconcilerMarker(
    val endedTransitions: Long,
    val requested: Long,
    val completed: Long,
    val fallbackCompleted: Long,
    val noOp: Long,
    val failure: Long,
    val lastRuntimePhase: String?,
    val lastResult: String?,
)

private data class Stage158Baseline(
    val runtime: Stage158RuntimeMarker,
    val reconciler: Stage158ReconcilerMarker,
    val fileCount: Int,
)

private data class Stage158Observation(
    val gateAStartSuccess: Boolean? = null,
    val gateAStartFailure: String? = null,
    val gateAAfterStart: Stage158RuntimeMarker? = null,
    val gateARecordingAfterStart: Stage158RecordingMarker? = null,
    val gateASaveEligible: Boolean? = null,
    val gateARecordingBeforeSave: Stage158RecordingMarker? = null,
    val gateARuntimeBeforeSave: Stage158RuntimeMarker? = null,
    val gateARecordingAfterSave: Stage158RecordingMarker? = null,
    val gateAAfterSave: Stage158RuntimeMarker? = null,
    val gateAReconcilerAfterSave: Stage158ReconcilerMarker? = null,
    val gateAFileCountAfterSave: Int? = null,
    val gateBCStartSuccess: Boolean? = null,
    val gateBCStartFailure: String? = null,
    val gateBCAfterStart: Stage158RuntimeMarker? = null,
    val gateBCRecordingAfterStart: Stage158RecordingMarker? = null,
    val gateBCStopSuccess: Boolean? = null,
    val gateBCStopFailure: String? = null,
    val gateBCRuntimeBeforeStop: Stage158RuntimeMarker? = null,
    val gateBCAfterStop: Stage158RuntimeMarker? = null,
    val gateBCRecordingAfterStop: Stage158RecordingMarker? = null,
    val gateBCReconcilerBeforeStop: Stage158ReconcilerMarker? = null,
    val gateBCReconcilerAfterStop: Stage158ReconcilerMarker? = null,
    val gateDReconcilerBefore: Stage158ReconcilerMarker? = null,
    val gateDRecordingAfterStart: Stage158RecordingMarker? = null,
    val gateDRuntimeAfterStart: Stage158RuntimeMarker? = null,
    val gateDRecordingAfterSave: Stage158RecordingMarker? = null,
    val gateDRuntimeAfterSave: Stage158RuntimeMarker? = null,
    val gateDReconcilerAfter: Stage158ReconcilerMarker? = null,
)

@Composable
private fun Stage158Screen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    baseline: MutableStateFlow<Stage158Baseline?>,
    observation: MutableStateFlow<Stage158Observation>,
    action: MutableStateFlow<String>,
    reconciler: RealtimeRecordingLifecycleReconciler,
    onGateAStart: () -> Unit,
    onGateASave: () -> Unit,
    onGateBCStart: () -> Unit,
    onGateBCStop: () -> Unit,
    onGateDStart: () -> Unit,
    onGateDSave: () -> Unit,
    onSync: () -> Unit,
    onReset: () -> Unit,
) {
    val connection by repository.connectionState.collectAsState()
    val recording by repository.recordingState.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val baselineValue by baseline.collectAsState()
    val observationValue by observation.collectAsState()
    val actionText by action.collectAsState()
    val reconcilerState by reconciler.snapshot.collectAsState()
    val clipboard = LocalClipboardManager.current

    val gate = renderStage158Gate(
        connectionReady = connection is DeviceConnectionState.Ready,
        currentRecording = Stage158RecordingMarker(
            status = recording.status?.name,
            freshness = recording.freshness.name,
            commandState = recording.commandState.name,
            filename = recording.filename,
            sizeBytes = recording.currentSizeBytes,
        ),
        currentRuntime = runtime,
        currentReconciler = reconcilerState,
        baseline = baselineValue,
        observation = observationValue,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Stage 15.8 Reconciliation Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接 Ready：${connection is DeviceConnectionState.Ready}")
                Text("操作：$actionText")
                Text("Gate A：realtime START → TYPE3 SAVE → 自动收敛（禁止手工STOP）")
                Text("Gate BC：同连接第二次 START → explicit STOP")
                Text("Gate D：TYPE3-only START → SAVE")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGateAStart) { Text("A1 realtime START") }
                Button(onClick = onGateASave) { Text("A2 TYPE3 SAVE") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGateBCStart) { Text("BC1 second START") }
                Button(onClick = onGateBCStop) { Text("BC2 explicit STOP") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGateDStart) { Text("D1 TYPE3 START") }
                Button(onClick = onGateDSave) { Text("D2 TYPE3 SAVE") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSync) { Text("同步录音状态") }
                OutlinedButton(onClick = onReset) { Text("重置15.8") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(gate)) }) { Text("复制15.8 Gate") }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(gate, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun renderStage158Gate(
    connectionReady: Boolean,
    currentRecording: Stage158RecordingMarker,
    currentRuntime: RealtimeAudioRuntimeSnapshot,
    currentReconciler: RealtimeRecordingLifecycleReconcilerSnapshot,
    baseline: Stage158Baseline?,
    observation: Stage158Observation,
): String = buildString {
    appendLine("Stage 15.8 Realtime / Recording Lifecycle Reconciliation Gate")
    appendLine("connectionReady=$connectionReady")
    appendLine("recording currentStatus=${currentRecording.status ?: "--"} freshness=${currentRecording.freshness} commandState=${currentRecording.commandState} sizeBytes=${currentRecording.sizeBytes ?: 0} filename=${currentRecording.filename ?: "--"}")
    appendLine("runtime currentPhase=${currentRuntime.phase} gateOpen=${currentRuntime.fence.audioGateOpen} sourceActive=${currentRuntime.source.sessionActive} lastEnd=${currentRuntime.source.lastSessionEndReason ?: "--"}")
    appendLine("reconciler current ended=${currentReconciler.recordingEndedTransitions} requested=${currentReconciler.reconciliationRequested} completed=${currentReconciler.reconciliationCompleted} noOp=${currentReconciler.reconciliationNoOp} failure=${currentReconciler.reconciliationFailure}")

    if (baseline == null) {
        appendLine("15.8 baseline=--")
        append("15.8 factStatus=PENDING")
        return@buildString
    }

    appendLine("15.8 baseline runtimePhase=${baseline.runtime.phase} gateOpen=${baseline.runtime.gateOpen} sourceActive=${baseline.runtime.sourceActive} files=${baseline.fileCount}")
    appendLine("15.8 baseline reconcile runtimeAttempts=${baseline.runtime.reconciliationAttempts} completed=${baseline.runtime.reconciliationCompleted} fallback=${baseline.runtime.reconciliationFallback} reconcilerRequested=${baseline.reconciler.requested}")

    appendLine("A start outcome=${observation.gateAStartSuccess ?: false}/${observation.gateAStartFailure ?: "--"} recording=${observation.gateARecordingAfterStart?.status ?: "--"}")
    appendLine("A save eligible=${observation.gateASaveEligible ?: false} beforeStatus=${observation.gateARecordingBeforeSave?.status ?: "--"} afterStatus=${observation.gateARecordingAfterSave?.status ?: "--"}")
    appendLine("A runtime afterSave phase=${observation.gateAAfterSave?.phase ?: "--"} gateOpen=${observation.gateAAfterSave?.gateOpen ?: false} sourceActive=${observation.gateAAfterSave?.sourceActive ?: false} lastEnd=${observation.gateAAfterSave?.sourceLastEnd ?: "--"}")
    appendLine("A reconciliation runtimeDelta attempts=${delta(baseline.runtime.reconciliationAttempts, observation.gateAAfterSave?.reconciliationAttempts)} stopWriteSuccess=${delta(baseline.runtime.reconciliationStopWriteSuccess, observation.gateAAfterSave?.reconciliationStopWriteSuccess)} stopWriteFailure=${delta(baseline.runtime.reconciliationStopWriteFailure, observation.gateAAfterSave?.reconciliationStopWriteFailure)} fallback=${delta(baseline.runtime.reconciliationFallback, observation.gateAAfterSave?.reconciliationFallback)} completed=${delta(baseline.runtime.reconciliationCompleted, observation.gateAAfterSave?.reconciliationCompleted)} failure=${delta(baseline.runtime.reconciliationFailure, observation.gateAAfterSave?.reconciliationFailure)}")
    appendLine("A reconciliation appDelta requested=${delta(baseline.reconciler.requested, observation.gateAReconcilerAfterSave?.requested)} completed=${delta(baseline.reconciler.completed, observation.gateAReconcilerAfterSave?.completed)} fallback=${delta(baseline.reconciler.fallbackCompleted, observation.gateAReconcilerAfterSave?.fallbackCompleted)} failure=${delta(baseline.reconciler.failure, observation.gateAReconcilerAfterSave?.failure)} lastResult=${observation.gateAReconcilerAfterSave?.lastResult ?: "--"}")
    appendLine("A sourceDelta ended=${delta(baseline.runtime.sourceEnded, observation.gateAAfterSave?.sourceEnded)} filesDelta=${observation.gateAFileCountAfterSave?.minus(baseline.fileCount) ?: 0}")

    val gateAAutoConverged =
        observation.gateARecordingAfterSave?.status == "Idle" &&
            observation.gateAAfterSave?.phase == RealtimeRuntimePhase.IDLE.name &&
            observation.gateAAfterSave.gateOpen.not() &&
            observation.gateAAfterSave.sourceActive.not() &&
            observation.gateAAfterSave.sourceLastEnd == "DEVICE_RECORDING_ENDED" &&
            delta(baseline.reconciler.requested, observation.gateAReconcilerAfterSave?.requested) == 1L
    appendLine("A facts autoConverged=$gateAAutoConverged noManualRealtimeStop=true")

    appendLine("BC secondStart outcome=${observation.gateBCStartSuccess ?: false}/${observation.gateBCStartFailure ?: "--"} phase=${observation.gateBCAfterStart?.phase ?: "--"} sourceActive=${observation.gateBCAfterStart?.sourceActive ?: false}")
    appendLine("BC explicitStop outcome=${observation.gateBCStopSuccess ?: false}/${observation.gateBCStopFailure ?: "--"} phase=${observation.gateBCAfterStop?.phase ?: "--"} gateOpen=${observation.gateBCAfterStop?.gateOpen ?: false} sourceActive=${observation.gateBCAfterStop?.sourceActive ?: false} lastEnd=${observation.gateBCAfterStop?.sourceLastEnd ?: "--"}")
    appendLine("BC mediaDelta acceptedBlocks=${delta(observation.gateAAfterSave?.acceptedBlocks, observation.gateBCRuntimeBeforeStop?.acceptedBlocks)} canonicalSamples=${delta(observation.gateAAfterSave?.canonicalSamples, observation.gateBCRuntimeBeforeStop?.canonicalSamples)}")
    appendLine("BC reconcilerDelta requested=${delta(observation.gateBCReconcilerBeforeStop?.requested, observation.gateBCReconcilerAfterStop?.requested)} completed=${delta(observation.gateBCReconcilerBeforeStop?.completed, observation.gateBCReconcilerAfterStop?.completed)}")

    val gateBCPassed =
        observation.gateBCStartSuccess == true &&
            (delta(observation.gateAAfterSave?.canonicalSamples, observation.gateBCRuntimeBeforeStop?.canonicalSamples) ?: 0L) > 0L &&
            observation.gateBCStopSuccess == true &&
            observation.gateBCAfterStop?.phase == RealtimeRuntimePhase.IDLE.name &&
            observation.gateBCAfterStop.sourceLastEnd == "STOP" &&
            delta(observation.gateBCReconcilerBeforeStop?.requested, observation.gateBCReconcilerAfterStop?.requested) == 0L
    appendLine("BC facts secondSessionHealthy=$gateBCPassed explicitStopDidNotReconcile=${delta(observation.gateBCReconcilerBeforeStop?.requested, observation.gateBCReconcilerAfterStop?.requested) == 0L}")

    appendLine("D TYPE3-only afterStart recording=${observation.gateDRecordingAfterStart?.status ?: "--"} runtimePhase=${observation.gateDRuntimeAfterStart?.phase ?: "--"}")
    appendLine("D TYPE3-only afterSave recording=${observation.gateDRecordingAfterSave?.status ?: "--"} runtimePhase=${observation.gateDRuntimeAfterSave?.phase ?: "--"}")
    appendLine("D reconcilerDelta requested=${delta(observation.gateDReconcilerBefore?.requested, observation.gateDReconcilerAfter?.requested)} completed=${delta(observation.gateDReconcilerBefore?.completed, observation.gateDReconcilerAfter?.completed)} noOp=${delta(observation.gateDReconcilerBefore?.noOp, observation.gateDReconcilerAfter?.noOp)}")

    val gateDPassed =
        observation.gateDRecordingAfterStart?.status == "Recording" &&
            observation.gateDRuntimeAfterStart?.phase == RealtimeRuntimePhase.IDLE.name &&
            observation.gateDRecordingAfterSave?.status == "Idle" &&
            observation.gateDRuntimeAfterSave?.phase == RealtimeRuntimePhase.IDLE.name &&
            delta(observation.gateDReconcilerBefore?.requested, observation.gateDReconcilerAfter?.requested) == 0L
    appendLine("D facts type3OnlyDoesNotStartRealtime=$gateDPassed")

    val executionValid =
        connectionReady &&
            observation.gateAStartSuccess == true &&
            observation.gateASaveEligible == true &&
            observation.gateAAfterSave != null &&
            observation.gateBCStartSuccess == true &&
            observation.gateBCStopSuccess == true &&
            observation.gateDRecordingAfterSave != null
    appendLine("15.8 executionValid=$executionValid")
    append("15.8 factStatus=${if (executionValid && gateAAutoConverged && gateBCPassed && gateDPassed) "PASS" else if (executionValid) "RESOLVED_WITH_FAILURE" else "PENDING"}")
}

private fun delta(before: Long?, after: Long?): Long? =
    if (before == null || after == null) null else after - before
