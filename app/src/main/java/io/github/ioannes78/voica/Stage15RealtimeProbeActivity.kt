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
import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.ble.AndroidBleScanner
import io.github.ioannes78.voica.ble.AndroidDeviceSession
import io.github.ioannes78.voica.ble.BlePermissionPolicy
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioIngressSnapshot
import io.github.ioannes78.voica.ble.RealtimeOpusPacket
import io.github.ioannes78.voica.ble.RealtimeOpusPacketizer
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.opus.NativeOpusBackend
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import io.github.ioannes78.voica.protocol.RealtimeProtocol
import java.util.concurrent.atomic.AtomicInteger
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
 * Starting with Stage 15.1/15.2, TYPE=1/CMD=1 audio reaches this probe through the production
 * dedicated RealtimeAudioIngress. The generic notification observer is retained only for low-rate
 * non-audio TYPE=1 responses such as START filename and pause/resume state. Final Freeze must remove
 * this second launcher or fold the diagnostics into the normal product surface.
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
    private val accumulator = Stage15RealtimeProbeAccumulator()
    private val realtimeIngress = RealtimeAudioIngress()
    private val decodeResetEpoch = AtomicInteger(0)
    private val mutableSnapshot = MutableStateFlow(Stage15ProbeSnapshot())
    private val mutableIngressSnapshot = MutableStateFlow(RealtimeAudioIngressSnapshot())
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
            val backend = NativeOpusBackend()
            val decoder = runCatching {
                backend.create(
                    sampleRateHz = SPLIT_DECODE_SAMPLE_RATE_HZ,
                    channelCount = SPLIT_DECODE_CHANNEL_COUNT,
                )
            }.getOrNull()
            var appliedResetEpoch = -1
            try {
                while (isActive) {
                    val block = realtimeIngress.receive() ?: break
                    val requestedResetEpoch = decodeResetEpoch.get()
                    if (block.discontinuityBefore || requestedResetEpoch != appliedResetEpoch) {
                        decoder?.let { runCatching { it.reset() } }
                        appliedResetEpoch = requestedResetEpoch
                    }

                    val fullBodyInfo = inspectSafely(backend, block.encodedBytes)
                    val packets = RealtimeOpusPacketizer.split(block)
                    val split40Facts = buildSplit40Facts(
                        packets = packets,
                        backend = backend,
                        decoder = decoder,
                    )
                    val syntheticNotification = RoutedNotification(
                        source = block.source,
                        frame = ProtocolFrame(
                            sequence = block.protocolSequence,
                            data = byteArrayOf(
                                ProtocolConstants.Type.REALTIME.toByte(),
                                ProtocolConstants.Realtime.AUDIO_DATA.toByte(),
                            ) + block.encodedBytes,
                        ),
                    )
                    accumulator.accept(
                        notification = syntheticNotification,
                        receivedAtNanos = block.receivedAtNanos,
                        opusInfo = fullBodyInfo,
                        split40Facts = split40Facts,
                    )
                }
            } finally {
                decoder?.let { runCatching { it.close() } }
            }
        }

        probeScope.launch {
            while (isActive) {
                mutableSnapshot.value = accumulator.snapshot()
                mutableIngressSnapshot.value = realtimeIngress.snapshot()
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
                    action = mutableAction,
                    onConnect = ::connect,
                    onDisconnect = session::disconnect,
                    onStartRealtime = ::startRealtime,
                    onPauseRealtime = { sendRealtime("PAUSE", RealtimeProtocol::buildPause) },
                    onResumeRealtime = { sendRealtime("RESUME", RealtimeProtocol::buildResume) },
                    onStopRealtime = { sendRealtime("STOP", RealtimeProtocol::buildStop) },
                    onReset = ::resetProbe,
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { scanner.stop() }
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
        realtimeIngress.reset()
        decodeResetEpoch.incrementAndGet()
        if (!session.registerRealtimeAudioConsumer(realtimeIngress)) {
            mutableAction.value = "Realtime ingress 注册失败"
            return
        }
        mutableSnapshot.value = accumulator.snapshot()
        mutableIngressSnapshot.value = realtimeIngress.snapshot()
        sendRealtime("START", RealtimeProtocol::buildStart)
    }

    private fun resetProbe() {
        accumulator.reset()
        realtimeIngress.reset()
        decodeResetEpoch.incrementAndGet()
        mutableSnapshot.value = accumulator.snapshot()
        mutableIngressSnapshot.value = realtimeIngress.snapshot()
        mutableAction.value = "统计已清空"
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
            mutableSnapshot.value = accumulator.snapshot()
            mutableIngressSnapshot.value = realtimeIngress.snapshot()
            mutableAction.value = "$label seq=${result.requestSequence} written=${result.written}"
        }
    }

    private fun buildSplit40Facts(
        packets: List<RealtimeOpusPacket>,
        backend: NativeOpusBackend,
        decoder: OpusDecoder?,
    ): Stage15Split40Facts {
        if (packets.size != RealtimeAudioIngress.OPUS_PACKETS_PER_BLOCK) {
            return Stage15Split40Facts(eligible160ByteBody = false)
        }
        val packetFacts = packets.map { packet ->
            val info = inspectSafely(backend, packet.encodedBytes)
            if (!info.valid) {
                Stage15Split40PacketFact(
                    slot = packet.packetIndexInBlock,
                    opusInfo = info,
                )
            } else if (decoder == null) {
                Stage15Split40PacketFact(
                    slot = packet.packetIndexInBlock,
                    opusInfo = info,
                    decodeErrorCode = PROBE_DECODER_UNAVAILABLE,
                )
            } else {
                runCatching { decoder.decode(packet.encodedBytes) }
                    .fold(
                        onSuccess = { pcm ->
                            Stage15Split40PacketFact(
                                slot = packet.packetIndexInBlock,
                                opusInfo = info,
                                decodedSamples16k = pcm.size,
                            )
                        },
                        onFailure = {
                            Stage15Split40PacketFact(
                                slot = packet.packetIndexInBlock,
                                opusInfo = info,
                                decodeErrorCode = PROBE_DECODE_EXCEPTION,
                            )
                        },
                    )
            }
        }
        return Stage15Split40Facts(
            eligible160ByteBody = true,
            packets = packetFacts,
        )
    }

    private fun inspectSafely(
        backend: NativeOpusBackend,
        packet: ByteArray,
    ): OpusPacketInfo = runCatching { backend.inspect(packet) }
        .getOrElse { OpusPacketInfo(valid = false, errorCode = PROBE_INSPECT_EXCEPTION) }

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
        const val PROBE_INSPECT_EXCEPTION = -2001
        const val PROBE_DECODE_EXCEPTION = -2002
        const val PROBE_DECODER_UNAVAILABLE = -2003
        const val SPLIT_DECODE_SAMPLE_RATE_HZ = 16_000
        const val SPLIT_DECODE_CHANNEL_COUNT = 1
    }
}

