package io.github.ioannes78.voica.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidBleScanner(
    context: Context,
    private val scope: CoroutineScope,
    private val scanTimeoutMs: Long = 8_000L,
) : BleScanner {
    private val applicationContext = context.applicationContext
    private val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
    private val accumulator = BleScanAccumulator()
    private var timeoutJob: Job? = null

    private val mutableState = MutableStateFlow(BleScanState())
    override val state: StateFlow<BleScanState> = mutableState.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            acceptResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::acceptResult)
        }

        override fun onScanFailed(errorCode: Int) {
            timeoutJob?.cancel()
            mutableState.value = mutableState.value.copy(
                isScanning = false,
                error = BleError(
                    code = BleErrorCode.SCAN_FAILED,
                    detail = "Android scan error=$errorCode",
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (mutableState.value.isScanning) return

        val missing = BlePermissionPolicy.missingPermissions(applicationContext)
        if (missing.isNotEmpty()) {
            mutableState.value = BleScanState(
                error = BleError(
                    BleErrorCode.PERMISSION_DENIED,
                    "missing=${missing.joinToString()}",
                ),
            )
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            mutableState.value = BleScanState(
                error = BleError(BleErrorCode.BLUETOOTH_UNAVAILABLE),
            )
            return
        }
        if (!adapter.isEnabled) {
            mutableState.value = BleScanState(
                error = BleError(BleErrorCode.BLUETOOTH_OFF),
            )
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            mutableState.value = BleScanState(
                error = BleError(BleErrorCode.BLUETOOTH_UNAVAILABLE, "bluetoothLeScanner=null"),
            )
            return
        }

        accumulator.clear()
        mutableState.value = BleScanState(isScanning = true)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (security: SecurityException) {
            mutableState.value = BleScanState(
                error = BleError(BleErrorCode.PERMISSION_DENIED, security.message),
            )
            return
        } catch (error: RuntimeException) {
            mutableState.value = BleScanState(
                error = BleError(BleErrorCode.SCAN_FAILED, error.message),
            )
            return
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(scanTimeoutMs)
            stopInternal(timedOut = true)
        }
    }

    override fun stop() {
        stopInternal(timedOut = false)
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal(timedOut: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = null

        val current = mutableState.value
        if (!current.isScanning) {
            if (timedOut) mutableState.value = current.copy(timedOut = true)
            return
        }

        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // State still transitions to stopped; permission state is refreshed by the caller.
        } catch (_: RuntimeException) {
            // Adapter may be turning off. Do not crash while tearing down a scan.
        }
        mutableState.value = current.copy(isScanning = false, timedOut = timedOut)
    }

    @SuppressLint("MissingPermission")
    private fun acceptResult(result: ScanResult) {
        if (!mutableState.value.isScanning) return

        val address = try {
            result.device.address
        } catch (_: SecurityException) {
            return
        }
        val name = try {
            result.scanRecord?.deviceName ?: result.device.name
        } catch (_: SecurityException) {
            result.scanRecord?.deviceName
        }
        val advertisesAe20 = result.scanRecord
            ?.serviceUuids
            ?.any { it.uuid == BleUuids.AE20_SERVICE }
            ?: false
        val normalizedName = name.orEmpty().lowercase()
        val candidate = advertisesAe20 ||
            normalizedName.contains("qs668") ||
            normalizedName.contains("cb08")

        val model = BleScanDevice(
            address = address,
            name = name,
            rssi = result.rssi,
            lastSeenElapsedMs = SystemClock.elapsedRealtime(),
            advertisesAe20 = advertisesAe20,
            likelyQs668 = candidate,
        )

        val snapshot = accumulator.upsert(model)
        mutableState.value = mutableState.value.copy(devices = snapshot)
    }
}
