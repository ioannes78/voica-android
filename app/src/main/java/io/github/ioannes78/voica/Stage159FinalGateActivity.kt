package io.github.ioannes78.voica

import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.material3.HorizontalDivider
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
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** QA-only Stage 15.9 final stability surface. It never owns BLE or media production. */
class Stage159FinalGateActivity : ComponentActivity() {
    private val qaJob = SupervisorJob()
    private val qaScope = CoroutineScope(qaJob + Dispatchers.Main.immediate)

    private lateinit var repository: DeviceRepository
    private lateinit var runtime: RealtimeAudioRuntime
    private lateinit var reconciler: RealtimeRecordingLifecycleReconciler

    private val canonicalAccumulator = Stage15CanonicalProbeAccumulator()
    private val runtimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())
    private val canonicalSnapshot = MutableStateFlow(Stage15CanonicalProbeSnapshot())
    private val baseline = MutableStateFlow<Stage159Baseline?>(null)
    private val heapWindow = MutableStateFlow(Stage159HeapWindow())
    private val elapsedMs = MutableStateFlow(0L)
    private val action = MutableStateFlow("先连接录音卡；开始测试前建立 Final Gate 基线")

    private var startedAtElapsedMs: Long? = null
    private var finishedAtElapsedMs: Long? = null
    private var nextHeapSampleAtElapsedMs = 0L
    private var lastDeviceAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoicaApplication).container
        repository = container.deviceRepository
        runtime = container.realtimeAudioRuntime
        reconciler = container.realtimeRecordingLifecycleReconciler

        qaScope.launch(Dispatchers.Default) {
            while (isActive) {
                val event = runtime.canonicalSource.receive() ?: break
                canonicalAccumulator.accept(event)
            }
        }
        qaScope.launch {
            while (isActive) {
                publishSnapshots()
                updateElapsedAndMemory()
                delay(UI_REFRESH_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage159FinalGateScreen(
                    repository = repository,
                    runtimeSnapshot = runtimeSnapshot,
                    canonicalSnapshot = canonicalSnapshot,
                    baseline = baseline,
                    heapWindow = heapWindow,
                    elapsedMs = elapsedMs,
                    action = action,
                    reconciler = reconciler,
                    onRealtime = ::sendRealtime,
                    onType3Start = ::type3Start,
                    onType3Save = ::type3Save,
                    onRefresh = ::refreshDeviceFacts,
                    onDisconnect = ::disconnect,
                    onReconnect = ::reconnectLastDevice,
                    onStartBaseline = ::startBaseline,
                    onFinishTiming = ::finishTiming,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::repository.isInitialized) repository.setForeground(true)
    }

    override fun onStop() {
        if (::repository.isInitialized) repository.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        qaScope.cancel()
        super.onDestroy()
    }

    private fun sendRealtime(command: RealtimeControlCommand) {
        qaScope.launch {
            action.value = "正在执行 realtime ${command.name}"
            val outcome = when (command) {
                RealtimeControlCommand.START -> runtime.start()
                RealtimeControlCommand.PAUSE -> runtime.pause()
                RealtimeControlCommand.RESUME -> runtime.resume()
                RealtimeControlCommand.STOP -> runtime.stop()
            }
            delay(COMMAND_SETTLE_MS)
            repository.syncRecordingState()
            publishSnapshots()
            action.value = buildString {
                append("realtime ${command.name} success=")
                append(outcome.success)
                outcome.failure?.let { append(" failure=$it") }
                append(" phase=${runtime.snapshot().phase}")
            }
        }
    }

    private fun type3Start() {
        qaScope.launch {
            action.value = "正在执行 production TYPE3 START"
            repository.startRecording()
            delay(COMMAND_SETTLE_MS)
            repository.syncRecordingState()
            action.value = "TYPE3 START 完成：recording=${repository.recordingState.value.status ?: "--"}"
        }
    }

    private fun type3Save() {
        qaScope.launch {
            action.value = "正在执行 production TYPE3 SAVE"
            repository.saveRecording()
            delay(TYPE3_SAVE_SETTLE_MS)
            repository.syncRecordingState()
            repository.refreshDeviceFiles()
            publishSnapshots()
            action.value =
                "TYPE3 SAVE 完成：recording=${repository.recordingState.value.status ?: "--"} " +
                    "runtime=${runtime.snapshot().phase}"
        }
    }

    private fun refreshDeviceFacts() {
        qaScope.launch {
            action.value = "正在同步录音状态与设备文件"
            repository.syncRecordingState()
            repository.refreshDeviceFiles()
            publishSnapshots()
            action.value = "设备事实已刷新"
        }
    }

    private fun disconnect() {
        val state = repository.connectionState.value
        if (state is DeviceConnectionState.Ready) lastDeviceAddress = state.address
        repository.disconnect()
        action.value = "已请求正常断开；上次设备=${lastDeviceAddress ?: "--"}"
    }

    private fun reconnectLastDevice() {
        val address = lastDeviceAddress
        if (address == null) {
            action.value = "没有可重连地址；请先从 Ready 状态执行一次断开"
            return
        }
        if (repository.missingPermissions().isNotEmpty()) {
            action.value = "蓝牙权限不足，请返回 Voica 处理权限"
            return
        }
        repository.connect(address)
        action.value = "正在使用 production connect() 重连 $address"
    }

    private fun startBaseline() {
        qaScope.launch {
            val current = runtime.snapshot()
            if (current.phase != RealtimeRuntimePhase.IDLE || current.source.sessionActive) {
                action.value = "请先 STOP 并等待 runtime IDLE/source inactive，再建立基线"
                return@launch
            }
            action.value = "正在建立 Stage 15.9 基线"
            repository.syncRecordingState()
            repository.refreshDeviceFiles()
            delay(FILE_SETTLE_MS)
            publishSnapshots()
            val now = SystemClock.elapsedRealtime()
            val heap = readStage159HeapSample()
            val canonical = canonicalAccumulator.snapshot()
            val reconcilerSnapshot = reconciler.snapshot.value
            val runtimeNow = runtime.snapshot()
            val files = repository.deviceFileListState.value.files.size
            baseline.value = Stage159Baseline(
                counters = captureStage159Counters(runtimeNow, canonical, reconcilerSnapshot, files),
                transportGeneration = runtimeNow.transportGeneration,
                canonicalSessionId = canonical.lastSessionId,
                fileCount = files,
            )
            heapWindow.value = Stage159HeapWindow().withSample(heap)
            startedAtElapsedMs = now
            finishedAtElapsedMs = null
            nextHeapSampleAtElapsedMs = now + HEAP_SAMPLE_INTERVAL_MS
            elapsedMs.value = 0L
            action.value = "基线已建立；稳定性计时开始"
        }
    }

    private fun finishTiming() {
        val started = startedAtElapsedMs
        if (started == null) {
            action.value = "尚未建立基线"
            return
        }
        finishedAtElapsedMs = SystemClock.elapsedRealtime()
        elapsedMs.value = (finishedAtElapsedMs!! - started).coerceAtLeast(0L)
        sampleHeap()
        publishSnapshots()
        action.value = "计时已冻结；请复制完整 Final Gate"
    }

    private fun publishSnapshots() {
        runtimeSnapshot.value = runtime.snapshot()
        canonicalSnapshot.value = canonicalAccumulator.snapshot()
    }

    private fun updateElapsedAndMemory() {
        val started = startedAtElapsedMs ?: return
        val now = SystemClock.elapsedRealtime()
        val end = finishedAtElapsedMs ?: now
        elapsedMs.value = (end - started).coerceAtLeast(0L)
        if (finishedAtElapsedMs == null && now >= nextHeapSampleAtElapsedMs) {
            sampleHeap()
            nextHeapSampleAtElapsedMs = now + HEAP_SAMPLE_INTERVAL_MS
        }
    }

    private fun sampleHeap() {
        heapWindow.value = heapWindow.value.withSample(readStage159HeapSample())
    }

    private companion object {
        const val UI_REFRESH_MS = 500L
        const val COMMAND_SETTLE_MS = 600L
        const val TYPE3_SAVE_SETTLE_MS = 1_000L
        const val FILE_SETTLE_MS = 750L
        const val HEAP_SAMPLE_INTERVAL_MS = 30_000L
    }
}

private data class Stage159Baseline(
    val counters: Stage159CounterSnapshot,
    val transportGeneration: Long?,
    val canonicalSessionId: Long?,
    val fileCount: Int,
)

@Composable
private fun Stage159FinalGateScreen(
    repository: DeviceRepository,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    canonicalSnapshot: MutableStateFlow<Stage15CanonicalProbeSnapshot>,
    baseline: MutableStateFlow<Stage159Baseline?>,
    heapWindow: MutableStateFlow<Stage159HeapWindow>,
    elapsedMs: MutableStateFlow<Long>,
    action: MutableStateFlow<String>,
    reconciler: RealtimeRecordingLifecycleReconciler,
    onRealtime: (RealtimeControlCommand) -> Unit,
    onType3Start: () -> Unit,
    onType3Save: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onStartBaseline: () -> Unit,
    onFinishTiming: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val connection by repository.connectionState.collectAsState()
    val recording by repository.recordingState.collectAsState()
    val files by repository.deviceFileListState.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val canonical by canonicalSnapshot.collectAsState()
    val base by baseline.collectAsState()
    val heap by heapWindow.collectAsState()
    val elapsed by elapsedMs.collectAsState()
    val actionText by action.collectAsState()
    val reconcilerSnapshot by reconciler.snapshot.collectAsState()

    val rendered = renderStage159FinalGate(
        connection = connection,
        recordingStatus = recording.status?.name,
        recordingFreshness = recording.freshness.name,
        recordingCommandState = recording.commandState.name,
        recordingFilename = recording.filename,
        runtime = runtime,
        canonical = canonical,
        reconciler = reconcilerSnapshot,
        fileCount = files.files.size,
        baseline = base,
        heap = heap,
        elapsedMs = elapsed,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Stage 15.9 Final Gate", style = MaterialTheme.typography.headlineSmall)
                Text("连接：${stage159ConnectionLabel(connection)}")
                Text("录音：${recording.status ?: "--"} · runtime=${runtime.phase}")
                Text("操作：$actionText", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Realtime", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onRealtime(RealtimeControlCommand.START) }) { Text("START") }
                        Button(onClick = { onRealtime(RealtimeControlCommand.PAUSE) }) { Text("PAUSE") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onRealtime(RealtimeControlCommand.RESUME) }) { Text("RESUME") }
                        Button(onClick = { onRealtime(RealtimeControlCommand.STOP) }) { Text("STOP") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("设备生命周期", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onType3Start) { Text("TYPE3 START") }
                        OutlinedButton(onClick = onType3Save) { Text("TYPE3 SAVE") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onDisconnect) { Text("正常断开") }
                        OutlinedButton(onClick = onReconnect) { Text("重连上次设备") }
                    }
                    OutlinedButton(onClick = onRefresh) { Text("刷新设备事实") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("稳定性计时", style = MaterialTheme.typography.titleMedium)
                    Text("Elapsed ${formatStage159Elapsed(elapsed)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onStartBaseline) { Text("建立基线并计时") }
                        OutlinedButton(onClick = onFinishTiming) { Text("结束计时") }
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(rendered)) },
                    ) {
                        Text("复制完整 Final Gate")
                    }
                }
            }
        }

        item { HorizontalDivider() }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    rendered,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun renderStage159FinalGate(
    connection: DeviceConnectionState,
    recordingStatus: String?,
    recordingFreshness: String,
    recordingCommandState: String,
    recordingFilename: String?,
    runtime: RealtimeAudioRuntimeSnapshot,
    canonical: Stage15CanonicalProbeSnapshot,
    reconciler: RealtimeRecordingLifecycleReconcilerSnapshot,
    fileCount: Int,
    baseline: Stage159Baseline?,
    heap: Stage159HeapWindow,
    elapsedMs: Long,
): String = buildString {
    val current = captureStage159Counters(runtime, canonical, reconciler, fileCount)
    val delta = baseline?.let { current.deltaFrom(it.counters) }
    val ingress = runtime.ingress
    val pipeline = runtime.pipeline
    val source = runtime.source
    val command = runtime.command
    val fence = runtime.fence
    val anomalyCount = delta?.anomalyCount()
    val result = when {
        delta == null -> "NO_BASELINE"
        anomalyCount != 0L -> "ANOMALY"
        else -> "HEALTHY_SO_FAR"
    }

    appendLine("Stage 15.9 Final Stability Gate")
    appendLine("commit=${BuildConfig.GIT_SHA}")
    appendLine("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) applicationId=${BuildConfig.APPLICATION_ID}")
    appendLine("elapsed=${formatStage159Elapsed(elapsedMs)}")
    appendLine("connection=${stage159ConnectionLabel(connection)}")
    appendLine("transportGeneration baseline=${baseline?.transportGeneration ?: "--"} current=${runtime.transportGeneration ?: "--"}")
    appendLine(
        "recording status=${recordingStatus ?: "--"} freshness=$recordingFreshness " +
            "commandState=$recordingCommandState filename=${recordingFilename ?: "--"}",
    )
    appendLine()
    appendLine("runtime")
    appendLine(" phase=${runtime.phase} gateOpen=${fence.audioGateOpen} sourceActive=${source.sessionActive}")
    appendLine(" activeSession=${source.activeSessionId ?: "--"} activeEpoch=${source.activeEpochId ?: "--"}")
    appendLine(
        " ownerCreate=${runtime.transportRuntimeCreates} ownerRelease=${runtime.transportRuntimeReleases} " +
            "decoderCreate=${runtime.decoderCreateCount} decoderRelease=${runtime.decoderReleaseCount}",
    )
    appendLine(
        " commandDelta start=${delta?.startSuccess ?: 0}/${delta?.startFailure ?: 0} " +
            "pause=${delta?.pauseSuccess ?: 0}/${delta?.pauseFailure ?: 0} " +
            "resume=${delta?.resumeSuccess ?: 0}/${delta?.resumeFailure ?: 0} " +
            "stop=${delta?.stopSuccess ?: 0}/${delta?.stopFailure ?: 0}",
    )
    appendLine(" commandLast=${command.lastCommand ?: "--"} failure=${command.lastFailure ?: "--"}")
    appendLine()
    appendLine("media delta")
    appendLine(" acceptedBlocks=${delta?.acceptedBlocks ?: 0} acceptedBytes=${delta?.acceptedBytes ?: 0}")
    appendLine(" opusPackets=${delta?.opusPackets ?: 0} decodeSuccess=${delta?.decodeSuccess ?: 0} decodeFailures=${delta?.decodeFailures ?: 0}")
    appendLine(" canonicalFrames=${delta?.canonicalFrames ?: 0} canonicalSamples=${delta?.canonicalSamples ?: 0}")
    appendLine(" canonicalDurationSec=${formatStage1593((delta?.canonicalSamples ?: 0) / 16000.0)}")
    appendLine()
    appendLine("anomaly delta")
    appendLine(
        " ingressOverflow=${delta?.ingressOverflow ?: 0} malformed=${delta?.malformedBlocks ?: 0} " +
            "droppedBlocks=${delta?.droppedBlocks ?: 0}",
    )
    appendLine(
        " invalidOpus=${delta?.invalidOpusPackets ?: 0} zeroSampleDecode=${delta?.zeroSampleDecodeFailures ?: 0} " +
            "sourceOverflow=${delta?.sourceOverflow ?: 0} sourceDroppedFrames=${delta?.sourceDroppedFrames ?: 0}",
    )
    appendLine(
        " gap=${delta?.canonicalGaps ?: 0} overlap=${delta?.canonicalOverlaps ?: 0} " +
            "invalidEpochStart=${delta?.invalidEpochStarts ?: 0} endMismatch=${delta?.sessionEndMismatches ?: 0} " +
            "drainTimeoutEnds=${delta?.drainTimeoutEnds ?: 0}",
    )
    appendLine()
    appendLine("timeline delta")
    appendLine(" sessions=${delta?.sessions ?: 0} epochs=${delta?.epochs ?: 0} discontinuities=${delta?.discontinuities ?: 0}")
    appendLine(
        " boundaries transport=${delta?.transportBoundaries ?: 0} control=${delta?.controlBoundaries ?: 0} " +
            "recovery=${delta?.decoderRecoveryBoundaries ?: 0}",
    )
    appendLine(
        " source started=${delta?.sourceSessionStarted ?: 0} ended=${delta?.sourceSessionEnded ?: 0} " +
            "consumerDiscontinuities=${delta?.consumerDiscontinuities ?: 0}",
    )
    appendLine(" canonicalObserver sessions=${canonical.sessionsSeen} epochs=${canonical.epochsSeen} lastSession=${canonical.lastSessionId ?: "--"}")
    appendLine(" lastEnd=${source.lastSessionEndReason ?: canonical.lastSessionEndReason ?: "--"} drainTimedOut=${source.lastSessionEndDrainTimedOut}")
    appendLine()
    appendLine("reconciliation delta")
    appendLine(
        " runtime attempts=${delta?.reconciliationAttempts ?: 0} completed=${delta?.reconciliationCompleted ?: 0} " +
            "fallback=${delta?.reconciliationFallback ?: 0} failure=${delta?.reconciliationFailures ?: 0}",
    )
    appendLine(
        " app requested=${delta?.appReconciliationRequested ?: 0} completed=${delta?.appReconciliationCompleted ?: 0} " +
            "fallback=${delta?.appReconciliationFallback ?: 0} failure=${delta?.appReconciliationFailures ?: 0}",
    )
    appendLine()
    appendLine("memory")
    appendLine(
        " heapStartMiB=${formatStage159MiB(heap.startBytes)} heapCurrentMiB=${formatStage159MiB(heap.currentBytes)} " +
            "heapPeakMiB=${formatStage159MiB(heap.peakBytes)} heapMaxMiB=${formatStage159MiB(heap.maxBytes)}",
    )
    appendLine()
    appendLine("queue")
    appendLine(
        " ingressCurrent=${ingress.queueBlocks}/${ingress.queueBytes} " +
            "ingressPeak=${ingress.peakQueueBlocks}/${ingress.peakQueueBytes}",
    )
    appendLine(
        " sourceCurrent=${source.queuedPcmFrames}/${source.queuedPcmSamples} " +
            "sourcePeak=${source.peakQueuedPcmFrames}/${source.peakQueuedPcmSamples}",
    )
    appendLine()
    appendLine("performance")
    appendLine(" maxBlockProcessingMs=${formatStage1593(pipeline.maxBlockProcessingMs)}")
    appendLine(" maxArrivalToEmitLagMs=${formatStage1593(pipeline.maxArrivalToEmitLagMs)}")
    appendLine(" pipelineLastError=${pipeline.lastError ?: "--"}")
    appendLine()
    appendLine("files")
    appendLine(" baseline=${baseline?.fileCount ?: "--"} current=$fileCount delta=${delta?.fileCount ?: 0}")
    appendLine("result=$result anomalyDelta=${anomalyCount ?: "--"}")
}

