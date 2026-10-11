package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RealtimeProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * Formal Stage 15 realtime transport adapter over the single AndroidDeviceSession/GATT owner.
 *
 * This adapter intentionally exposes no BluetoothGatt, characteristic UUID, MTU, or callback type.
 */
class AndroidDeviceSessionRealtimeTransport(
    private val session: AndroidDeviceSession,
) : RealtimeDeviceTransport {
    override val connectionState: StateFlow<DeviceConnectionState>
        get() = session.state

    override val currentGeneration: Long?
        get() {
            if (session.state.value !is DeviceConnectionState.Ready) return null
            return session.diagnostics.value.sessionId.takeIf { it > 0L }
        }

    override fun registerRealtimeAudioConsumer(consumer: RealtimeAudioConsumer): Boolean =
        session.registerRealtimeAudioConsumer(consumer)

    override fun unregisterRealtimeAudioConsumer(consumer: RealtimeAudioConsumer) {
        session.unregisterRealtimeAudioConsumer(consumer)
    }

    override suspend fun sendRealtimeCommand(
        command: RealtimeControlCommand,
    ): RealtimeTransportCommandResult {
        val generation = currentGeneration
        if (generation == null) {
            return RealtimeTransportCommandResult(
                command = command,
                transportGeneration = null,
                requestSequence = null,
                written = false,
                generationStillCurrent = false,
            )
        }

        val send = session.sendFileTransferRequest { sequence ->
            buildRealtimeCommandFrame(command, sequence)
        }
        return RealtimeTransportCommandResult(
            command = command,
            transportGeneration = generation,
            requestSequence = send.requestSequence,
            written = send.written,
            generationStillCurrent = currentGeneration == generation,
        )
    }
}

internal fun buildRealtimeCommandFrame(
    command: RealtimeControlCommand,
    sequence: Int,
): ByteArray =
    when (command) {
        RealtimeControlCommand.START -> RealtimeProtocol.buildStart(sequence)
        RealtimeControlCommand.PAUSE -> RealtimeProtocol.buildPause(sequence)
        RealtimeControlCommand.RESUME -> RealtimeProtocol.buildResume(sequence)
        RealtimeControlCommand.STOP -> RealtimeProtocol.buildStop(sequence)
    }
