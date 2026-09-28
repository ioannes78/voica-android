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
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface DeviceRepository {
    val scanState: StateFlow<BleScanState>
    val connectionState: StateFlow<DeviceConnectionState>
    val deviceInfo: StateFlow<DeviceInfo>
    val diagnostics: StateFlow<BleDiagnostics>

    fun missingPermissions(): Set<String>
    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun setForeground(foreground: Boolean)
    suspend fun refreshDeviceInfo()
    suspend fun syncTime(): Boolean
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

    override val scanState: StateFlow<BleScanState> = scanner.state

    private val mutableConnectionState =
        MutableStateFlow<DeviceConnectionState>(initialEnvironmentState())
    override val connectionState: StateFlow<DeviceConnectionState> =
        mutableConnectionState.asStateFlow()

    private val mutableDeviceInfo = MutableStateFlow(DeviceInfo())
    override val deviceInfo: StateFlow<DeviceInfo> = mutableDeviceInfo.asStateFlow()

    override val diagnostics: StateFlow<BleDiagnostics> = session.diagnostics

    private var foreground = true
    private var lastAddress: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var refreshJob: Job? = null
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
                    }

                    is DeviceConnectionState.Disconnected -> {
                        if (state.reason == DisconnectReason.REMOTE) {
                            scheduleReconnect(state.address ?: lastAddress)
                        }
                    }

                    is DeviceConnectionState.Error -> {
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
        lastAddress = address
        connectInternal(address)
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
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

    override fun close() {
        reconnectJob?.cancel()
        refreshJob?.cancel()
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
}
