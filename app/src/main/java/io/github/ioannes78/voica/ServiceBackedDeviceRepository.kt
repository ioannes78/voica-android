package io.github.ioannes78.voica

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val foregroundServiceAcquire: (Context, String, String) -> Long? =
        { serviceContext, deviceName, label ->
            val result =
                DeviceSessionForegroundService.acquire(
                    context = serviceContext,
                    deviceName = deviceName,
                    label = label,
                )
            result.generation.takeIf { result.requestAccepted }
        },
    private val foregroundServiceRelease: (Context) -> Unit =
        { serviceContext -> DeviceSessionForegroundService.release(serviceContext) },
    private val foregroundNotificationUpdate: (Context, String, String) -> Unit =
        { serviceContext, deviceName, label ->
            DeviceSessionNotificationUpdater.update(serviceContext, deviceName, label)
        },
    private val realtimeState: StateFlow<Stage16ARealtimeUiState>? = null,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val progressUpdateMinIntervalMs: Long = PROGRESS_NOTIFICATION_MIN_INTERVAL_MS,
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

    private var delegateForegroundActive = false
    private var serviceGeneration: Long? = null
    private var currentDeviceName: String? = null
    private var lastPublishedDeviceName: String? = null
    private var lastPublishedLabel: String? = null
    private var pendingConnectJob: Job? = null
    private var pendingProgressNotificationJob: Job? = null
    private var lastProgressNotificationElapsedMs: Long? = null
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
            ) { _, _, _, _ ->
                Unit
            }.collect {
                // Combined emissions are invalidation signals only. Always re-read current business
                // truth at publication time so an old progress emission can never carry a stale label.
                reconcileCurrentDeviceSessionDecision()
            }
        }

        realtimeState?.let { states ->
            scope.launch {
                states
                    .map(Stage16ARealtimeNotificationPolicy::project)
                    .distinctUntilChanged()
                    .collect {
                        // Hypothesis/audio-envelope churn is intentionally absent from the projected
                        // state, so only lifecycle/model changes can invalidate the notification.
                        reconcileCurrentDeviceSessionDecision(forceImmediate = true)
                    }
            }
        }

        scope.launch {
            DeviceSessionForegroundDiagnostics.state.collect { snapshot ->
                val shouldDropLease: Boolean
                val shouldRefreshLatest: Boolean
                synchronized(lock) {
                    shouldDropLease =
                        snapshot.phase == DeviceSessionForegroundPhase.FAILED &&
                            snapshot.generation == serviceGeneration
                    shouldRefreshLatest =
                        snapshot.phase == DeviceSessionForegroundPhase.ACTIVE &&
                            snapshot.generation == serviceGeneration
                    if (shouldDropLease) {
                        serviceHeld = false
                        serviceGeneration = null
                        lastPublishedDeviceName = null
                        lastPublishedLabel = null
                        foregroundStartFailedThisSession = true
                    }
                }
                if (shouldDropLease) {
                    reconcileDelegateForeground()
                } else if (shouldRefreshLatest) {
                    // State may have advanced while startForegroundService was still STARTING.
                    // Re-project the latest business truth once the single lease becomes ACTIVE.
                    reconcileCurrentDeviceSessionDecision(forceImmediate = true)
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
        cancelPendingExplicitConnect()
        userDisconnectedThisProcess = true
        foregroundStartFailedThisSession = false
        delegate.disconnect()
        releaseService()
        currentDeviceName = null
    }

    override fun setForeground(foreground: Boolean) {
        appForeground = foreground
        reconcileDelegateForeground()
    }

    override suspend fun refreshDeviceInfo() = delegate.refreshDeviceInfo()

    override suspend fun syncTime(): Boolean = delegate.syncTime()

    override suspend fun startRecording() {
        holdService(resolvedDeviceName(), "正在开始录音")
        delegate.startRecording()
    }

    override suspend fun pauseRecording() = delegate.pauseRecording()

    override suspend fun resumeRecording() {
        holdService(resolvedDeviceName(), "正在继续录音")
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
        try {
            delegate.downloadDeviceFile(file, format)
        } finally {
            // Terminal business state must override any coalesced progress immediately.
            reconcileCurrentDeviceSessionDecision(forceImmediate = true)
        }
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

    private fun reconcileCurrentDeviceSessionDecision(forceImmediate: Boolean = false) {
        synchronized(lock) {
            val decision =
                decisionFor(
                    connection = delegate.connectionState.value,
                    recording = delegate.recordingState.value,
                    operation = delegate.fileOperationState.value,
                    info = delegate.deviceInfo.value,
                )
            applyDecision(decision, forceImmediate)
        }
    }

    private fun decisionFor(
        connection: DeviceConnectionState,
        recording: RecordingDeviceState,
        operation: FileOperationState,
        info: DeviceInfo,
    ): DeviceSessionForegroundDecision {
        val resolvedName =
            info.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: currentDeviceName
                ?: FALLBACK_DEVICE_NAME
        val activeOperation = operation as? FileOperationState.Active
        val downloadActive = activeOperation?.operation == DeviceFileOperationType.DOWNLOAD
        val coalescibleProgress =
            downloadActive &&
                activeOperation.progress?.expectedBytes?.let { it > 0L } == true
        val baseLabel = deviceSessionLabel(connection, recording, operation)
        val realtimeProjection = realtimeState?.value?.let(Stage16ARealtimeNotificationPolicy::project)
        return DeviceSessionForegroundDecision(
            keep =
                !userDisconnectedThisProcess &&
                    shouldKeepDeviceSession(connection),
            deviceName = resolvedName,
            label =
                Stage16ARealtimeNotificationPolicy.label(
                    baseDeviceLabel = baseLabel,
                    realtime = realtimeProjection,
                    downloadActive = downloadActive,
                ),
            coalescibleProgress = coalescibleProgress,
        )
    }

    private fun applyDecision(
        decision: DeviceSessionForegroundDecision,
        forceImmediate: Boolean,
    ) {
        currentDeviceName = decision.deviceName.takeIf { it != FALLBACK_DEVICE_NAME } ?: currentDeviceName
        if (!decision.keep) {
            cancelPendingProgressNotificationLocked()
            releaseService()
            return
        }

        if (!decision.coalescibleProgress || forceImmediate || progressUpdateMinIntervalMs <= 0L) {
            cancelPendingProgressNotificationLocked()
            publishDecisionNow(decision)
            return
        }

        val now = elapsedRealtimeMs()
        val previous = lastProgressNotificationElapsedMs
        val elapsed = previous?.let { now - it } ?: Long.MAX_VALUE
        if (elapsed >= progressUpdateMinIntervalMs) {
            cancelPendingProgressNotificationLocked()
            publishDecisionNow(decision)
            return
        }

        if (pendingProgressNotificationJob == null) {
            val waitMs = (progressUpdateMinIntervalMs - elapsed).coerceAtLeast(1L)
            pendingProgressNotificationJob =
                scope.launch {
                    delay(waitMs)
                    synchronized(lock) {
                        pendingProgressNotificationJob = null
                    }
                    // Re-read current truth. If the download already reached a terminal state this
                    // publishes that state instead of replaying the coalesced historical percentage.
                    reconcileCurrentDeviceSessionDecision(forceImmediate = true)
                }
        }
    }

    private fun publishDecisionNow(decision: DeviceSessionForegroundDecision) {
        if (decision.coalescibleProgress) {
            lastProgressNotificationElapsedMs = elapsedRealtimeMs()
        }
        holdService(decision.deviceName, decision.label)
    }

    private fun cancelPendingProgressNotificationLocked() {
        pendingProgressNotificationJob?.cancel()
        pendingProgressNotificationJob = null
        lastProgressNotificationElapsedMs = null
    }

    private fun holdService(deviceName: String, label: String): Boolean {
        if (
            userDisconnectedThisProcess ||
            foregroundStartFailedThisSession ||
            delegate.missingPermissions().isNotEmpty()
        ) {
            reconcileDelegateForeground()
            return false
        }

        val safeName = deviceName.trim().ifEmpty { FALLBACK_DEVICE_NAME }
        val safeLabel = label.trim().ifEmpty { "保持连接" }
        val held =
            synchronized(lock) {
                if (
                    userDisconnectedThisProcess ||
                    foregroundStartFailedThisSession ||
                    delegate.missingPermissions().isNotEmpty()
                ) {
                    serviceHeld
                } else if (
                    serviceHeld &&
                    lastPublishedDeviceName == safeName &&
                    lastPublishedLabel == safeLabel
                ) {
                    true
                } else if (serviceHeld && serviceGeneration != null) {
                    val generation = serviceGeneration!!
                    val foregroundSnapshot = DeviceSessionForegroundDiagnostics.state.value
                    when {
                        foregroundSnapshot.generation == generation &&
                            foregroundSnapshot.phase == DeviceSessionForegroundPhase.ACTIVE -> {
                            val revision =
                                DeviceSessionNotificationDisplayDiagnostics.recordDesired(
                                    leaseGeneration = generation,
                                    deviceName = safeName,
                                    label = safeLabel,
                                )
                            try {
                                foregroundNotificationUpdate(appContext, safeName, safeLabel)
                                DeviceSessionNotificationDisplayDiagnostics.markRendered(
                                    leaseGeneration = generation,
                                    revision = revision,
                                    deviceName = safeName,
                                    label = safeLabel,
                                )
                                lastPublishedDeviceName = safeName
                                lastPublishedLabel = safeLabel
                            } catch (error: RuntimeException) {
                                Log.e(TAG, "connectedDevice notification update failed", error)
                            }
                            true
                        }

                        foregroundSnapshot.generation == generation &&
                            foregroundSnapshot.phase == DeviceSessionForegroundPhase.STARTING -> {
                            // Do not enqueue another startForegroundService command. Remember the
                            // desired display state and publish it when the one lease becomes ACTIVE.
                            DeviceSessionNotificationDisplayDiagnostics.recordDesired(
                                leaseGeneration = generation,
                                deviceName = safeName,
                                label = safeLabel,
                            )
                            true
                        }

                        else -> {
                            acquireServiceLeaseLocked(safeName, safeLabel)
                        }
                    }
                } else {
                    acquireServiceLeaseLocked(safeName, safeLabel)
                }
            }
        reconcileDelegateForeground()
        return held
    }

    private fun acquireServiceLeaseLocked(
        safeName: String,
        safeLabel: String,
    ): Boolean {
        val generation = foregroundServiceAcquire(appContext, safeName, safeLabel)
        if (generation != null) {
            serviceHeld = true
            serviceGeneration = generation
            lastPublishedDeviceName = safeName
            lastPublishedLabel = safeLabel
            DeviceSessionNotificationDisplayDiagnostics.recordDesired(
                leaseGeneration = generation,
                deviceName = safeName,
                label = safeLabel,
            )
        } else if (!serviceHeld) {
            foregroundStartFailedThisSession = true
            serviceGeneration = null
            lastPublishedDeviceName = null
            lastPublishedLabel = null
        }
        return serviceHeld
    }

    private fun releaseService() {
        val shouldRelease =
            synchronized(lock) {
                cancelPendingProgressNotificationLocked()
                val hadLease = serviceHeld || serviceGeneration != null
                serviceHeld = false
                serviceGeneration = null
                lastPublishedDeviceName = null
                lastPublishedLabel = null
                DeviceSessionNotificationDisplayDiagnostics.reset()
                hadLease
            }
        if (shouldRelease) {
            foregroundServiceRelease(appContext)
        }
        reconcileDelegateForeground()
    }

    private fun reconcileDelegateForeground() {
        val transition =
            synchronized(lock) {
                val scanActive = delegate.scanState.value.isScanning
                val appRequiresForeground =
                    appForeground &&
                        (!scanActive || delegateForegroundActive)
                val desired = serviceHeld || appRequiresForeground
                if (delegateForegroundActive == desired) {
                    null
                } else {
                    delegateForegroundActive = desired
                    desired
                }
            }
        if (transition != null) {
            delegate.setForeground(transition)
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
                        if (connection.attempt <= FAST_RECONNECT_VISIBLE_ATTEMPTS) {
                            "正在重连 · 第 ${connection.attempt} 次"
                        } else {
                            "等待设备重新连接"
                        }
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
        val coalescibleProgress: Boolean,
    )

    private companion object {
        const val TAG = "VoicaDeviceSessionOwner"
        const val FALLBACK_DEVICE_NAME = "录音卡"
        const val SCAN_TO_CONNECT_SETTLE_MS = 250L
        const val FAST_RECONNECT_VISIBLE_ATTEMPTS = 3
        const val PROGRESS_NOTIFICATION_MIN_INTERVAL_MS = 300L
    }
}
