package io.github.ioannes78.voica.protocol

object RealtimeProtocol {
    fun buildStart(sequence: Int): ByteArray =
        ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.REALTIME,
            command = ProtocolConstants.Realtime.START,
        )

    fun buildStop(sequence: Int): ByteArray =
        ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.REALTIME,
            command = ProtocolConstants.Realtime.STOP,
        )

    fun buildPause(sequence: Int): ByteArray =
        ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.REALTIME,
            command = ProtocolConstants.Realtime.PAUSE_RESUME,
            params = byteArrayOf(PAUSE_VALUE.toByte()),
        )

    fun buildResume(sequence: Int): ByteArray =
        ProtocolCodec.buildCommand(
            sequence = sequence,
            type = ProtocolConstants.Type.REALTIME,
            command = ProtocolConstants.Realtime.PAUSE_RESUME,
            params = byteArrayOf(RESUME_VALUE.toByte()),
        )

    const val RESUME_VALUE = 0
    const val PAUSE_VALUE = 1
}