@Composable
private fun Stage15ProbeScreen(
    scanner: AndroidBleScanner,
    session: AndroidDeviceSession,
    snapshot: MutableStateFlow<Stage15ProbeSnapshot>,
    ingressSnapshot: MutableStateFlow<RealtimeAudioIngressSnapshot>,
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
    val actionText by action.collectAsState()
    val missingPermissions = remember(permissionEpoch) {
        BlePermissionPolicy.missingPermissions(activity)
    }
    val observerOverflow = remember(diagnostics.logs) {
        diagnostics.logs.count { it.contains("Notification observer overflow") }
    }
    val rendered = remember(facts, ingress, diagnostics.notifications, observerOverflow) {
        facts.render(observerOverflow) +
            "\nformalIngress acceptedBlocks=${ingress.acceptedBlocks} acceptedBytes=${ingress.acceptedBytes}" +
            " queueBlocks=${ingress.queueBlocks} queueBytes=${ingress.queueBytes}" +
            " peakBlocks=${ingress.peakQueueBlocks} peakBytes=${ingress.peakQueueBytes}" +
            "\nformalIngress overflowEvents=${ingress.overflowEvents} malformedBlocks=${ingress.malformedBlocks}" +
            " droppedBlocks=${ingress.droppedBlocks} droppedBytes=${ingress.droppedBytes}" +
            " continuityId=${ingress.continuityId}" +
            "\nrouter AE22=${diagnostics.notifications.ae22Frames} AE23=${diagnostics.notifications.ae23Frames}" +
            "\nrouter CRC AE22=${diagnostics.notifications.ae22CrcErrors} AE23=${diagnostics.notifications.ae23CrcErrors}" +
            "\nsession=${diagnostics.sessionId} mtu=${diagnostics.negotiatedMtu}"
    }
    val clipboard = LocalClipboardManager.current
    val ready = connection is DeviceConnectionState.Ready

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Voica Stage 15.1/15.2 Realtime Ingress Probe", style = MaterialTheme.typography.headlineSmall)
            Text(
                "build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Realtime CMD=1 已走正式 dedicated ingress；generic observer 只保留低频非音频 TYPE=1 响应。" +
                    "正式 ingress 上限为 12×160B / 2048B，overflow 时丢弃 stale backlog 并标记 discontinuity。",
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
                            onClick = {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            },
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
