package io.github.ioannes78.voica.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.ioannes78.voica.protocol.DeviceDecoders
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.RecordingCommandResult
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DeviceRepository {
    val scanState: StateFlow<BleScanState>
    val connectionState: StateFlow<DeviceConnectionState>
    val deviceInfo: StateFlow<DeviceInfo>
    val recordingState: StateFlow<RecordingDeviceState>
    val diagnostics: StateFlow<BleDiagnostics>

    fun missingPermissions(): Set<String>
    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun setForeground(foreground: Boolean)
    suspend fun refreshDeviceInfo()
    suspend fun syncTime(): Boolean

    suspend fun startRecording()
    suspend fun pauseRecording()
    suspend fun resumeRecording()
    suspend fun saveRecording()
    suspend fun syncRecordingState()
    suspend fun setRecordingGain(gain: RecordingGain)
}

class DefaultDeviceRepository(
    context: Context,
    parentScope: CoroutineScope,
) : DeviceRepository, Closeable {
    private val applicationContext = context.applicationContext
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val bluetoothManager =
        applicationContext.getSystemService(BluetoothManager::class.java)
    private val scanner = AndroidBleScanner(applicationContext, scope)
    private val session = AndroidDeviceSession(applicationContext, scope)
    private val recordingFrameRouter = RecordingFrameRouter()
    private val recordingSyncMutex = Mutex()

    override val scanState: StateFlow<BleScanState> = scanner.state

    private val mutableConnectionState =
        MutableStateFlow<DeviceConnectionState>(initialEnvironmentState())
    override val connectionState: StateFlow<DeviceConnectionState> =
        mutableConnectionState.asStateFlow()

    private val mutableDeviceInfo = MutableStateFlow(DeviceInfo())
    override val deviceInfo: StateFlow<DeviceInfo> = mutableDeviceInfo.asStateFlow()

    private val mutableRecordingState = MutableStateFlow(RecordingDeviceState())
    override val recordingState: StateFlow<RecordingDeviceState> =
        mutableRecordingState.asStateFlow()

    override val diagnostics: StateFlow<BleDiagnostics> = session.diagnostics

    private var foreground = true
    private var lastAddress: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var refreshJob: Job? = null
    private var recordingPollJob: Job? = null
    private var recordingReconcileJob: Job? = null
    private var lastReadySessionId: Long? = null
    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR,
                )
            ) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF,
                -> {
                    scanner.stop()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectAttempt = 0
                    session.setReconnectAttempt(0)
                    stopRecordingPoller()
                    recordingReconcileJob?.cancel()
                    recordingReconcileJob = null
                    markRecordingDisconnected()
                    session.handleBluetoothOff()
                    mutableConnectionState.value = DeviceConnectionState.BluetoothOff
                }

                BluetoothAdapter.STATE_ON -> {
                    session.markIdleIfTransportAvailable()
                    if (mutableConnectionState.value is DeviceConnectionState.BluetoothOff) {
                        mutableConnectionState.value = initialEnvironmentState()
                    }
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()

        scope.launch {
            scanner.state.collect { scan ->
                if (scan.isScanning) {
                    if (mutableConnectionState.value is DeviceConnectionState.Idle) {
                        mutableConnectionState.value = DeviceConnectionState.Scanning
                    }
                } else if (mutableConnectionState.value is DeviceConnectionState.Scanning) {
                    mutableConnectionState.value = initialEnvironmentState()
                }
            }
        }

        scope.launch {
            session.notifications.collect { event ->
                val frame = event.frame
                if (
                    frame.type == ProtocolConstants.Type.CONTROL &&
                    frame.command == ProtocolConstants.Control.BATTERY_RESPONSE
                ) {
                    mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                        battery = DeviceDecoders.decodeBatteryState(frame.body),
                    )
                }

                recordingFrameRouter.route(event)?.let(::handleRecordingFrameEvent)
            }
        }

        scope.launch {
            session.state.collect { state ->
                mutableConnectionState.value = state
                when (state) {
                    is DeviceConnectionState.Ready -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        reconnectAttempt = 0
                        session.setReconnectAttempt(0)
                        mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                            address = state.address,
                            negotiatedMtu = state.negotiatedMtu,
                            name = session.currentDeviceName()
                                ?: mutableDeviceInfo.value.name,
                        )
                        refreshJob?.cancel()
                        refreshJob = scope.launch { refreshDeviceInfo() }

                        val sessionId = session.diagnostics.value.sessionId
                        val reason =
                            if (lastReadySessionId == null) {
                                RecordingSyncReason.INITIAL_READY
                            } else if (lastReadySessionId != sessionId) {
                                RecordingSyncReason.RECONNECT
                            } else {
                                RecordingSyncReason.INITIAL_READY
                            }
                        lastReadySessionId = sessionId
                        scheduleRecordingSync(reason, delayMs = 0)
                    }

                    is DeviceConnectionState.Disconnected -> {
                        stopRecordingPoller()
                        recordingReconcileJob?.cancel()
                        recordingReconcileJob = null
                        markRecordingDisconnected()
                        if (state.reason == DisconnectReason.REMOTE) {
                            scheduleReconnect(state.address ?: lastAddress)
                        }
                    }

                    DeviceConnectionState.BluetoothOff,
                    is DeviceConnectionState.PermissionRequired,
                    -> {
                        stopRecordingPoller()
                        markRecordingDisconnected()
                    }

                    is DeviceConnectionState.Error -> {
                        stopRecordingPoller()
                        markRecordingDisconnected()
                        if (ReconnectPolicy.shouldRetry(state.error)) {
                            scheduleReconnect(lastAddress)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun missingPermissions(): Set<String> =
        BlePermissionPolicy.missingPermissions(applicationContext)

    override fun startScan() {
        refreshEnvironment()
        if (missingPermissions().isNotEmpty()) return
        if (!isBluetoothEnabled()) return
        scanner.start()
    }

    override fun stopScan() {
        scanner.stop()
    }

    override fun connect(address: String) {
        scanner.stop()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
        stopRecordingPoller()
        recordingReconcileJob?.cancel()
        recordingReconcileJob = null
        lastReadySessionId = null
        lastAddress = address
        connectInternal(address)
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
        stopRecordingPoller()
        recordingReconcileJob?.cancel()
        recordingReconcileJob = null
        markRecordingDisconnected()
        session.disconnect()
    }

    override fun setForeground(foreground: Boolean) {
        val wasForeground = this.foreground
        this.foreground = foreground

        if (!foreground) {
            scanner.stop()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            session.setReconnectAttempt(0)
            stopRecordingPoller()
            if (mutableConnectionState.value is DeviceConnectionState.ReconnectWaiting) {
                mutableConnectionState.value = session.state.value
            }
            return
        }

        val environment = initialEnvironmentState()
        when (environment) {
            is DeviceConnectionState.PermissionRequired -> {
                scanner.stop()
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                session.setReconnectAttempt(0)
                stopRecordingPoller()
                markRecordingDisconnected()
                session.handlePermissionRevoked()
                mutableConnectionState.value = environment
                return
            }
            DeviceConnectionState.BluetoothOff -> {
                scanner.stop()
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                session.setReconnectAttempt(0)
                stopRecordingPoller()
                markRecordingDisconnected()
                session.handleBluetoothOff()
                mutableConnectionState.value = environment
                return
            }
            DeviceConnectionState.Unavailable -> {
                mutableConnectionState.value = environment
                return
            }
            else -> Unit
        }

        if (!wasForeground) {
            when (val current = session.state.value) {
                is DeviceConnectionState.Ready -> {
                    scheduleRecordingSync(
                        RecordingSyncReason.FOREGROUND_RETURN,
                        delayMs = 0,
                    )
                }
                is DeviceConnectionState.Disconnected -> {
                    if (current.reason == DisconnectReason.REMOTE) {
                        scheduleReconnect(current.address ?: lastAddress)
                    }
                }
                is DeviceConnectionState.Error -> {
                    if (ReconnectPolicy.shouldRetry(current.error)) {
                        scheduleReconnect(lastAddress)
                    }
                }
                else -> Unit
            }
        }
    }

    override suspend fun refreshDeviceInfo() {
        val ready = session.state.value as? DeviceConnectionState.Ready ?: return
        val address = ready.address
        val scanName = scanner.state.value.devices
            .firstOrNull { it.address == address }
            ?.name

        mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
            name = scanName ?: session.currentDeviceName(),
            address = address,
            negotiatedMtu = ready.negotiatedMtu,
        )

        session.syncTime()

        session.readBattery()?.let { battery ->
            mutableDeviceInfo.value =
                mutableDeviceInfo.value.copy(battery = battery)
        }

        session.readCapacity()?.let { capacity ->
            mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                remainingKb = capacity.remaining,
                totalKb = capacity.total,
                capacityByteOrder = capacity.byteOrder,
            )
        }

        session.readFirmware()?.let { firmware ->
            mutableDeviceInfo.value =
                mutableDeviceInfo.value.copy(firmwareVersion = firmware)
        }

        session.readAuth()?.let { auth ->
            mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                authAvailable = auth.text != null || auth.hex.isNotEmpty(),
                authDisplay = auth.text ?: auth.hex,
            )
        }
    }

    override suspend fun syncTime(): Boolean = session.syncTime()

    override suspend fun startRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Idle
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.STARTING,
            expectedSuccessCode = 1,
            action = session::startRecording,
        )
    }

    override suspend fun pauseRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Recording
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.PAUSING,
            expectedSuccessCode = 1,
            action = session::pauseRecording,
        )
    }

    override suspend fun resumeRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Paused
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.RESUMING,
            expectedSuccessCode = 1,
            action = session::resumeRecording,
        )
    }

    override suspend fun saveRecording() {
        val current = mutableRecordingState.value.status
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            (current != RecordingStatus.Recording && current != RecordingStatus.Paused)
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.SAVING,
            expectedSuccessCode = 1,
            action = session::saveRecording,
        )
    }

    override suspend fun syncRecordingState() {
        syncRecordingState(RecordingSyncReason.MANUAL_REFRESH)
    }

    override suspend fun setRecordingGain(gain: RecordingGain) {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.freshness != RecordingFreshness.FRESH
        ) {
            return
        }
        val raw = when (gain) {
            RecordingGain.Low -> ProtocolConstants.RecordingGainValue.LOW
            RecordingGain.Medium -> ProtocolConstants.RecordingGainValue.MEDIUM
            RecordingGain.High -> ProtocolConstants.RecordingGainValue.HIGH
            is RecordingGain.UnknownRaw -> return
        }

        stopRecordingPoller()
        reduceRecordingState(
            RecordingStateEvent.CommandStarted(
                RecordingCommandState.SETTING_GAIN,
                nowMs(),
            ),
        )

        val outcome = session.setRecordingGain(raw)
        val error = commandError(outcome, expectedSuccessCode = 0)
        reduceRecordingState(
            RecordingStateEvent.CommandFinished(
                error = error,
                timestampMs = nowMs(),
            ),
        )

        val gainOutcome = session.readRecordingGain()
        when (gainOutcome) {
            is RecordingRequestOutcome.Success -> {
                reduceRecordingState(
                    RecordingStateEvent.GainReceived(
                        gainOutcome.value,
                        nowMs(),
                    ),
                )
                reduceRecordingState(
                    RecordingStateEvent.SyncCompleted(nowMs()),
                    syncReason = RecordingSyncReason.APP_COMMAND,
                )
            }
            else -> {
                val gainError = requestError(gainOutcome, "GET_GAIN")
                if (gainError != null) {
                    reduceRecordingState(
                        RecordingStateEvent.OperationError(gainError, nowMs()),
                    )
                }
            }
        }

        if (error != null) {
            reduceRecordingState(RecordingStateEvent.OperationError(error, nowMs()))
        }
        updateRecordingPoller()
    }

    private suspend fun performRecordingCommand(
        commandState: RecordingCommandState,
        expectedSuccessCode: Int,
        action: suspend () -> RecordingRequestOutcome<RecordingCommandResult>,
    ) {
        stopRecordingPoller()
        reduceRecordingState(
            RecordingStateEvent.CommandStarted(commandState, nowMs()),
        )

        val outcome = action()
        val error = commandError(outcome, expectedSuccessCode)
        reduceRecordingState(
            RecordingStateEvent.CommandFinished(error, nowMs()),
        )

        syncRecordingState(RecordingSyncReason.APP_COMMAND)

        if (error != null) {
            reduceRecordingState(RecordingStateEvent.OperationError(error, nowMs()))
        }
    }

    private suspend fun syncRecordingState(reason: RecordingSyncReason) {
        recordingSyncMutex.withLock {
            if (session.state.value !is DeviceConnectionState.Ready) {
                markRecordingDisconnected()
                return
            }

            reduceRecordingState(
                RecordingStateEvent.SyncStarted(nowMs()),
                syncReason = reason,
            )

            val stateOutcome = session.readRecordingState()
            val status = when (stateOutcome) {
                is RecordingRequestOutcome.Success -> stateOutcome.value
                else -> {
                    val error = requestError(stateOutcome, "GET_STATE")
                        ?: RecordingError(
                            RecordingErrorCode.SYNC_FAILED,
                            "GET_STATE failed",
                        )
                    reduceRecordingState(
                        RecordingStateEvent.SyncFailed(error, nowMs()),
                        syncReason = reason,
                    )
                    stopRecordingPoller()
                    return
                }
            }

            reduceRecordingState(
                RecordingStateEvent.StateReceived(status, nowMs()),
                syncReason = reason,
            )

            when (val time = session.readRecordingTime()) {
                is RecordingRequestOutcome.Success ->
                    reduceRecordingState(
                        RecordingStateEvent.TimeReceived(time.value, nowMs()),
                        syncReason = reason,
                    )
                else -> noteOptionalReadFailure(time, "GET_TIME")
            }

            when (val filename = session.readRecordingFilename()) {
                is RecordingRequestOutcome.Success ->
                    reduceRecordingState(
                        RecordingStateEvent.FilenameReceived(filename.value, nowMs()),
                        syncReason = reason,
                    )
                else -> noteOptionalReadFailure(filename, "GET_FILENAME")
            }

            when (val gain = session.readRecordingGain()) {
                is RecordingRequestOutcome.Success ->
                    reduceRecordingState(
                        RecordingStateEvent.GainReceived(gain.value, nowMs()),
                        syncReason = reason,
                    )
                else -> noteOptionalReadFailure(gain, "GET_GAIN")
            }

            reduceRecordingState(
                RecordingStateEvent.SyncCompleted(nowMs()),
                syncReason = reason,
            )
            updateRecordingPoller()
        }
    }

    private fun handleRecordingFrameEvent(event: RecordingFrameEvent) {
        when (event) {
            is RecordingFrameEvent.State -> {
                reduceRecordingState(
                    RecordingStateEvent.StateReceived(event.value, nowMs()),
                )
                updateRecordingPoller()
            }

            is RecordingFrameEvent.Time ->
                reduceRecordingState(
                    RecordingStateEvent.TimeReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.Filename ->
                reduceRecordingState(
                    RecordingStateEvent.FilenameReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.Gain ->
                reduceRecordingState(
                    RecordingStateEvent.GainReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.CommandResponse -> Unit

            is RecordingFrameEvent.Hardware -> {
                reduceRecordingState(RecordingStateEvent.HardwareReceived(event.event))
                session.noteRecordingHardwareEvent(event.event)
                scheduleRecordingSync(
                    RecordingSyncReason.HARDWARE_EVENT,
                    delayMs = HARDWARE_EVENT_RECONCILE_DELAY_MS,
                )
            }

            is RecordingFrameEvent.Malformed -> {
                val error = RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    "cmd=" + event.command + " " + event.reason,
                )
                reduceRecordingState(
                    RecordingStateEvent.DecodeFailed(error, nowMs()),
                )
                session.noteRecordingDecodeError(event.reason)
            }

            is RecordingFrameEvent.Unknown -> Unit
        }
    }

    private fun scheduleRecordingSync(
        reason: RecordingSyncReason,
        delayMs: Long,
    ) {
        recordingReconcileJob?.cancel()
        recordingReconcileJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            syncRecordingState(reason)
        }
    }

    private fun updateRecordingPoller() {
        val shouldPoll = RecordingPollingPolicy.shouldPoll(
            ready = session.state.value is DeviceConnectionState.Ready,
            foreground = foreground,
            status = mutableRecordingState.value.status,
        )

        if (!shouldPoll) {
            stopRecordingPoller()
            return
        }
        if (recordingPollJob?.isActive == true) return

        val pollJob = scope.launch {
            session.updateRecordingDiagnostics(
                mutableRecordingState.value,
                pollingActive = true,
            )
            while (
                isActive &&
                RecordingPollingPolicy.shouldPoll(
                    ready = session.state.value is DeviceConnectionState.Ready,
                    foreground = foreground,
                    status = mutableRecordingState.value.status,
                )
            ) {
                delay(RECORDING_TIME_POLL_MS)
                if (!isActive) break
                if (
                    !RecordingPollingPolicy.shouldPoll(
                        ready = session.state.value is DeviceConnectionState.Ready,
                        foreground = foreground,
                        status = mutableRecordingState.value.status,
                    )
                ) {
                    break
                }

                when (val time = session.readRecordingTime()) {
                    is RecordingRequestOutcome.Success ->
                        reduceRecordingState(
                            RecordingStateEvent.TimeReceived(time.value, nowMs()),
                            syncReason = RecordingSyncReason.PERIODIC_REFRESH,
                        )
                    else -> {
                        val error = requestError(time, "GET_TIME")
                        if (error != null) {
                            reduceRecordingState(
                                RecordingStateEvent.OperationError(error, nowMs()),
                                syncReason = RecordingSyncReason.PERIODIC_REFRESH,
                            )
                        }
                    }
                }
            }
        }
        recordingPollJob = pollJob
        pollJob.invokeOnCompletion {
            if (recordingPollJob === pollJob) {
                recordingPollJob = null
                session.updateRecordingDiagnostics(
                    mutableRecordingState.value,
                    pollingActive = false,
                )
            }
        }
    }

    private fun stopRecordingPoller() {
        val current = recordingPollJob
        recordingPollJob = null
        current?.cancel()
        session.updateRecordingDiagnostics(
            mutableRecordingState.value,
            pollingActive = false,
        )
    }

    private fun markRecordingDisconnected() {
        if (mutableRecordingState.value.freshness == RecordingFreshness.NOT_SYNCED) return
        reduceRecordingState(RecordingStateEvent.Disconnected(nowMs()))
    }

    private fun reduceRecordingState(
        event: RecordingStateEvent,
        syncReason: RecordingSyncReason? = null,
    ) {
        mutableRecordingState.value =
            RecordingStateReducer.reduce(mutableRecordingState.value, event)
        session.updateRecordingDiagnostics(
            mutableRecordingState.value,
            reason = syncReason,
        )
    }

    private fun <T> noteOptionalReadFailure(
        outcome: RecordingRequestOutcome<T>,
        operation: String,
    ) {
        val error = requestError(outcome, operation) ?: return
        reduceRecordingState(
            RecordingStateEvent.OperationError(error, nowMs()),
        )
    }

    private fun commandError(
        outcome: RecordingRequestOutcome<RecordingCommandResult>,
        expectedSuccessCode: Int,
    ): RecordingError? =
        when (outcome) {
            is RecordingRequestOutcome.Success ->
                if (outcome.value.rawCode == expectedSuccessCode) {
                    null
                } else {
                    RecordingError(
                        RecordingErrorCode.UNKNOWN_RESULT_CODE,
                        "raw=" + outcome.value.rawCode +
                            " expected=" + expectedSuccessCode,
                    )
                }

            is RecordingRequestOutcome.Malformed ->
                RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    outcome.reason,
                )

            RecordingRequestOutcome.WriteFailed ->
                RecordingError(RecordingErrorCode.WRITE_FAILED)

            RecordingRequestOutcome.ResponseTimedOut ->
                RecordingError(RecordingErrorCode.RESPONSE_TIMEOUT)

            RecordingRequestOutcome.Cancelled ->
                RecordingError(RecordingErrorCode.REQUEST_CANCELLED)
        }

    private fun <T> requestError(
        outcome: RecordingRequestOutcome<T>,
        operation: String,
    ): RecordingError? =
        when (outcome) {
            is RecordingRequestOutcome.Success -> null
            is RecordingRequestOutcome.Malformed ->
                RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    operation + ": " + outcome.reason,
                )
            RecordingRequestOutcome.WriteFailed ->
                RecordingError(
                    RecordingErrorCode.WRITE_FAILED,
                    operation,
                )
            RecordingRequestOutcome.ResponseTimedOut ->
                RecordingError(
                    RecordingErrorCode.RESPONSE_TIMEOUT,
                    operation,
                )
            RecordingRequestOutcome.Cancelled ->
                RecordingError(
                    RecordingErrorCode.REQUEST_CANCELLED,
                    operation,
                )
        }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) {
            mutableConnectionState.value = environment
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            mutableConnectionState.value = DeviceConnectionState.Unavailable
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            mutableConnectionState.value = DeviceConnectionState.Error(
                BleError(BleErrorCode.CONNECT_FAILED, error.message),
            )
            return
        } catch (security: SecurityException) {
            mutableConnectionState.value = DeviceConnectionState.PermissionRequired(
                BlePermissionPolicy.requiredPermissions(),
            )
            return
        }

        session.connect(device)
    }

    private fun scheduleReconnect(address: String?) {
        val target = address ?: return
        if (!foreground) return

        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) return

        val nextAttempt = reconnectAttempt + 1
        val waitMs = ReconnectPolicy.delayForAttempt(nextAttempt)
        if (waitMs == null) {
            reconnectJob?.cancel()
            reconnectJob = null
            mutableConnectionState.value = DeviceConnectionState.Disconnected(
                target,
                DisconnectReason.REMOTE,
            )
            return
        }
        reconnectAttempt = nextAttempt
        session.setReconnectAttempt(nextAttempt)
        mutableConnectionState.value = DeviceConnectionState.ReconnectWaiting(
            address = target,
            attempt = nextAttempt,
            delayMs = waitMs,
        )

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            reconnectJob = null
            if (
                foreground &&
                reconnectAttempt == nextAttempt &&
                initialEnvironmentState() is DeviceConnectionState.Idle
            ) {
                connectInternal(target)
            }
        }
    }

    private fun refreshEnvironment() {
        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) {
            mutableConnectionState.value = environment
            return
        }

        val sessionState = session.state.value
        mutableConnectionState.value =
            if (
                sessionState is DeviceConnectionState.Idle ||
                sessionState is DeviceConnectionState.Disconnected ||
                sessionState is DeviceConnectionState.Error ||
                sessionState is DeviceConnectionState.BluetoothOff
            ) {
                DeviceConnectionState.Idle
            } else {
                sessionState
            }
    }

    private fun initialEnvironmentState(): DeviceConnectionState {
        val hasBle = applicationContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE,
        )
        if (!hasBle || bluetoothManager?.adapter == null) {
            return DeviceConnectionState.Unavailable
        }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            return DeviceConnectionState.PermissionRequired(missing)
        }

        if (!isBluetoothEnabled()) {
            return DeviceConnectionState.BluetoothOff
        }

        return DeviceConnectionState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean =
        try {
            bluetoothManager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    override fun close() {
        reconnectJob?.cancel()
        refreshJob?.cancel()
        recordingReconcileJob?.cancel()
        stopRecordingPoller()
        scanner.stop()
        session.close()

        if (receiverRegistered) {
            try {
                applicationContext.unregisterReceiver(bluetoothReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            receiverRegistered = false
        }

        scope.cancel()
    }

    private companion object {
        const val RECORDING_TIME_POLL_MS = 1_000L
        const val HARDWARE_EVENT_RECONCILE_DELAY_MS = 120L
    }
}
