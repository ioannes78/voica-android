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
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.ble.AndroidBleScanner
import io.github.ioannes78.voica.ble.AndroidDeviceSession
import io.github.ioannes78.voica.ble.BlePermissionPolicy
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.opus.NativeOpusBackend
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.RealtimeProtocol
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
 * Stage 15.0B temporary true-device cadence/silence fact-gate surface.
 *
 * This activity intentionally uses the existing generic notification observer only to discover
 * TYPE=1 packet facts before the production dedicated realtime ingress is implemented. It must
 * not be treated as the Stage 15 realtime audio authority and must be removed or folded into the
 * normal diagnostics surface before Stage 15 Final Freeze.
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
    private val mutableSnapshot = MutableStateFlow(Stage15ProbeSnapshot())
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

        probeScope.launch {
            session.notifications.collect { notification ->
                if (notification.frame.type != ProtocolConstants.Type.REALTIME) return@collect
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
            val inspector = NativeOpusBackend()
            for (queued in probeQueue) {
                val frame = queued.notification.frame
                val info = if (frame.command == ProtocolConstants.Realtime.AUDIO_DATA) {
                    runCatching { inspector.inspect(frame.body) }
                        .getOrElse { OpusPacketInfo(valid = false, errorCode = PROBE_INSPECT_EXCEPTION) }
                } else {
                    null
                }
                accumulator.accept(
                    notification = queued.notification,
                    receivedAtNanos = queued.receivedAtNanos,
                    opusInfo = info,
                )
            }
        }

        probeScope.launch {
            while (isActive) {
                mutableSnapshot.value = accumulator.snapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        setContent {
            MaterialTheme {
                Stage15ProbeScreen(
                    scanner = scanner,
                    session = session,
                    snapshot = mutableSnapshot,
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
        mutableAction.value = if (session.connect(device)) "正在连接 $address" else "连接请求未启动"
    }

    private fun startRealtime() {
        accumulator.reset()
        mutableSnapshot.value = accumulator.snapshot()
        sendRealtime("START", RealtimeProtocol::buildStart)
    }

    private fun resetProbe() {
        accumulator.reset()
        mutableSnapshot.value = accumulator.snapshot()
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
            mutableAction.value = "$label seq=${result.requestSequence} written=${result.written}"
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
        const val PROBE_INSPECT_EXCEPTION = -2001
    }
}

@Composable
private fun Stage15ProbeScreen(
    scanner: AndroidBleScanner,
    session: AndroidDeviceSession,
    snapshot: MutableStateFlow<Stage15ProbeSnapshot>,
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
    val actionText by action.collectAsState()
    val missingPermissions = remember(permissionEpoch) {
        BlePermissionPolicy.missingPermissions(activity)
    }
    val observerOverflow = remember(diagnostics.logs) {
        diagnostics.logs.count { it.contains("Notification observer overflow") }
    }
    val rendered = remember(facts, diagnostics.notifications, observerOverflow) {
        facts.render(observerOverflow) +
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
            Text("Voica Stage 15.0B Realtime Probe", style = MaterialTheme.typography.headlineSmall)
            Text(
                "build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Cadence/silence fact gate；protocol SEQ 仅作为原始诊断字段，不用于丢包或重复判定。" +
                    "若 genericObserverOverflow 或 probeQueueOverflow > 0，本轮结果不能用于冻结 cadence 语义。",
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