private fun stage159ConnectionLabel(state: DeviceConnectionState): String =
    when (state) {
        DeviceConnectionState.Unavailable -> "Unavailable"
        is DeviceConnectionState.PermissionRequired -> "PermissionRequired"
        DeviceConnectionState.BluetoothOff -> "BluetoothOff"
        DeviceConnectionState.Idle -> "Idle"
        DeviceConnectionState.Scanning -> "Scanning"
        is DeviceConnectionState.Connecting -> "Connecting ${state.address}"
        is DeviceConnectionState.LinkConnected -> "LinkConnected ${state.address}"
        is DeviceConnectionState.DiscoveringServices -> "DiscoveringServices ${state.address}"
        is DeviceConnectionState.Subscribing -> "Subscribing ${state.address}"
        is DeviceConnectionState.NegotiatingMtu -> "NegotiatingMtu ${state.address}"
        is DeviceConnectionState.Ready -> "Ready ${state.address} mtu=${state.negotiatedMtu}"
        is DeviceConnectionState.Disconnecting -> "Disconnecting ${state.address ?: "--"}"
        is DeviceConnectionState.Disconnected -> "Disconnected ${state.reason}"
        is DeviceConnectionState.ReconnectWaiting -> "ReconnectWaiting attempt=${state.attempt}"
        is DeviceConnectionState.Error -> "Error ${state.error.code}"
    }

private fun formatStage159Elapsed(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatStage159MiB(bytes: Long): String =
    if (bytes <= 0L) "--" else formatStage1593(bytes / (1024.0 * 1024.0))

private fun formatStage1593(value: Double): String = String.format(Locale.US, "%.3f", value)
