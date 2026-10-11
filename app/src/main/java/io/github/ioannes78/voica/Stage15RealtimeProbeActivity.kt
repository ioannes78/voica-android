package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ble.BleScanDevice
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

/**
 * Temporary Stage 15.6 true-device surface over the formal application-scoped realtime runtime.
 *
 * This Activity owns UI collectors only. BLE/GATT, reconnect, command fencing, Opus decode,
 * canonical PCM/source and connectedDevice FGS ownership all remain in AppContainer runtimes.
 */
class Stage15RealtimeProbeActivity : ComponentActivity() {
    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var realtimeRuntime: RealtimeAudioRuntime

    private val accumulator = Stage15RealtimeProbeAccumulator()
    private val canonicalAccumulator = Stage15CanonicalProbeAccumulator()
    private val mutableProbeSnapshot = MutableStateFlow(Stage15ProbeSnapshot())
    private val mutableRuntimeSnapshot = MutableStateFlow(RealtimeAudioRuntimeSnapshot())
    private val mutableCanonicalSnapshot = MutableStateFlow(Stage15CanonicalProbeSnapshot())
    private val mutableAction = MutableStateFlow("等待连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as VoicaApplication).container
        deviceRepository = container.deviceRepository
        realtimeRuntime = container.realtimeAudioRuntime

        probeScope.launch(Dispatchers.Default) {
            while (isActive) {
                val event = realtimeRuntime.canonicalSource.receive() ?: break
                canonicalAccumulator.accept(event)
            }
        }

        probeScope.launch {
            while (isActive) {
                publishSnapshots()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage15ProbeScreen(
                    repository = deviceRepository,
                    probeSnapshot = mutableProbeSnapshot,
                    runtimeSnapshot = mutableRuntimeSnapshot,
                    canonicalSnapshot = mutableCanonicalSnapshot,
                    action = mutableAction,
                    onConnect = ::connect,
                    onDisconnect = ::disconnect,
                    onCommand = ::sendRealtime,
                    onStopDrainFault = ::stopAndDisconnectDuringDrain,
                    onReset = ::resetProbe,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::deviceRepository.isInitialized) {
            deviceRepository.setForeground(true)
        }
    }

    override fun onStop() {
        if (::deviceRepository.isInitialized) {
            deviceRepository.setForeground(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        probeScope.cancel()
        super.onDestroy()
    }

    private fun connect(address: String) {
        if (deviceRepository.missingPermissions().isNotEmpty()) {
            mutableAction.value = "缺少蓝牙权限"
            return
        }
        deviceRepository.stopScan()
        deviceRepository.connect(address)
        mutableAction.value = "正在连接 $address"
    }

    private fun disconnect() {
        deviceRepository.disconnect()
        mutableAction.value = "已请求断开连接"
        publishSnapshots()
    }

    private fun sendRealtime(command: RealtimeControlCommand) {
        probeScope.launch {
            val requestedAtNanos = System.nanoTime()
            if (command == RealtimeControlCommand.STOP) {
                mutableAction.value = "STOP 已发起，等待 runtime canonical drain"
            }
            val outcome = when (command) {
                RealtimeControlCommand.START -> realtimeRuntime.start()
                RealtimeControlCommand.PAUSE -> realtimeRuntime.pause()
                RealtimeControlCommand.RESUME -> realtimeRuntime.resume()
                RealtimeControlCommand.STOP -> realtimeRuntime.stop()
            }
            val transport = outcome.transportResult
            accumulator.noteTx(
                label = command.name,
                sequence = transport?.requestSequence ?: -1,
                written = transport?.written == true,
                atNanos = requestedAtNanos,
            )
            publishSnapshots()
            mutableAction.value = buildString {
                append(command.name)
                append(" success=")
                append(outcome.success)
                transport?.requestSequence?.let {
                    append(" seq=")
                    append(it)
                }
                outcome.failure?.let {
                    append(" failure=")
                    append(it)
                }
                if (command == RealtimeControlCommand.STOP && outcome.success) {
                    append(" phase=")
                    append(realtimeRuntime.snapshot().phase)
                }
            }
        }
    }

    /**
     * Stage 15.6-F QA-only deterministic fault injection.
     *
     * It runs the production STOP path, observes the public runtime phase until the successful STOP
     * write has moved the controller into DRAINING, then uses the existing DeviceRepository
     * disconnect path while the canonical quiet drain is still in flight. No production runtime,
     * transport, or media behavior is bypassed or modified.
     */
    private fun stopAndDisconnectDuringDrain() {
        probeScope.launch {
            val requestedAtNanos = System.nanoTime()
            mutableAction.value = "QA：STOP 已发起，等待 DRAINING"

            var stopOutcome: RealtimeCommandOutcome? = null
            val stopJob = launch {
                val outcome = realtimeRuntime.stop()
                stopOutcome = outcome
                val transport = outcome.transportResult
                accumulator.noteTx(
                    label = RealtimeControlCommand.STOP.name,
                    sequence = transport?.requestSequence ?: -1,
                    written = transport?.written == true,
                    atNanos = requestedAtNanos,
                )
            }

            val armDeadlineNanos =
                System.nanoTime() + STOP_DRAIN_ARM_TIMEOUT_MS * NANOS_PER_MILLISECOND
            var drainingObserved = false
            while (stopJob.isActive && System.nanoTime() < armDeadlineNanos) {
                if (realtimeRuntime.snapshot().phase == RealtimeRuntimePhase.DRAINING) {
                    drainingObserved = true
                    break
                }
                delay(STOP_DRAIN_POLL_INTERVAL_MS)
            }

            var disconnectInjected = false
            if (drainingObserved) {
                mutableAction.value =
                    "QA：已命中 DRAINING，${STOP_DRAIN_FAULT_DELAY_MS}ms 后请求断开"
                delay(STOP_DRAIN_FAULT_DELAY_MS)
                if (realtimeRuntime.snapshot().phase == RealtimeRuntimePhase.DRAINING) {
                    deviceRepository.disconnect()
                    disconnectInjected = true
                    mutableAction.value = "QA：已在 DRAINING 中请求断开；等待 runtime 收口"
                }
            }

            stopJob.join()
            publishSnapshots()
            val outcome = stopOutcome
            mutableAction.value = when {
                disconnectInjected ->
                    "QA：STOP drain 断联已注入；STOP success=${outcome?.success == true}"
                drainingObserved ->
                    "QA：观察到 DRAINING，但断开前 drain 已结束；请重试"
                else ->
                    "QA：未观察到 DRAINING；STOP success=${outcome?.success == true} failure=${outcome?.failure ?: "--"}"
            }
        }
    }

    private fun resetProbe() {
        accumulator.reset()
        canonicalAccumulator.reset()
        publishSnapshots()
        mutableAction.value = "已清空 Probe 观察统计；正式 runtime/resource 计数保持进程级累计"
    }

    private fun publishSnapshots() {
        mutableProbeSnapshot.value = accumulator.snapshot()
        mutableRuntimeSnapshot.value = realtimeRuntime.snapshot()
        mutableCanonicalSnapshot.value = canonicalAccumulator.snapshot()
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 500L
        const val STOP_DRAIN_POLL_INTERVAL_MS = 5L
        const val STOP_DRAIN_FAULT_DELAY_MS = 80L
        const val STOP_DRAIN_ARM_TIMEOUT_MS = 750L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

@Composable
private fun Stage15ProbeScreen(
    repository: DeviceRepository,
    probeSnapshot: MutableStateFlow<Stage15ProbeSnapshot>,
    runtimeSnapshot: MutableStateFlow<RealtimeAudioRuntimeSnapshot>,
    canonicalSnapshot: MutableStateFlow<Stage15CanonicalProbeSnapshot>,
    action: MutableStateFlow<String>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onCommand: (RealtimeControlCommand) -> Unit,
    onStopDrainFault: () -> Unit,
    onReset: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        repository.onPermissionsChanged()
        permissionEpoch += 1
    }
    val scan by repository.scanState.collectAsState()
    val connection by repository.connectionState.collectAsState()
    val diagnostics by repository.diagnostics.collectAsState()
    val facts by probeSnapshot.collectAsState()
    val runtime by runtimeSnapshot.collectAsState()
    val canonical by canonicalSnapshot.collectAsState()
    val actionText by action.collectAsState()
    val fgs by DeviceSessionForegroundDiagnostics.state.collectAsState()
    val missingPermissions = remember(permissionEpoch, connection) {
        repository.missingPermissions()
    }
    val rendered = remember(facts, runtime, canonical, diagnostics, fgs) {
        renderStage156Gate(
            facts = facts,
            runtime = runtime,
            canonical = canonical,
            gattSessionId = diagnostics.sessionId,
            negotiatedMtu = diagnostics.negotiatedMtu,
            fgs = fgs,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Voica Stage 15.6 Runtime Probe", style = MaterialTheme.typography.headlineSmall)
                Text("连接：${connectionLabel(connection)}")
                Text("操作：$actionText")
                if (scan.error != null) {
                    Text("扫描错误：${scan.error?.code} ${scan.error?.detail.orEmpty()}")
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            item {
                Button(onClick = { permissionLauncher.launch(missingPermissions.toTypedArray()) }) {
                    Text("授权蓝牙权限")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (scan.isScanning) repository.stopScan() else repository.startScan()
                    },
                ) {
                    Text(if (scan.isScanning) "停止扫描" else "扫描设备")
                }
                OutlinedButton(onClick = onDisconnect) {
                    Text("断开连接")
                }
            }
        }

        items(scan.devices, key = BleScanDevice::address) { device ->
            DeviceRow(device = device, onConnect = onConnect)
        }

        item { HorizontalDivider() }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCommand(RealtimeControlCommand.START) }) { Text("START") }
                Button(onClick = { onCommand(RealtimeControlCommand.PAUSE) }) { Text("PAUSE") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCommand(RealtimeControlCommand.RESUME) }) { Text("RESUME") }
                Button(onClick = { onCommand(RealtimeControlCommand.STOP) }) { Text("STOP") }
            }
        }
        item {
            OutlinedButton(onClick = onStopDrainFault) {
                Text("QA STOP→80ms断开")
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("重置观察统计") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(rendered)) }) {
                    Text("复制 Gate")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = rendered,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleScanDevice,
    onConnect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name ?: "未命名设备")
                Text("${device.address} · RSSI ${device.rssi} · QS668=${device.likelyQs668}")
            }
            Button(onClick = { onConnect(device.address) }) { Text("连接") }
        }
    }
}

