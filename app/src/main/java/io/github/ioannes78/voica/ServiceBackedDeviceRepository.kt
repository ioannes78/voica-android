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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val scanToConnectSettler: suspend () -> Unit = {
        delay(SCAN_TO_CONNECT_SETTLE_MS)
    },
) : DeviceRepository {
    private val appContext = context.applicationContext
    private val scope = scope
    private val lock = Any()

    @Volatile
    private var appForeground = false

    @Volatile
    private var serviceHeld = false

    @Volatile
    private var userDisconnectedThisProcess = false

    @Volatile
    private var foregroundStartFailedThisSession = false

    private var serviceGeneration: Long? = null
    private var currentDeviceName: String? = null
    private var lastPublishedDeviceName: String? = null
    private var lastPublishedLabel: String? = null
    private var pendingConnectJob: Job? = null
    private var connectGeneration = 0L
    private var scanToConnectTransitionInFlight = false

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
                delegate.deviceInfo,
            ) { connection, recording, operation, info ->
                val resolvedName =
                    info.name?.trim()?.takeIf { it.isNotEmpty() }
                        ?: currentDeviceName
                        ?: FALLBACK_DEVICE_NAME
                DeviceSessionForegroundDecision(
                    keep =
                        !userDisconnectedThisProcess &&
                            shouldKeepDeviceSession(connection),
                    deviceName = resolvedName,
                    label = deviceSessionLabel(connection, recording, operation),
                )
            }.collect(::applyDecision)
        }

        scope.launch {
            DeviceSessionForegroundDiagnostics.state.collect { snapshot ->
                val shouldDropLease =
                    synchronized(lock) {
                        snapshot.phase == DeviceSessionForegroundPhase.FAILED &&
                            snapshot.generation == serviceGeneration
                    }
                if (shouldDropLease) {
                    synchronized(lock) {
                        if (snapshot.generation == serviceGeneration) {
                            serviceHeld = false
                            serviceGeneration = null
                            lastPublishedDeviceName = null
                            lastPublishedLabel = null
                            foregroundStartFailedThisSession = true
                        }
                    }
                    if (!appForeground) {
                        delegate.setForeground(false)
                    }
                }
            }
        }
    }

    override fun missingPermissions(): Set<String> = delegate.missingPermissions()

    override fun onPermissionsChanged() {
        delegate.onPermissionsChanged()
    }

    override fun startScan() {
        cancelPendingExplicitConnect()
        delegate.startScan()
    }

    override fun stopScan() {
        delegate.stopScan()
    }

    override fun connect(address: String) {
        userDisconnectedThisProcess = false
        foregroundStartFailedThisSession = false
        currentDeviceName =
            delegate.scanState.value.devices
                .firstOrNull { it.address == address }
                ?.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val scanWasActive = delegate.scanState.value.isScanning
        if (scanWasActive) {
            delegate.stopScan()
        }

        val generation: Long
        val requiresSettle: Boolean
        val previousJob: Job?
        synchronized(lock) {
            // Advance ownership before cancelling the old job. Its finally block must see a stale
            // generation and therefore cannot clear the settle state inherited by this request.
            connectGeneration += 1L
            generation = connectGeneration
            requiresSettle = scanWasActive || scanToConnectTransitionInFlight
            scanToConnectTransitionInFlight = requiresSettle
            previousJob = pendingConnectJob
            pendingConnectJob = null
        }
        previousJob?.cancel()

        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (requiresSettle) {
                        scanToConnectSettler()
                    }
                    val shouldConnect =
                        synchronized(lock) {
                            generation == connectGeneration &&
                                !userDisconnectedThisProcess
                        }
                    if (shouldConnect) {
                        // Foreground ownership is intentionally not requested from the row-click
                        // call stack. When the delegate publishes Connecting, applyDecision() obtains
                        // the connectedDevice FGS lease for that real connection generation.
                        delegate.connect(address)
                    }
                } finally {
                    synchronized(lock) {
                        if (generation == connectGeneration) {
                            pendingConnectJob = null
                            scanToConnectTransitionInFlight = false
                        }
                    }
                }
            }

        synchronized(lock) {
            if (generation == connectGeneration) {
                pendingConnectJob = job
            } else {
                job.cancel()
            }
        }
        job.start()
    }

    override fun disconnect() {
        // Cancel a scan-to-connect transition before latching the explicit disconnect. A delayed
        // connect must never fire after the user has asked to disconnect.
        cancelPendingExplicitConnect()
        // Latch before asking the delegate to disconnect so its transient Disconnecting state cannot
        // reacquire the foreground service after an explicit user disconnect.
        userDisconnectedThisProcess = true
        foregroundStartFailedThisSession = false
        delegate.disconnect()
        releaseService()
        currentDeviceName = null
        if (!appForeground) delegate.setForeground(false)
    }

    override fun setForeground(foreground: Boolean) {
        appForeground = foreground
        if (foreground) {
            // Never let an Activity foreground transition initiate remembered-device auto-connect
            // while an explicit scan is active. The scan result tap owns that transition.
            if (!delegate.scanState.value.isScanning) {
                delegate.setForeground(true)
            }
        } else if (!serviceHeld) {
            delegate.setForeground(false)
        }
        // If a connected-device FGS lease is active, deliberately do not propagate false: the
        // repository may continue GATT callbacks, recording polling, reconnect and active transfer.
    }

    override suspend fun refreshDeviceInfo() = delegate.refreshDeviceInfo()

    override suspend fun syncTime(): Boolean = delegate.syncTime()

    override suspend fun startRecording() {
        holdService(resolvedDeviceName(), "正在开始录音")
        delegate.setForeground(true)
        delegate.startRecording()
    }

    override suspend fun pauseRecording() = delegate.pauseRecording()

    override suspend fun resumeRecording() {
        holdService(resolvedDeviceName(), "正在继续录音")
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
        holdService(resolvedDeviceName(), "正在下载")
        delegate.setForeground(true)
        delegate.downloadDeviceFile(file, format)
    }

    override suspend fun cancelDeviceFileDownload() = delegate.cancelDeviceFileDownload()

    override suspend fun deleteRemoteRecording(file: RemoteDeviceFile) =
        delegate.deleteRemoteRecording(file)

    override suspend fun runRangeProbe(file: RemoteDeviceFile) = delegate.runRangeProbe(file)

    override suspend fun deleteLocalRecording(localId: String): LocalDeleteResult =
        delegate.deleteLocalRecording(localId)

    private fun cancelPendingExplicitConnect() {
        val pending =
            synchronized(lock) {
                connectGeneration += 1L
                scanToConnectTransitionInFlight = false
                pendingConnectJob.also { pendingConnectJob = null }
            }
        pending?.cancel()
    }

    private fun applyDecision(decision: DeviceSessionForegroundDecision) {
        currentDeviceName = decision.deviceName.takeIf { it != FALLBACK_DEVICE_NAME } ?: currentDeviceName
        if (decision.keep) {
            holdService(decision.deviceName, decision.label)
            if (serviceHeld) {
                // A session can become Ready after the Activity has already stopped. Keep the
                // delegate active only after Android has an explicit connectedDevice FGS reason.
                delegate.setForeground(true)
            }
        } else {
            releaseService()
            if (!appForeground) delegate.setForeground(false)
        }
    }

    private fun holdService(deviceName: String, label: String): Boolean {
        if (
            userDisconnectedThisProcess ||
            foregroundStartFailedThisSession ||
            delegate.missingPermissions().isNotEmpty()
        ) {
            return false
        }

        val safeName = deviceName.trim().ifEmpty { FALLBACK_DEVICE_NAME }
        val safeLabel = label.trim().ifEmpty { "保持连接" }
        synchronized(lock) {
            if (
                userDisconnectedThisProcess ||
                foregroundStartFailedThisSession ||
                delegate.missingPermissions().isNotEmpty()
            ) {
                return false
            }
            if (
                serviceHeld &&
                lastPublishedDeviceName == safeName &&
                lastPublishedLabel == safeLabel
            ) {
                return true
            }

            val result =
                DeviceSessionForegroundService.acquire(
                    context = appContext,
                    deviceName = safeName,
                    label = safeLabel,
                )
            if (result.requestAccepted) {
                serviceHeld = true
                serviceGeneration = result.generation
                lastPublishedDeviceName = safeName
                lastPublishedLabel = safeLabel
            } else if (!serviceHeld) {
                foregroundStartFailedThisSession = true
                serviceGeneration = null
                lastPublishedDeviceName = null
                lastPublishedLabel = null
            }
            return serviceHeld
        }
    }

    private fun releaseService() {
        synchronized(lock) {
            if (!serviceHeld && serviceGeneration == null) return
            serviceHeld = false
            serviceGeneration = null
            lastPublishedDeviceName = null
            lastPublishedLabel = null
            DeviceSessionForegroundService.release(appContext)
        }
    }

    private fun resolvedDeviceName(): String =
        delegate.deviceInfo.value.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: currentDeviceName
            ?: FALLBACK_DEVICE_NAME

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
                return "正在下载 · ${percent.coerceIn(0L, 100L)}%"
            }
            return "正在下载"
        }

        return when (recording.status) {
            RecordingStatus.Recording -> "正在录音"
            RecordingStatus.Paused -> "录音已暂停"
            else ->
                when (connection) {
                    is DeviceConnectionState.ReconnectWaiting ->
                        "正在重连 · 第 ${connection.attempt} 次"
                    is DeviceConnectionState.Connecting,
                    is DeviceConnectionState.LinkConnected,
                    is DeviceConnectionState.DiscoveringServices,
                    is DeviceConnectionState.Subscribing,
                    is DeviceConnectionState.NegotiatingMtu,
                    -> "正在连接"
                    is DeviceConnectionState.Ready -> "已连接"
                    else -> "保持连接"
                }
        }
    }

    private data class DeviceSessionForegroundDecision(
        val keep: Boolean,
        val deviceName: String,
        val label: String,
    )

    private companion object {
        const val FALLBACK_DEVICE_NAME = "录音卡"
        const val SCAN_TO_CONNECT_SETTLE_MS = 250L
    }
}
