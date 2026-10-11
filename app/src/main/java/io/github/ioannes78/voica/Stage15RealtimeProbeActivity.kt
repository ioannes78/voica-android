package io.github.ioannes78.voica

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSourceSnapshot
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.ble.AndroidBleScanner
import io.github.ioannes78.voica.ble.AndroidDeviceSession
import io.github.ioannes78.voica.ble.BlePermissionPolicy
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioIngressSnapshot
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.opus.NativeOpusBackend
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.RealtimeProtocol
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Temporary Stage 15 true-device surface.
 *
 * TYPE=1/CMD=1 audio reaches this probe through the production dedicated ingress, formal canonical
 * decoder/timeline pipeline, and Stage 15.5 bounded canonical consumer seam. The generic observer is
 * retained solely for low-rate TYPE=1 control responses. Final Freeze must remove this second
 * launcher or fold it into diagnostics.
 */
class Stage15RealtimeProbeActivity : ComponentActivity() {
    private data class QueuedProbeEvent(
        val notification: RoutedNotification,
        val receivedAtNanos: Long,
    )

    private val probeJob = SupervisorJob()
    private val probeScope = CoroutineScope(probeJob + Dispatchers.Main.immediate)
    private lateinit var scanner: AndroidBleScanner
    private lateinit var session: AndroidDeviceSession
    private lateinit var productRepository: DeviceRepository
    private lateinit var canonicalPipeline: RealtimeCanonicalAudioPipeline

    private val accumulator = Stage15RealtimeProbeAccumulator()
    private val canonicalAccumulator = Stage15CanonicalProbeAccumulator()
    private val realtimeIngress = RealtimeAudioIngress()
    private val canonicalSource = RealtimeCanonicalAudioSource()
    private val mutableSnapshot = MutableStateFlow(Stage15ProbeSnapshot())
    private val mutableIngressSnapshot = MutableStateFlow(RealtimeAudioIngressSnapshot())
    private val mutablePipelineSnapshot = MutableStateFlow(RealtimeCanonicalAudioPipelineSnapshot())
    private val mutableSourceSnapshot = MutableStateFlow(RealtimeCanonicalAudioSourceSnapshot())
    private val mutableCanonicalSnapshot = MutableStateFlow(Stage15CanonicalProbeSnapshot())
    private val mutableAction = MutableStateFlow("等待连接")
    private val probeQueue = Channel<QueuedProbeEvent>(capacity = 1_024)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        productRepository = (application as VoicaApplication).container.deviceRepository
        productRepository.setForeground(false)
        if (productRepository.connectionState.value.hasActiveTransport()) {
            productRepository.disconnect()
        }

        scanner = AndroidBleScanner(applicationContext, probeScope)
        session = AndroidDeviceSession(applicationContext, probeScope)
        check(session.registerRealtimeAudioConsumer(realtimeIngress)) {
            "Stage15 probe failed to register realtime ingress"
        }

        val backend = NativeOpusBackend()
        canonicalPipeline = RealtimeCanonicalAudioPipeline(
            ingress = realtimeIngress,
            packetInspector = backend,
            decoderFactory = backend,
            onFrame = { frame -> canonicalSource.offer(frame) },
        )

        probeScope.launch {
            session.notifications.collect { notification ->
                if (notification.frame.type != ProtocolConstants.Type.REALTIME) return@collect
                if (notification.frame.command == ProtocolConstants.Realtime.AUDIO_DATA) return@collect
                val accepted = probeQueue.trySend(
                    QueuedProbeEvent(
                        notification = notification,
                        receivedAtNanos = System.nanoTime(),
                    ),
                ).isSuccess
                if (!accepted) accumulator.noteQueueOverflow()
            }
        }

        probeScope.launch(Dispatchers.Default) {
            for (queued in probeQueue) {
                accumulator.accept(
                    notification = queued.notification,
                    receivedAtNanos = queued.receivedAtNanos,
                    opusInfo = null,
                    split40Facts = null,
                )
            }
        }

        probeScope.launch(Dispatchers.Default) {
            canonicalPipeline.run()
        }

