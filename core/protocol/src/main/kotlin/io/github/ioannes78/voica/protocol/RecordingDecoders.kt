package io.github.ioannes78.voica.protocol

object RecordingDecoders {
    fun decodeStatus(body: ByteArray): ProtocolDecodeResult<RecordingStatus> {
        val raw = body.firstOrNull()?.toInt()?.and(0xFF)
            ?: return ProtocolDecodeResult.Malformed("STATE_RESPONSE body 为空")
        val value = when (raw) {
            ProtocolConstants.RecordingStateValue.RECORDING -> RecordingStatus.Recording
            ProtocolConstants.RecordingStateValue.IDLE -> RecordingStatus.Idle
            ProtocolConstants.RecordingStateValue.PAUSED -> RecordingStatus.Paused
            else -> RecordingStatus.UnknownRaw(raw)
        }
        return ProtocolDecodeResult.Success(value)
    }

    fun decodeTime(body: ByteArray): ProtocolDecodeResult<RecordingTimeInfo> {
        if (body.size < 6) {
            return ProtocolDecodeResult.Malformed(
                "TIME_RESPONSE 需要至少 6 字节，实际 ${body.size}",
            )
        }
        return ProtocolDecodeResult.Success(
            RecordingTimeInfo(
                durationSeconds = ByteCodec.readU16Le(body, 0),
                currentSizeBytes = ByteCodec.readU32Le(body, 2),
            ),
        )
    }

    fun decodeFilename(body: ByteArray): ProtocolDecodeResult<String> {
        val end = body.indexOf(0).let { if (it < 0) body.size else it }
        val filename = body.copyOfRange(0, end).toString(Charsets.UTF_8).trim()
        return ProtocolDecodeResult.Success(filename)
    }

    fun decodeGain(body: ByteArray): ProtocolDecodeResult<RecordingGain> {
        val raw = body.firstOrNull()?.toInt()?.and(0xFF)
            ?: return ProtocolDecodeResult.Malformed("GAIN_RESPONSE body 为空")
        val value = when (raw) {
            ProtocolConstants.RecordingGainValue.LOW -> RecordingGain.Low
            ProtocolConstants.RecordingGainValue.MEDIUM -> RecordingGain.Medium
            ProtocolConstants.RecordingGainValue.HIGH -> RecordingGain.High
            else -> RecordingGain.UnknownRaw(raw)
        }
        return ProtocolDecodeResult.Success(value)
    }

    fun decodeCommandResult(body: ByteArray): ProtocolDecodeResult<RecordingCommandResult> {
        val raw = body.firstOrNull()?.toInt()?.and(0xFF)
            ?: return ProtocolDecodeResult.Malformed("命令响应 body 为空")
        return ProtocolDecodeResult.Success(RecordingCommandResult(raw))
    }
}
