package io.github.ioannes78.voica.ble

import kotlinx.coroutines.flow.StateFlow

enum class RealtimeControlCommand {
    START,
    PAUSE,
    RESUME,
    STOP,
}

data class RealtimeTransportCommandResult(
    val command: RealtimeControlCommand,
    val transportGeneration: Long?,
    val requestSequence: Int?,
    val written: Boolean,
    val generationStillCurrent: Boolean,
) {
    val committed: Boolean
        get() = written && transportGeneration != null && generationStillCurrent
}

/**
 * Narrow Stage 15 realtime transport seam.
 *
 * Implementations may own Android/BLE details, but consumers of this contract must not depend on
 * BluetoothGatt, characteristic UUIDs, MTU internals, or protocol callback types.
 */
interface RealtimeDeviceTransport {
    val connectionState: StateFlow<DeviceConnectionState>
    val currentGeneration: Long?

    fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean

    fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer)

    suspend fun sendRealtimeCommand(command: RealtimeControlCommand): RealtimeTransportCommandResult
}