        probeScope.launch(Dispatchers.Default) {
            while (isActive) {
                val event = canonicalSource.receive() ?: break
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
                    scanner = scanner,
                    session = session,
                    snapshot = mutableSnapshot,
                    ingressSnapshot = mutableIngressSnapshot,
                    pipelineSnapshot = mutablePipelineSnapshot,
                    sourceSnapshot = mutableSourceSnapshot,
                    canonicalSnapshot = mutableCanonicalSnapshot,
                    action = mutableAction,
                    onConnect = ::connect,
                    onDisconnect = session::disconnect,
                    onStartRealtime = ::startRealtime,
                    onPauseRealtime = { sendRealtime("PAUSE", RealtimeProtocol::buildPause) },
                    onResumeRealtime = {
                        canonicalPipeline.requestControlBoundary()
                        sendRealtime("RESUME", RealtimeProtocol::buildResume)
                    },
                    onStopRealtime = { sendRealtime("STOP", RealtimeProtocol::buildStop) },
                    onReset = ::resetProbe,
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { scanner.stop() }
        runCatching { canonicalPipeline.close() }
        runCatching { canonicalSource.close() }
        runCatching { session.close() }
        runCatching { realtimeIngress.close() }
        probeQueue.close()
        probeScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        if (BlePermissionPolicy.missingPermissions(this).isNotEmpty()) {
            mutableAction.value = "缺少蓝牙权限"
            return
        }
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            mutableAction.value = "Bluetooth adapter 不可用"
            return
        }
        scanner.stop()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            mutableAction.value = "无法取得设备 $address"
            return
        }
        if (!session.registerRealtimeAudioConsumer(realtimeIngress)) {
            mutableAction.value = "Realtime ingress 注册失败"
            return
        }
        mutableAction.value = if (session.connect(device)) "正在连接 $address" else "连接请求未启动"
    }

    private fun startRealtime() {
        accumulator.reset()
        canonicalAccumulator.reset()
        realtimeIngress.reset()
        canonicalPipeline.resetDiagnostics()
        canonicalSource.resetDiagnostics()
        canonicalPipeline.beginRealtimeSession()
        if (!session.registerRealtimeAudioConsumer(realtimeIngress)) {
            mutableAction.value = "Realtime ingress 注册失败"
            return
        }
        publishSnapshots()
        sendRealtime("START", RealtimeProtocol::buildStart)
    }

    private fun resetProbe() {
        accumulator.reset()
        canonicalAccumulator.reset()
        realtimeIngress.reset()
        canonicalPipeline.resetDiagnostics()
        canonicalSource.resetDiagnostics()
        canonicalPipeline.requestControlBoundary()
        publishSnapshots()
        mutableAction.value = "统计已清空；下一音频帧将开启新 epoch"
    }

    private fun publishSnapshots() {
        mutableSnapshot.value = accumulator.snapshot()
        mutableIngressSnapshot.value = realtimeIngress.snapshot()
        mutablePipelineSnapshot.value = canonicalPipeline.snapshot()
        mutableSourceSnapshot.value = canonicalSource.snapshot()
        mutableCanonicalSnapshot.value = canonicalAccumulator.snapshot()
    }

    private fun sendRealtime(
        label: String,
        builder: (Int) -> ByteArray,
    ) {
        probeScope.launch {
            val requestedAtNanos = System.nanoTime()
            val result = session.sendFileTransferRequest(builder)
            accumulator.noteTx(
                label = label,
                sequence = result.requestSequence,
                written = result.written,
                atNanos = requestedAtNanos,
            )
            publishSnapshots()

            if (label == "STOP" && result.written) {
                mutableAction.value = "STOP 已写入，等待 canonical 尾帧 drain"
                val endResult = canonicalSource.endActiveSessionAfterQuiet(
                    reason = RealtimeCanonicalSessionEndReason.STOP,
                )
                publishSnapshots()
                mutableAction.value = "STOP seq=${result.requestSequence} written=true drain=$endResult"
            } else {
                mutableAction.value = "$label seq=${result.requestSequence} written=${result.written}"
            }
        }
    }

    private fun DeviceConnectionState.hasActiveTransport(): Boolean =
        when (this) {
            is DeviceConnectionState.Connecting,
            is DeviceConnectionState.LinkConnected,
            is DeviceConnectionState.DiscoveringServices,
            is DeviceConnectionState.Subscribing,
            is DeviceConnectionState.NegotiatingMtu,
            is DeviceConnectionState.Ready,
            is DeviceConnectionState.Disconnecting,
            -> true

            else -> false
        }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 500L
    }
}

