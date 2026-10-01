package io.github.ioannes78.voica.audio

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState {
    IDLE,
    PREPARING,
    READY,
    PLAYING,
    PAUSED,
    SEEKING,
    COMPLETED,
    ERROR,
    RELEASED,
}

enum class PlaybackErrorCategory {
    SOURCE,
    OUTPUT,
    AUDIO_FOCUS,
    SPEED,
    INTERNAL,
}

enum class PlaybackErrorCode {
    SOURCE_NOT_AVAILABLE,
    SOURCE_INTEGRITY_FAILED,
    INVALID_CANONICAL_WAV,
    AUDIO_TRACK_INIT_FAILED,
    AUDIO_FOCUS_DENIED,
    AUDIO_READ_FAILED,
    PLAYBACK_SPEED_UNSUPPORTED,
    OUTPUT_ROUTE_FAILED,
    SOURCE_REMOVED,
    INTERNAL_STATE_ERROR,
}

data class PlaybackError(
    val code: PlaybackErrorCode,
    val category: PlaybackErrorCategory,
    val recoverable: Boolean,
    val diagnosticDetail: String? = null,
)

data class PlaybackSnapshot(
    val recordingId: String? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val positionSampleIndex: Long = 0L,
    val positionUs: Long = 0L,
    val durationSampleCount: Long = 0L,
    val durationUs: Long = 0L,
    val speed: Float = 1.0f,
    val seekGeneration: Long = 0L,
    val discontinuityGeneration: Long = 0L,
    val error: PlaybackError? = null,
)

interface PlaybackController {
    val snapshot: StateFlow<PlaybackSnapshot>
    val stateFlow: StateFlow<PlaybackState>
    val positionFlow: StateFlow<Long>

    suspend fun load(recordingId: String)
    suspend fun play()
    suspend fun pause()
    suspend fun seekToSample(sampleIndex: Long)
    suspend fun setSpeed(speed: Float)
    suspend fun unload()
    suspend fun release()
}