private fun renderStage156Gate(
    facts: Stage15ProbeSnapshot,
    runtime: RealtimeAudioRuntimeSnapshot,
    canonical: Stage15CanonicalProbeSnapshot,
    gattSessionId: Long,
    negotiatedMtu: Int?,
    fgs: DeviceSessionForegroundSnapshot,
): String = buildString {
    val command = runtime.command
    val fence = runtime.fence
    val ingress = runtime.ingress
    val pipeline = runtime.pipeline
    val source = runtime.source
    val logicalSessionId = source.activeSessionId ?: canonical.lastSessionId

    appendLine("Stage 15.6 Application Realtime Runtime Fact Gate")
    appendLine(
        "runtime phase=${runtime.phase} transportGeneration=${runtime.transportGeneration ?: "--"} " +
            "logicalSessionId=${logicalSessionId ?: "--"} closed=${runtime.closed}",
    )
    appendLine(
        "runtime owners create=${runtime.transportRuntimeCreates} release=${runtime.transportRuntimeReleases} " +
            "transportClose=${runtime.transportCloseCount} closeWhileActive=${runtime.transportCloseWhileActiveCount}",
    )
    appendLine(
        "runtime decoderCreate=${runtime.decoderCreateCount} decoderRelease=${runtime.decoderReleaseCount} " +
            "decoderBalance=${runtime.decoderCreateCount - runtime.decoderReleaseCount}",
    )
    appendLine(
        "command last=${command.lastCommand ?: "--"} failure=${command.lastFailure ?: "--"} " +
            "start=${command.startSuccess}/${command.startFailure} " +
            "pause=${command.pauseSuccess}/${command.pauseFailure} " +
            "resume=${command.resumeSuccess}/${command.resumeFailure} " +
            "stop=${command.stopSuccess}/${command.stopFailure}",
    )
    appendLine(
        "command ambiguity=${command.commandAmbiguityCount} staleCompletion=${command.staleCommandCompletionCount} " +
            "controllerGeneration=${command.transportGeneration ?: "--"}",
    )
    appendLine(
        "fence gateOpen=${fence.audioGateOpen} active=${fence.activeCommand ?: "--"} " +
            "generation=${fence.activeGeneration ?: "--"} quarantine=${fence.quarantinedGeneration ?: "--"} " +
            "buffer=${fence.bufferedFrames}/${fence.bufferedBytes} peak=${fence.peakBufferedFrames}/${fence.peakBufferedBytes}",
    )
    appendLine(
        "fence prepared=${fence.preparedCount} committed=${fence.committedCount} aborted=${fence.abortedCount} " +
            "ambiguous=${fence.ambiguousCount} overflow=${fence.overflowCount} staleOffer=${fence.staleOfferCount} " +
            "quarantineReject=${fence.quarantinedRejectCount} gatedReject=${fence.gatedRejectCount}",
    )
    appendLine("control tx=${facts.txEvents.joinToString()}")
    appendLine("control timelineOffsetsMs=${facts.timelineOffsetsMs}")
    appendLine(
        "formalIngress generation=${ingress.activeSessionGeneration ?: "--"} acceptedBlocks=${ingress.acceptedBlocks} " +
            "acceptedBytes=${ingress.acceptedBytes} queueBlocks=${ingress.queueBlocks} queueBytes=${ingress.queueBytes} " +
            "peakBlocks=${ingress.peakQueueBlocks} peakBytes=${ingress.peakQueueBytes}",
    )
    appendLine(
        "formalIngress overflow=${ingress.overflowEvents} malformed=${ingress.malformedBlocks} " +
            "generationTransitions=${ingress.generationTransitions} closures=${ingress.sessionClosures} " +
            "droppedBlocks=${ingress.droppedBlocks} droppedBytes=${ingress.droppedBytes}",
    )
    appendLine(
        "canonicalPipeline workerRunning=${pipeline.running} sessions=${pipeline.sessionCount} epochs=${pipeline.epochCount} " +
            "blocks=${pipeline.receivedBlocks} opusPackets=${pipeline.packetizedPackets}",
    )
    appendLine(
        "canonicalPipeline decodeSuccess=${pipeline.decodeSuccess} decodeFailures=${pipeline.decodeFailures} " +
            "invalidOpus=${pipeline.invalidOpusPackets} shapeAnomalies=${pipeline.opusShapeAnomalies} " +
            "zeroSampleFailures=${pipeline.zeroSampleDecodeFailures}",
    )
    appendLine(
        "canonicalPipeline canonicalFrames=${pipeline.canonicalFrames} canonicalSamples=${pipeline.canonicalSamples} " +
            "durationMs=${format3(pipeline.canonicalDurationMs)} nextSampleIndex=${pipeline.nextSampleIndex}",
    )
    appendLine(
        "canonicalPipeline boundaries transport=${pipeline.transportBoundaryCount} control=${pipeline.controlBoundaryCount} " +
            "recovery=${pipeline.decoderRecoveryBoundaryCount} discontinuities=${pipeline.discontinuityCount} " +
            "decoderResets=${pipeline.decoderResetCount} decoderRecreates=${pipeline.decoderRecreateCount}",
    )
    appendLine(
        "canonicalPipeline maxBlockMs=${format3(pipeline.maxBlockProcessingMs)} " +
            "maxArrivalToEmitLagMs=${format3(pipeline.maxArrivalToEmitLagMs)} lastError=${pipeline.lastError ?: "--"}",
    )
    appendLine(
        "canonicalSource sessionActive=${source.sessionActive} activeSession=${source.activeSessionId ?: "--"} " +
            "activeEpoch=${source.activeEpochId ?: "--"} queueFrames=${source.queuedPcmFrames} " +
            "queueSamples=${source.queuedPcmSamples} peakFrames=${source.peakQueuedPcmFrames} peakSamples=${source.peakQueuedPcmSamples}",
    )
    appendLine(
        "canonicalSource offeredFrames=${source.offeredFrames} offeredSamples=${source.offeredSamples} " +
            "sessionStarted=${source.sessionStartedEvents} epochStarted=${source.epochStartedEvents} " +
            "sessionEnded=${source.sessionEndedEvents} consumerDiscontinuities=${source.consumerDiscontinuities}",
    )
    appendLine(
        "canonicalSource overflow=${source.overflowEvents} droppedFrames=${source.droppedFrames} droppedSamples=${source.droppedSamples} " +
            "lastEndReason=${source.lastSessionEndReason ?: "--"} drainTimedOut=${source.lastSessionEndDrainTimedOut}",
    )
    appendLine(
        "canonicalProbe frames=${canonical.frames} samples=${canonical.samples} sessionsSeen=${canonical.sessionsSeen} " +
            "epochsSeen=${canonical.epochsSeen} started=${canonical.sessionStartedEvents} epochStarted=${canonical.epochStartedEvents} " +
            "ended=${canonical.sessionEndedEvents}",
    )
    appendLine(
        "canonicalProbe discontinuities=${canonical.consumerDiscontinuities} drainTimeoutEnds=${canonical.drainTimeoutEnds} " +
            "endMismatch=${canonical.sessionEndMismatchCount} gap=${canonical.gapCount} overlap=${canonical.overlapCount} " +
            "invalidEpochStart=${canonical.invalidEpochStartCount}",
    )
    appendLine(
        "canonicalProbe lastSession=${canonical.lastSessionId ?: "--"} lastEpoch=${canonical.lastEpochId ?: "--"} " +
            "lastEndSampleIndex=${canonical.lastEndSampleIndexExclusive} lastEndReason=${canonical.lastSessionEndReason ?: "--"}",
    )
    appendLine(
        "connectedDeviceFGS generation=${fgs.generation} phase=${fgs.phase} " +
            "label=${fgs.label ?: "--"} failure=${fgs.failureClass ?: "--"}:${fgs.failureMessage ?: "--"}",
    )
    appendLine("gattSession=$gattSessionId mtu=${negotiatedMtu ?: "--"}")
}

