package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.BatteryState
import io.github.ioannes78.voica.protocol.DeviceByteOrder

data class BleScanDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenElapsedMs: Long,
    val advertisesAe20: Boolean,
    val likelyQs668: Boolean,
)

data class BleScanState(
    val isScanning: Boolean = false,
    val devices: List<BleScanDevice> = emptyList(),
    val error: BleError? = null,
    val timedOut: Boolean = false,
)

enum class BleErrorCode {
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_OFF,
    SCAN_FAILED,
    SCAN_TIMEOUT,
    CONNECT_FAILED,
    CONNECT_TIMEOUT,
    REMOTE_DISCONNECTED,
    SERVICE_MISSING,
    CHARACTERISTIC_MISSING,
    CHARACTERISTIC_PROPERTY_MISMATCH,
    NOTIFICATION_ENABLE_FAILED,
    MTU_REQUEST_FAILED,
    MTU_TOO_SMALL,
    GATT_OPERATION_REJECTED,
    GATT_OPERATION_TIMEOUT,
    GATT_OPERATION_FAILED,
    WRITE_REJECTED,
    WRITE_FAILED,
    RESPONSE_TIMEOUT,
    PROTOCOL_DECODE_ERROR,
}

data class BleError(
    val code: BleErrorCode,
    val detail: String? = null,
    val recoverable: Boolean = true,
)

enum class DisconnectReason {
    USER,
    REMOTE,
    BLUETOOTH_OFF,
    PERMISSION_REVOKED,
    SETUP_FAILED,
}

enum class NotificationSource {
    AE22,
    AE23,
}

sealed interface DeviceConnectionState {
    data object Unavailable : DeviceConnectionState
    data class PermissionRequired(val permissions: Set<String>) : DeviceConnectionState
    data object BluetoothOff : DeviceConnectionState
    data object Idle : DeviceConnectionState
    data object Scanning : DeviceConnectionState
    data class Connecting(val address: String) : DeviceConnectionState
    data class LinkConnected(val address: String) : DeviceConnectionState
    data class DiscoveringServices(val address: String) : DeviceConnectionState
    data class Subscribing(val address: String, val source: NotificationSource) : DeviceConnectionState
    data class NegotiatingMtu(val address: String, val requestedMtu: Int) : DeviceConnectionState
    data class Ready(
        val address: String,
        val negotiatedMtu: Int,
        val capability: MtuCapability,
    ) : DeviceConnectionState
    data class Disconnecting(val address: String?) : DeviceConnectionState
    data class Disconnected(
        val address: String?,
        val reason: DisconnectReason,
        val gattStatus: Int? = null,
    ) : DeviceConnectionState
    data class ReconnectWaiting(
        val address: String,
        val attempt: Int,
        val delayMs: Long,
    ) : DeviceConnectionState
    data class Error(val error: BleError) : DeviceConnectionState
}

enum class MtuTier {
    UNSUPPORTED,
    CONTROL_ONLY,
    DATA_CHANNEL,
}

data class MtuCapability(
    val requestedMtu: Int,
    val negotiatedMtu: Int?,
    val tier: MtuTier,
    val atomic36Supported: Boolean,
    val data168Supported: Boolean,
) {
    val isReady: Boolean
        get() = atomic36Supported
}

object MtuPolicy {
    const val REQUESTED_ATT_MTU = 517
    const val MINIMUM_ATOMIC_36_ATT_MTU = 39
    const val MINIMUM_DATA_168_ATT_MTU = 171

    fun evaluate(negotiatedMtu: Int?): MtuCapability {
        val atomic = negotiatedMtu != null && negotiatedMtu >= MINIMUM_ATOMIC_36_ATT_MTU
        val data = negotiatedMtu != null && negotiatedMtu >= MINIMUM_DATA_168_ATT_MTU
        val tier = when {
            data -> MtuTier.DATA_CHANNEL
            atomic -> MtuTier.CONTROL_ONLY
            else -> MtuTier.UNSUPPORTED
        }
        return MtuCapability(
            requestedMtu = REQUESTED_ATT_MTU,
            negotiatedMtu = negotiatedMtu,
            tier = tier,
            atomic36Supported = atomic,
            data168Supported = data,
        )
    }
}

data class DeviceInfo(
    val name: String? = null,
    val address: String? = null,
    val battery: BatteryState = BatteryState.Unknown(null),
    val remainingKb: Long? = null,
    val totalKb: Long? = null,
    val capacityByteOrder: DeviceByteOrder? = null,
    val firmwareVersion: String? = null,
    val authAvailable: Boolean = false,
    val authDisplay: String? = null,
    val negotiatedMtu: Int? = null,
)

data class GattShapeSnapshot(
    val ae20Found: Boolean = false,
    val ae21Found: Boolean = false,
    val ae21Properties: Int? = null,
    val ae22Found: Boolean = false,
    val ae22Properties: Int? = null,
    val ae22CccdFound: Boolean = false,
    val ae23Found: Boolean = false,
    val ae23Properties: Int? = null,
    val ae23CccdFound: Boolean = false,
)

data class NotificationStats(
    val ae22Frames: Int = 0,
    val ae23Frames: Int = 0,
    val ae22CrcErrors: Int = 0,
    val ae23CrcErrors: Int = 0,
    val ae22InvalidLengths: Int = 0,
    val ae23InvalidLengths: Int = 0,
    val unknownCharacteristicNotifications: Int = 0,
)

data class GattQueueSnapshot(
    val activeOperation: String? = null,
    val waitingCount: Int = 0,
    val closed: Boolean = false,
)

data class BleDiagnostics(
    val sessionId: Long = 0,
    val gattStage: String = "Idle",
    val requestedMtu: Int = MtuPolicy.REQUESTED_ATT_MTU,
    val negotiatedMtu: Int? = null,
    val mtuCapability: MtuCapability = MtuPolicy.evaluate(null),
    val shape: GattShapeSnapshot = GattShapeSnapshot(),
    val ae22Subscribed: Boolean = false,
    val ae23Subscribed: Boolean = false,
    val queue: GattQueueSnapshot = GattQueueSnapshot(),
    val lastGattStatus: Int? = null,
    val lastError: BleError? = null,
    val reconnectAttempt: Int = 0,
    val notifications: NotificationStats = NotificationStats(),
    val lastTxType: Int? = null,
    val lastTxCommand: Int? = null,
    val lastTxSequence: Int? = null,
    val logs: List<String> = emptyList(),
)