@Composable
private fun Stage15ProbeScreen(
    scanner: AndroidBleScanner,
    session: AndroidDeviceSession,
    snapshot: MutableStateFlow<Stage15ProbeSnapshot>,
    ingressSnapshot: MutableStateFlow<RealtimeAudioIngressSnapshot>,
    pipelineSnapshot: MutableStateFlow<RealtimeCanonicalAudioPipelineSnapshot>,
    sourceSnapshot: MutableStateFlow<RealtimeCanonicalAudioSourceSnapshot>,
    canonicalSnapshot: MutableStateFlow<Stage15CanonicalProbeSnapshot>,
    action: MutableStateFlow<String>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartRealtime: () -> Unit,
    onPauseRealtime: () -> Unit,
    onResumeRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    onReset: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionEpoch += 1
    }
    val scan by scanner.state.collectAsState()
    val connection by session.state.collectAsState()
    val diagnostics by session.diagnostics.collectAsState()
    val facts by snapshot.collectAsState()
    val ingress by ingressSnapshot.collectAsState()
    val pipeline by pipelineSnapshot.collectAsState()
    val source by sourceSnapshot.collectAsState()
    val canonical by canonicalSnapshot.collectAsState()
    val actionText by action.collectAsState()
    val missingPermissions = remember(permissionEpoch) {
        BlePermissionPolicy.missingPermissions(activity)
    }
    val observerOverflow = remember(diagnostics.logs) {
        diagnostics.logs.count { it.contains("Notification observer overflow") }
    }
    val rendered = remember(
        facts,
        ingress,
        pipeline,
        source,
        canonical,
        diagnostics.notifications,
        diagnostics.sessionId,
        diagnostics.negotiatedMtu,
        observerOverflow,
    ) {
        buildString {
            appendLine("Stage 15.5 Realtime Canonical Consumer Fact Gate")
            appendLine(
                "control wallElapsedMs=${facts.wallElapsedMs ?: "--"} " +
                    "deviceReportedFilename=${facts.deviceReportedFilename ?: "--"}",
            )
            appendLine("control timelineOffsetsMs=${facts.timelineOffsetsMs}")
            appendLine("control tx=${facts.txEvents.joinToString()}")
            appendLine("control nonAudioFrames:")
            facts.nonAudioFrames.forEach { appendLine("  $it") }
            appendLine("probeQueueOverflow=${facts.queueOverflowCount} genericObserverOverflow=$observerOverflow")
            appendLine(
                "formalIngress acceptedBlocks=${ingress.acceptedBlocks} acceptedBytes=${ingress.acceptedBytes} " +
                    "queueBlocks=${ingress.queueBlocks} queueBytes=${ingress.queueBytes} " +
                    "peakBlocks=${ingress.peakQueueBlocks} peakBytes=${ingress.peakQueueBytes}",
            )
            appendLine(
                "formalIngress overflowEvents=${ingress.overflowEvents} malformedBlocks=${ingress.malformedBlocks} " +
                    "droppedBlocks=${ingress.droppedBlocks} droppedBytes=${ingress.droppedBytes} " +
                    "continuityId=${ingress.continuityId}",
            )
            appendLine(
                "canonicalPipeline workerRunning=${pipeline.running} sessions=${pipeline.sessionCount} epochs=${pipeline.epochCount} " +
                    "blocks=${pipeline.receivedBlocks} opusPackets=${pipeline.packetizedPackets}",
            )
            appendLine(
                "canonicalPipeline invalidOpus=${pipeline.invalidOpusPackets} shapeAnomalies=${pipeline.opusShapeAnomalies} " +
                    "decodeSuccess=${pipeline.decodeSuccess} decodeFailures=${pipeline.decodeFailures} " +
                    "zeroSampleFailures=${pipeline.zeroSampleDecodeFailures}",
            )
            appendLine(
                "canonicalPipeline decodedSampleHistogram=${pipeline.decodedSampleHistogram.toSortedMap()} " +
                    "unexpectedDecodedSampleCount=${pipeline.unexpectedDecodedSampleCount}",
            )
            appendLine(
                "canonicalPipeline canonicalFrames=${pipeline.canonicalFrames} canonicalSamples=${pipeline.canonicalSamples} " +
                    "canonicalDurationMs=${format3(pipeline.canonicalDurationMs)}",
            )
            appendLine(
                "canonicalPipeline discontinuities=${pipeline.discontinuityCount} transportBoundaries=${pipeline.transportBoundaryCount} " +
                    "controlBoundaries=${pipeline.controlBoundaryCount} decoderRecoveryBoundaries=${pipeline.decoderRecoveryBoundaryCount}",
            )
            appendLine(
                "canonicalPipeline decoderResets=${pipeline.decoderResetCount} decoderRecreates=${pipeline.decoderRecreateCount} " +
                    "decoderCreateFailures=${pipeline.decoderCreateFailures} observerFailures=${pipeline.frameObserverFailures}",
            )
            appendLine(
                "canonicalPipeline activeSession=${pipeline.activeSessionId ?: "--"} activeEpoch=${pipeline.activeEpochId ?: "--"} " +
                    "nextSampleIndex=${pipeline.nextSampleIndex} maxBlockProcessingMs=${format3(pipeline.maxBlockProcessingMs)} " +
                    "maxArrivalToEmitLagMs=${format3(pipeline.maxArrivalToEmitLagMs)} lastError=${pipeline.lastError ?: "--"}",
            )
            appendLine(
                "canonicalSource sessionActive=${source.sessionActive} activeSession=${source.activeSessionId ?: "--"} " +
                    "activeEpoch=${source.activeEpochId ?: "--"} queueFrames=${source.queuedPcmFrames} " +
                    "queueSamples=${source.queuedPcmSamples} peakFrames=${source.peakQueuedPcmFrames} " +
                    "peakSamples=${source.peakQueuedPcmSamples}",
            )
            appendLine(
                "canonicalSource offeredFrames=${source.offeredFrames} offeredSamples=${source.offeredSamples} " +
                    "sessionStarted=${source.sessionStartedEvents} epochStarted=${source.epochStartedEvents} " +
                    "sessionEnded=${source.sessionEndedEvents} consumerDiscontinuities=${source.consumerDiscontinuities}",
            )
            appendLine(
                "canonicalSource overflowEvents=${source.overflowEvents} droppedFrames=${source.droppedFrames} " +
                    "droppedSamples=${source.droppedSamples} rejectedAfterClose=${source.rejectedAfterClose} " +
                    "lastEndReason=${source.lastSessionEndReason ?: "--"} drainTimedOut=${source.lastSessionEndDrainTimedOut}",
            )
            appendLine(
                "canonicalProbe frames=${canonical.frames} samples=${canonical.samples} sessionsSeen=${canonical.sessionsSeen} " +
                    "epochsSeen=${canonical.epochsSeen} sessionStartedEvents=${canonical.sessionStartedEvents} " +
                    "epochStartedEvents=${canonical.epochStartedEvents} sessionEndedEvents=${canonical.sessionEndedEvents}",
            )
            appendLine(
                "canonicalProbe consumerDiscontinuities=${canonical.consumerDiscontinuities} drainTimeoutEnds=${canonical.drainTimeoutEnds} " +
                    "sessionEndMismatchCount=${canonical.sessionEndMismatchCount} gapCount=${canonical.gapCount} " +
                    "overlapCount=${canonical.overlapCount} invalidEpochStartCount=${canonical.invalidEpochStartCount}",
            )
            appendLine(
                "canonicalProbe lastSession=${canonical.lastSessionId ?: "--"} lastEpoch=${canonical.lastEpochId ?: "--"} " +
                    "lastEndSampleIndex=${canonical.lastEndSampleIndexExclusive} " +
                    "lastEndReason=${canonical.lastSessionEndReason ?: "--"}",
            )
            appendLine(
                "router publishedSnapshot(batched64) AE22=${diagnostics.notifications.ae22Frames} " +
                    "AE23=${diagnostics.notifications.ae23Frames}",
            )
            appendLine(
                "router CRC AE22=${diagnostics.notifications.ae22CrcErrors} " +
                    "AE23=${diagnostics.notifications.ae23CrcErrors}",
            )
            appendLine("gattSession=${diagnostics.sessionId} mtu=${diagnostics.negotiatedMtu}")
        }
    }
    val clipboard = LocalClipboardManager.current
    val ready = connection is DeviceConnectionState.Ready

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Voica Stage 15.5 Canonical Consumer Probe", style = MaterialTheme.typography.headlineSmall)
            Text(
                "build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "CMD=1 → dedicated ingress → 4×40B Opus → canonical PCM → bounded consumer seam。" +
                    "PCM queue 上限 32 帧 / 10240 samples；STOP 成功写入后等待 250ms canonical quiet，再发 SessionEnded。" +
                    "workerRunning 仅表示 decoder worker 存活，sessionActive 才表示下游 session 生命周期。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("连接")
                    Text("state=$connection")
                    Text("action=$actionText")
                    if (missingPermissions.isNotEmpty()) {
                        Button(
                            onClick = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                        ) {
                            Text("授予蓝牙权限")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = missingPermissions.isEmpty(),
                            onClick = scanner::start,
                        ) {
                            Text(if (scan.isScanning) "扫描中" else "扫描设备")
                        }
                        OutlinedButton(onClick = onDisconnect) { Text("断开") }
                    }
                    if (scan.error != null) Text("scanError=${scan.error}")
                }
            }
        }

        if (scan.devices.isNotEmpty()) {
            item { Text("发现设备", style = MaterialTheme.typography.titleMedium) }
            items(scan.devices, key = BleScanDevice::address) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name ?: "未命名设备")
                            Text("${device.address}  RSSI=${device.rssi}  AE20=${device.advertisesAe20}")
                        }
                        Button(onClick = { onConnect(device.address) }) { Text("连接") }
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
                    Text("TYPE=1 控制", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = ready, onClick = onStartRealtime) { Text("开始") }
                        Button(enabled = ready, onClick = onPauseRealtime) { Text("暂停") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = ready, onClick = onResumeRealtime) { Text("继续") }
                        Button(enabled = ready, onClick = onStopRealtime) { Text("停止") }
                    }
                    OutlinedButton(onClick = onReset) { Text("清空统计") }
                }
            }
        }

        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Fact Gate 结果", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(rendered)) },
                ) {
                    Text("复制")
                }
            }
            Text(rendered, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