private fun connectionLabel(state: DeviceConnectionState): String =
    when (state) {
        DeviceConnectionState.Unavailable -> "Unavailable"
        is DeviceConnectionState.PermissionRequired -> "PermissionRequired ${state.permissions}"
        DeviceConnectionState.BluetoothOff -> "BluetoothOff"
        DeviceConnectionState.Idle -> "Idle"
        DeviceConnectionState.Scanning -> "Scanning"
        is DeviceConnectionState.Connecting -> "Connecting ${state.address}"
        is DeviceConnectionState.LinkConnected -> "LinkConnected ${state.address}"
        is DeviceConnectionState.DiscoveringServices -> "DiscoveringServices ${state.address}"
        is DeviceConnectionState.Subscribing -> "Subscribing ${state.address} ${state.source}"
        is DeviceConnectionState.NegotiatingMtu -> "NegotiatingMtu ${state.address} ${state.requestedMtu}"
        is DeviceConnectionState.Ready -> "Ready ${state.address} mtu=${state.negotiatedMtu}"
        is DeviceConnectionState.Disconnecting -> "Disconnecting ${state.address ?: "--"}"
        is DeviceConnectionState.Disconnected -> "Disconnected ${state.reason} ${state.address ?: "--"}"
        is DeviceConnectionState.ReconnectWaiting -> "ReconnectWaiting attempt=${state.attempt} ${state.delayMs}ms"
        is DeviceConnectionState.Error -> "Error ${state.error.code} ${state.error.detail.orEmpty()}"
    }

private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)