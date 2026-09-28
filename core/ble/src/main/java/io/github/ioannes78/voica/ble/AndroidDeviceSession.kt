package io.github.ioannes78.voica.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.ioannes78.voica.protocol.AuthCode
import io.github.ioannes78.voica.protocol.BatteryState
import io.github.ioannes78.voica.protocol.DeviceDecoders
import io.github.ioannes78.voica.protocol.DeviceTime
import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import io.github.ioannes78.voica.protocol.StorageCapacity
import java.io.Closeable
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidDeviceSession(
    context: Context,
    parentScope: CoroutineScope,
    private val operationTimeoutMs: Long = 5_000L,
    private val connectTimeoutMs: Long = 15_000L,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val sessionCounter = AtomicLong(0)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val lock = Any()
    private val router = NotificationRouter()
    private val log = BoundedBleLog(200)

    private val mutableState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)
    val state: StateFlow<DeviceConnectionState> = mutableState.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(BleDiagnostics())
    val diagnostics: StateFlow<BleDiagnostics> = mutableDiagnostics.asStateFlow()

    private val mutableNotifications = MutableSharedFlow<RoutedNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<RoutedNotification> = mutableNotifications.asSharedFlow()

    private var currentGeneration = 0L
    private var currentGatt: BluetoothGatt? = null
    private var backend: AndroidGattBackend? = null
    private var queue: GattOperationQueue? = null
    private var configureJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var queueSnapshotJob: Job? = null
    private var requestedDisconnectReason: DisconnectReason? = null
    private var negotiatedMtu: Int? = null

    private val commandClient = DeviceCommandClient(
        writer = { frame -> writeFrame(frame) },
        responseTimeoutMs = operationTimeoutMs,
    )

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val missing = BlePermissionPolicy.missingPermissions(applicationContext)
        if (missing.isNotEmpty()) {
            mutableState.value = DeviceConnectionState.PermissionRequired(missing)
            recordError(
                BleError(
                    BleErrorCode.PERMISSION_DENIED,
                    "missing=" + missing.joinToString(),
                ),
            )
            return false
        }

        synchronized(lock) {
            if (currentGatt != null) return false

            val address = try {
                device.address
            } catch (security: SecurityException) {
                mutableState.value = DeviceConnectionState.PermissionRequired(
                    BlePermissionPolicy.requiredPermissions(),
                )
                recordError(BleError(BleErrorCode.PERMISSION_DENIED, security.message))
                return false
            }

            currentGeneration = sessionCounter.incrementAndGet()
            requestedDisconnectReason = null
            negotiatedMtu = null
            router.reset()
            commandClient.cancelPending()
            commandClient.resetSequence()
            log.clear()
            mutableDiagnostics.value = BleDiagnostics(
                sessionId = currentGeneration,
                gattStage = "Connecting",
                logs = emptyList(),
            )
            addLog("Connect requested address=" + address + " session=" + currentGeneration)
            mutableState.value = DeviceConnectionState.Connecting(address)

            val callback = callbackFor(currentGeneration)
            val gatt = try {
                device.connectGatt(
                    applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } catch (security: SecurityException) {
                recordError(BleError(BleErrorCode.PERMISSION_DENIED, security.message))
                mutableState.value = DeviceConnectionState.PermissionRequired(
                    BlePermissionPolicy.requiredPermissions(),
                )
                return false
            } catch (error: RuntimeException) {
                val failure = BleError(BleErrorCode.CONNECT_FAILED, error.message)
                recordError(failure)
                mutableState.value = DeviceConnectionState.Error(failure)
                return false
            }

            if (gatt == null) {
                val failure = BleError(BleErrorCode.CONNECT_FAILED, "connectGatt returned null")
                recordError(failure)
                mutableState.value = DeviceConnectionState.Error(failure)
                return false
            }
            currentGatt = gatt
            startConnectTimeout(currentGeneration, address)
            return true
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val gatt = synchronized(lock) { currentGatt }
        if (gatt == null) {
            mutableState.value = DeviceConnectionState.Idle
            return
        }
        requestedDisconnectReason = DisconnectReason.USER
        mutableState.value = DeviceConnectionState.Disconnecting(safeAddress(gatt))
        addLog("User disconnect requested")
        queue?.close()
        commandClient.cancelPending()
        try {
            gatt.disconnect()
        } catch (security: SecurityException) {
            val address = safeAddress(gatt)
            releaseGatt(gatt)
            mutableState.value = DeviceConnectionState.Disconnected(
                address,
                DisconnectReason.PERMISSION_REVOKED,
            )
        }
    }

    fun handleBluetoothOff() {
        requestedDisconnectReason = DisconnectReason.BLUETOOTH_OFF
        val gatt = synchronized(lock) { currentGatt }
        if (gatt != null) releaseGatt(gatt)
        mutableState.value = DeviceConnectionState.BluetoothOff
        addLog("Bluetooth turned off")
    }

    fun markIdleIfTransportAvailable() {
        if (currentGatt == null && mutableState.value is DeviceConnectionState.BluetoothOff) {
            mutableState.value = DeviceConnectionState.Idle
        }
    }

    fun setReconnectAttempt(attempt: Int) {
        updateDiagnostics { it.copy(reconnectAttempt = attempt) }
    }

    suspend fun syncTime(now: LocalDateTime = LocalDateTime.now()): Boolean =
        commandClient.sendOnly { sequence ->
            ProtocolCodec.buildSyncTime(
                sequence,
                DeviceTime(
                    year = now.year,
                    month = now.monthValue,
                    day = now.dayOfMonth,
                    hour = now.hour,
                    minute = now.minute,
                    second = now.second,
                ),
            )
        }

    suspend fun readBattery(): BatteryState? =
        requestControl(
            requestBuilder = ProtocolCodec::buildGetBattery,
            responseCommand = ProtocolConstants.Control.BATTERY_RESPONSE,
        )?.let { DeviceDecoders.decodeBatteryState(it.body) }

    suspend fun readCapacity(): StorageCapacity? =
        requestControl(
            requestBuilder = ProtocolCodec::buildGetCapacity,
            responseCommand = ProtocolConstants.Control.CAPACITY_RESPONSE,
        )?.let { DeviceDecoders.decodeCapacity(it.body) }

    suspend fun readFirmware(): String? =
        requestControl(
            requestBuilder = ProtocolCodec::buildGetVersion,
            responseCommand = ProtocolConstants.Control.VERSION_RESPONSE,
        )?.let { DeviceDecoders.decodeFirmwareVersion(it.body) }

    suspend fun readAuth(): AuthCode? =
        requestControl(
            requestBuilder = ProtocolCodec::buildGetAuth,
            responseCommand = ProtocolConstants.Control.AUTH_RESPONSE,
        )?.let { DeviceDecoders.decodeAuthCode(it.body) }

    private suspend fun requestControl(
        requestBuilder: (Int) -> ByteArray,
        responseCommand: Int,
    ): ProtocolFrame? {
        val result = commandClient.request(
            expectedType = ProtocolConstants.Type.CONTROL,
            expectedCommand = responseCommand,
            buildRequest = requestBuilder,
        )
        return when (result) {
            is DeviceCommandResult.Success -> {
                addLog(
                    "RX response type=" + result.response.type +
                        " cmd=" + result.response.command +
                        " requestSeq=" + result.requestSequence +
                        " responseSeq=" + result.response.sequence,
                )
                result.response
            }
            DeviceCommandResult.ResponseTimedOut -> {
                recordError(BleError(BleErrorCode.RESPONSE_TIMEOUT, "cmd=" + responseCommand))
                null
            }
            DeviceCommandResult.WriteFailed -> {
                recordError(BleError(BleErrorCode.WRITE_FAILED, "cmd=" + responseCommand))
                null
            }
            DeviceCommandResult.Cancelled -> null
        }
    }

    private fun callbackFor(generation: Long): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (!isCurrent(generation)) {
                    safeClose(gatt)
                    addLog("Ignored stale connection callback session=" + generation)
                    return
                }
                updateDiagnostics { it.copy(lastGattStatus = status) }

                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    synchronized(lock) {
                        if (isCurrent(generation)) currentGatt = gatt
                    }
                    connectTimeoutJob?.cancel()
                    val address = safeAddress(gatt) ?: "unknown"
                    addLog("Link connected status=" + status)
                    mutableState.value = DeviceConnectionState.LinkConnected(address)
                    configureJob?.cancel()
                    configureJob = scope.launch { configureGatt(gatt, generation, address) }
                    return
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val reason = requestedDisconnectReason ?: DisconnectReason.REMOTE
                    val address = safeAddress(gatt)
                    addLog("Disconnected reason=" + reason + " status=" + status)
                    releaseGatt(gatt)
                    mutableState.value = DeviceConnectionState.Disconnected(address, reason, status)
                    if (reason == DisconnectReason.REMOTE) {
                        recordError(
                            BleError(
                                BleErrorCode.REMOTE_DISCONNECTED,
                                "gattStatus=" + status,
                            ),
                        )
                    }
                    return
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val failure = BleError(
                        BleErrorCode.CONNECT_FAILED,
                        "gattStatus=" + status,
                    )
                    recordError(failure)
                    releaseGatt(gatt)
                    mutableState.value = DeviceConnectionState.Error(failure)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                handleQueueCallback(
                    gatt,
                    generation,
                    GattCallbackEvent.ServicesDiscovered(status),
                )
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                handleQueueCallback(
                    gatt,
                    generation,
                    GattCallbackEvent.MtuChanged(mtu, status),
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                handleQueueCallback(
                    gatt,
                    generation,
                    GattCallbackEvent.DescriptorWritten(
                        characteristicUuid = descriptor.characteristic.uuid,
                        descriptorUuid = descriptor.uuid,
                        status = status,
                    ),
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                handleQueueCallback(
                    gatt,
                    generation,
                    GattCallbackEvent.CharacteristicWritten(
                        characteristic.uuid,
                        status,
                    ),
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                routeNotification(
                    gatt,
                    generation,
                    characteristic.uuid,
                    value,
                )
            }

            @Deprecated("API 33 adds immutable notification value")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                routeNotification(
                    gatt,
                    generation,
                    characteristic.uuid,
                    characteristic.value ?: byteArrayOf(),
                )
            }

            override fun onServiceChanged(gatt: BluetoothGatt) {
                if (!isCurrent(generation)) return
                val failure = BleError(
                    BleErrorCode.GATT_OPERATION_FAILED,
                    "Remote GATT service database changed",
                )
                recordError(failure)
                releaseGatt(gatt)
                mutableState.value = DeviceConnectionState.Error(failure)
            }
        }

    private suspend fun configureGatt(
        gatt: BluetoothGatt,
        generation: Long,
        address: String,
    ) {
        if (!isCurrent(generation)) return

        val newBackend = AndroidGattBackend(gatt)
        val newQueue = GattOperationQueue(newBackend, operationTimeoutMs)
        backend = newBackend
        queue = newQueue
        queueSnapshotJob?.cancel()
        queueSnapshotJob = scope.launch {
            newQueue.snapshot.collect { snapshot ->
                updateDiagnostics { it.copy(queue = snapshot) }
            }
        }

        mutableState.value = DeviceConnectionState.DiscoveringServices(address)
        updateStage("DiscoveringServices")
        val discovery = newQueue.execute(GattOperation.DiscoverServices())
        if (!isSuccess(discovery)) {
            failSetup(
                gatt,
                errorForOperation(BleErrorCode.GATT_OPERATION_FAILED, discovery),
            )
            return
        }

        val shape = newBackend.snapshotShape()
        updateDiagnostics { it.copy(shape = shape) }
        val shapeError = newBackend.validateShape(shape)
        if (shapeError != null) {
            failSetup(gatt, shapeError)
            return
        }

        mutableState.value =
            DeviceConnectionState.Subscribing(address, NotificationSource.AE22)
        updateStage("SubscribingAE22")
        val ae22Result = newQueue.execute(
            GattOperation.EnableNotification(BleUuids.AE22_NOTIFY),
        )
        if (!isSuccess(ae22Result)) {
            failSetup(
                gatt,
                errorForOperation(BleErrorCode.NOTIFICATION_ENABLE_FAILED, ae22Result),
            )
            return
        }
        updateDiagnostics { it.copy(ae22Subscribed = true) }

        mutableState.value =
            DeviceConnectionState.Subscribing(address, NotificationSource.AE23)
        updateStage("SubscribingAE23")
        val ae23Result = newQueue.execute(
            GattOperation.EnableNotification(BleUuids.AE23_NOTIFY),
        )
        if (!isSuccess(ae23Result)) {
            failSetup(
                gatt,
                errorForOperation(BleErrorCode.NOTIFICATION_ENABLE_FAILED, ae23Result),
            )
            return
        }
        updateDiagnostics { it.copy(ae23Subscribed = true) }

        mutableState.value =
            DeviceConnectionState.NegotiatingMtu(address, MtuPolicy.REQUESTED_ATT_MTU)
        updateStage("NegotiatingMtu")
        val mtuResult = newQueue.execute(
            GattOperation.RequestMtu(MtuPolicy.REQUESTED_ATT_MTU),
        )
        val completed = mtuResult as? GattOperationResult.Completed
        if (completed == null || completed.status != BluetoothGatt.GATT_SUCCESS) {
            failSetup(
                gatt,
                errorForOperation(BleErrorCode.MTU_REQUEST_FAILED, mtuResult),
            )
            return
        }

        val capability = MtuPolicy.evaluate(completed.negotiatedMtu)
        updateDiagnostics {
            it.copy(
                negotiatedMtu = completed.negotiatedMtu,
                mtuCapability = capability,
            )
        }
        val actualMtu = completed.negotiatedMtu
        if (!capability.isReady || actualMtu == null) {
            failSetup(
                gatt,
                BleError(
                    BleErrorCode.MTU_TOO_SMALL,
                    "actual=" + actualMtu +
                        " required>=" + MtuPolicy.MINIMUM_ATOMIC_36_ATT_MTU,
                    recoverable = false,
                ),
            )
            return
        }

        if (!isCurrent(generation)) return
        negotiatedMtu = actualMtu
        requestedDisconnectReason = null
        updateStage("Ready")
        addLog(
            "Device ready requestedMtu=" + MtuPolicy.REQUESTED_ATT_MTU +
                " actualMtu=" + actualMtu +
                " data168=" + capability.data168Supported,
        )
        mutableState.value = DeviceConnectionState.Ready(
            address = address,
            negotiatedMtu = actualMtu,
            capability = capability,
        )
    }

    private fun isSuccess(result: GattOperationResult): Boolean =
        result is GattOperationResult.Completed &&
            result.status == BluetoothGatt.GATT_SUCCESS

    private fun errorForOperation(
        code: BleErrorCode,
        result: GattOperationResult,
    ): BleError = when (result) {
        is GattOperationResult.TimedOut ->
            BleError(
                BleErrorCode.GATT_OPERATION_TIMEOUT,
                result.operation.javaClass.simpleName,
            )
        is GattOperationResult.Rejected ->
            BleError(
                BleErrorCode.GATT_OPERATION_REJECTED,
                result.operation.javaClass.simpleName,
            )
        is GattOperationResult.Cancelled ->
            BleError(code, "operation cancelled")
        is GattOperationResult.QueueClosed ->
            BleError(code, "queue closed")
        is GattOperationResult.Completed ->
            BleError(code, "gattStatus=" + result.status)
    }

    private fun failSetup(gatt: BluetoothGatt, error: BleError) {
        recordError(error)
        addLog(
            "Setup failed code=" + error.code +
                " detail=" + error.detail.orEmpty(),
        )
        releaseGatt(gatt)
        mutableState.value = DeviceConnectionState.Error(error)
    }

    private suspend fun writeFrame(frame: ByteArray): Boolean {
        val ready = state.value as? DeviceConnectionState.Ready ?: return false
        val mtu = negotiatedMtu ?: return false
        val maxValue = mtu - 3
        if (frame.size > maxValue) {
            recordError(
                BleError(
                    BleErrorCode.WRITE_REJECTED,
                    "frame=" + frame.size + " max=" + maxValue + " mtu=" + mtu,
                ),
            )
            return false
        }

        val sequence = frame.getOrNull(1)?.toInt()?.and(0xFF)
        val type = frame.getOrNull(6)?.toInt()?.and(0xFF)
        val command = frame.getOrNull(7)?.toInt()?.and(0xFF)
        updateDiagnostics {
            it.copy(
                lastTxSequence = sequence,
                lastTxType = type,
                lastTxCommand = command,
            )
        }
        addLog(
            "TX type=" + type +
                " cmd=" + command +
                " seq=" + sequence +
                " bytes=" + frame.size,
        )

        val activeQueue = queue ?: return false
        val result = activeQueue.execute(
            GattOperation.WriteCharacteristic(
                BleUuids.AE21_WRITE,
                frame.copyOf(),
            ),
        )
        val ok = isSuccess(result)
        if (!ok) {
            recordError(errorForOperation(BleErrorCode.WRITE_FAILED, result))
        }
        val currentReady = state.value as? DeviceConnectionState.Ready
        return ok && currentReady?.address == ready.address
    }

    private fun routeNotification(
        gatt: BluetoothGatt,
        generation: Long,
        characteristicUuid: UUID,
        value: ByteArray,
    ) {
        if (!isCurrent(generation) || currentGatt !== gatt) {
            addLog("Ignored stale notification session=" + generation)
            return
        }

        val routed = router.accept(characteristicUuid, value.copyOf())
        updateDiagnostics { it.copy(notifications = router.stats()) }
        routed.forEach { event ->
            if (event.source == NotificationSource.AE22) {
                commandClient.accept(event.frame)
            }
            mutableNotifications.tryEmit(event)
            addLog(
                "RX " + event.source +
                    " type=" + event.frame.type +
                    " cmd=" + event.frame.command +
                    " seq=" + event.frame.sequence +
                    " bytes=" + event.frame.data.size,
            )
        }
    }

    private fun handleQueueCallback(
        gatt: BluetoothGatt,
        generation: Long,
        event: GattCallbackEvent,
    ) {
        if (!isCurrent(generation) || currentGatt !== gatt) {
            addLog("Ignored stale GATT callback session=" + generation)
            return
        }
        val status = when (event) {
            is GattCallbackEvent.ServicesDiscovered -> event.status
            is GattCallbackEvent.DescriptorWritten -> event.status
            is GattCallbackEvent.MtuChanged -> event.status
            is GattCallbackEvent.CharacteristicWritten -> event.status
        }
        updateDiagnostics { it.copy(lastGattStatus = status) }
        if (queue?.onCallback(event) != true) {
            addLog("Unhandled GATT callback " + event.javaClass.simpleName)
        }
    }

    private fun startConnectTimeout(generation: Long, address: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(connectTimeoutMs)
            if (
                isCurrent(generation) &&
                state.value is DeviceConnectionState.Connecting
            ) {
                val failure = BleError(
                    BleErrorCode.CONNECT_TIMEOUT,
                    "address=" + address,
                )
                recordError(failure)
                currentGatt?.let(::releaseGatt)
                mutableState.value = DeviceConnectionState.Error(failure)
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        synchronized(lock) { generation == currentGeneration }

    @SuppressLint("MissingPermission")
    private fun safeAddress(gatt: BluetoothGatt): String? =
        try {
            gatt.device.address
        } catch (_: SecurityException) {
            null
        }

    @SuppressLint("MissingPermission")
    fun currentDeviceName(): String? =
        try {
            currentGatt?.device?.name
        } catch (_: SecurityException) {
            null
        }

    private fun updateStage(stage: String) {
        updateDiagnostics { it.copy(gattStage = stage) }
    }

    private fun recordError(error: BleError) {
        updateDiagnostics { it.copy(lastError = error) }
        addLog("Error " + error.code + ": " + error.detail.orEmpty())
    }

    private fun addLog(message: String) {
        log.add(message)
        updateDiagnostics { it.copy(logs = log.lines.value) }
    }

    private inline fun updateDiagnostics(
        transform: (BleDiagnostics) -> BleDiagnostics,
    ) {
        mutableDiagnostics.value = transform(mutableDiagnostics.value)
    }

    private fun releaseGatt(gatt: BluetoothGatt) {
        synchronized(lock) {
            if (currentGatt !== gatt) {
                safeClose(gatt)
                return
            }
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            configureJob?.cancel()
            configureJob = null
            queueSnapshotJob?.cancel()
            queueSnapshotJob = null
            queue?.close()
            queue = null
            backend = null
            negotiatedMtu = null
            commandClient.cancelPending()
            router.reset()
            currentGatt = null
            safeClose(gatt)
        }
        updateDiagnostics {
            it.copy(
                ae22Subscribed = false,
                ae23Subscribed = false,
                queue = GattQueueSnapshot(closed = true),
                notifications = router.stats(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeClose(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Session is already terminating.
        } catch (_: RuntimeException) {
            // Avoid crashing while releasing an invalidated GATT object.
        }
    }

    override fun close() {
        currentGatt?.let(::releaseGatt)
        scope.cancel()
    }
}
