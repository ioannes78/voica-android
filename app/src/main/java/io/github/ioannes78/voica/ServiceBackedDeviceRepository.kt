package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.BleScanState
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.DisconnectReason
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.FileTransferDiagnostics
import io.github.ioannes78.voica.ble.LocalDeleteResult
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.ble.RangeProbeDiagnostics
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RemoteDeleteDiagnostics
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App-layer foreground-service owner for the existing BLE repository.
 *
 * The decorator intentionally leaves the protocol/GATT implementation untouched. Once a user
 * connection attempt becomes a live/recoverable device session, the underlying repository remains
 * in its foreground-capable mode while [DeviceSessionForegroundService] gives Android the explicit
 * `connectedDevice` execution reason. When no session is held, normal Activity foreground/background
 * behavior is delegated unchanged.
 */
class ServiceBackedDeviceRepository(
    context: Context,
    private val delegate: DeviceRepository,
    scope: CoroutineScope,
) : DeviceRepository {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var appForeground = false

    @Volatile
    private var serviceHeld = false

    override val scanState: StateFlow<BleScanState> = delegate.scanState
    override val connectionState: StateFlow<DeviceConnectionState> = delegate.connectionState
    override val deviceInfo: StateFlow<DeviceInfo> = delegate.deviceInfo
    override val recordingState: StateFlow<RecordingDeviceState> = delegate.recordingState
    override val deviceFileListState: StateFlow<DeviceFileListState> = delegate.deviceFileListState
    override val fileOperationState: StateFlow<FileOperationState> = delegate.fileOperationState
    override val fileTransferDiagnostics: StateFlow<FileTransferDiagnostics> =
        delegate.fileTransferDiagnostics
    override val remoteDeleteDiagnostics: StateFlow<RemoteDeleteDiagnostics> =
        delegate.remoteDeleteDiagnostics
    override val rangeProbeDiagnostics: StateFlow<RangeProbeDiagnostics> =
        delegate.rangeProbeDiagnostics
    override val localRecordings: StateFlow<List<LocalRecordingArtifact>> = delegate.localRecordings
    override val diagnostics: StateFlow<BleDiagnostics> = delegate.diagnostics

    init {
        scope.launch {
            combine(
                delegate.connectionState,
                delegate.recordingState,
                delegate.fileOperationState,
            ) { connection, recording, operation ->
                DeviceSessionForegroundDecision(
                    keep = shouldKeepDeviceSession(connection),
                    label = deviceSessionLabel(connection, recording, operation),
                )
            }.collect(::applyDecision)
        }
    }

    override fun missingPermissions(): Set<String> = delegate.missingPermissions()

    override fun onPermissionsChanged() {
        delegate.onPermissionsChanged()
    }

    override fun startScan() {
        delegate.startScan()
    }

    override fun stopScan() {
        delegate.stopScan()
    }

    override fun connect(address: String) {
        // Start the explicit Android execution owner while the initiating Activity is still visible.
        holdService("正在连接录音卡")
        delegate.setForeground(true)
        delegate.connect(address)
    }

    override fun disconnect() {
        delegate.disconnect()
        releaseService()
        if (!appForeground) delegate.setForeground(false)
    }

    override fun setForeground(foreground: Boolean) {
        appForeground = foreground
        if (foreground) {
            delegate.setForeground(true)
        } else if (!serviceHeld) {
            delegate.setForeground(false)
        }
        // If a connected-device FGS lease is active, deliberately do not propagate false: the
        // repository may continue GATT callbacks, recording polling, reconnect and active transfer.
    }

    override suspend fun refreshDeviceInfo() = delegate.refreshDeviceInfo()

    override suspend fun syncTime(): Boolean = delegate.syncTime()

    override suspend fun startRecording() {
        holdService("正在开始录音")
        delegate.setForeground(true)
        delegate.startRecording()
    }

    override suspend fun pauseRecording() = delegate.pauseRecording()

    override suspend fun resumeRecording() {
        holdService("正在继续录音")
        delegate.setForeground(true)
        delegate.resumeRecording()
    }

    override suspend fun saveRecording() = delegate.saveRecording()

    override suspend fun syncRecordingState() = delegate.syncRecordingState()

    override suspend fun setRecordingGain(gain: RecordingGain) = delegate.setRecordingGain(gain)

    override suspend fun refreshDeviceFiles() = delegate.refreshDeviceFiles()

    override suspend fun downloadDeviceFile(
        file: RemoteDeviceFile,
        format: DeviceAudioFormat,
    ) {
        holdService("正在下载录音文件")
        delegate.setForeground(true)
        delegate.downloadDeviceFile(file, format)
    }

    override suspend fun cancelDeviceFileDownload() = delegate.cancelDeviceFileDownload()

    override suspend fun deleteRemoteRecording(file: RemoteDeviceFile) =
        delegate.deleteRemoteRecording(file)

    override suspend fun runRangeProbe(file: RemoteDeviceFile) = delegate.runRangeProbe(file)

    override suspend fun deleteLocalRecording(localId: String): LocalDeleteResult =
        delegate.deleteLocalRecording(localId)

    private fun applyDecision(decision: DeviceSessionForegroundDecision) {
        if (decision.keep) {
            holdService(decision.label)
            // A session can become Ready after the Activity has already stopped. Keep the delegate
            // in its active execution mode once Android has an explicit connectedDevice FGS reason.
            delegate.setForeground(true)
        } else {
            releaseService()
            if (!appForeground) delegate.setForeground(false)
        }
    }

    private fun holdService(label: String) {
        synchronized(lock) {
            serviceHeld = true
            DeviceSessionForegroundService.acquire(appContext, label)
        }
    }

    private fun releaseService() {
        synchronized(lock) {
            if (!serviceHeld) return
            serviceHeld = false
            DeviceSessionForegroundService.release(appContext)
        }
    }

    private fun shouldKeepDeviceSession(state: DeviceConnectionState): Boolean =
        when (state) {
            is DeviceConnectionState.Connecting,
            is DeviceConnectionState.LinkConnected,
            is DeviceConnectionState.DiscoveringServices,
            is DeviceConnectionState.Subscribing,
            is DeviceConnectionState.NegotiatingMtu,
            is DeviceConnectionState.Ready,
            is DeviceConnectionState.ReconnectWaiting,
            is DeviceConnectionState.Disconnecting,
            -> true

            is DeviceConnectionState.Disconnected ->
                state.reason == DisconnectReason.REMOTE

            is DeviceConnectionState.Error -> state.error.recoverable

            DeviceConnectionState.Unavailable,
            is DeviceConnectionState.PermissionRequired,
            DeviceConnectionState.BluetoothOff,
            DeviceConnectionState.Idle,
            DeviceConnectionState.Scanning,
            -> false
        }

    private fun deviceSessionLabel(
        connection: DeviceConnectionState,
        recording: RecordingDeviceState,
        operation: FileOperationState,
    ): String {
        val activeOperation = operation as? FileOperationState.Active
        if (activeOperation?.operation == DeviceFileOperationType.DOWNLOAD) {
            val progress = activeOperation.progress
            if (progress != null && progress.expectedBytes > 0L) {
                val percent = progress.receivedBytes * 100L / progress.expectedBytes
                return "正在下载录音文件 · ${percent.coerceIn(0L, 100L)}%"
            }
            return "正在下载录音文件"
        }

        return when (recording.status) {
            RecordingStatus.Recording -> "录音卡正在录音"
            RecordingStatus.Paused -> "录音卡录音已暂停"
            else ->
                when (connection) {
                    is DeviceConnectionState.ReconnectWaiting ->
                        "正在重连录音卡 · 第 ${connection.attempt} 次"
                    is DeviceConnectionState.Connecting,
                    is DeviceConnectionState.LinkConnected,
                    is DeviceConnectionState.DiscoveringServices,
                    is DeviceConnectionState.Subscribing,
                    is DeviceConnectionState.NegotiatingMtu,
                    -> "正在连接录音卡"
                    is DeviceConnectionState.Ready -> "录音卡已连接"
                    else -> "保持录音卡连接"
                }
        }
    }

    private data class DeviceSessionForegroundDecision(
        val keep: Boolean,
        val label: String,
    )
}
