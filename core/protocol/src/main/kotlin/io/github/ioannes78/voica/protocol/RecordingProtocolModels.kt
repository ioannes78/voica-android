package io.github.ioannes78.voica.protocol

sealed interface ProtocolDecodeResult<out T> {
    data class Success<T>(val value: T) : ProtocolDecodeResult<T>
    data class Malformed(val reason: String) : ProtocolDecodeResult<Nothing>
}

sealed interface RecordingStatus {
    data object Recording : RecordingStatus
    data object Idle : RecordingStatus
    data object Paused : RecordingStatus
    data class UnknownRaw(val rawValue: Int) : RecordingStatus
}

sealed interface RecordingGain {
    data object Low : RecordingGain
    data object Medium : RecordingGain
    data object High : RecordingGain
    data class UnknownRaw(val rawValue: Int) : RecordingGain
}

data class RecordingTimeInfo(
    val durationSeconds: Int,
    val currentSizeBytes: Long,
)

data class RecordingCommandResult(
    val rawCode: Int,
)
