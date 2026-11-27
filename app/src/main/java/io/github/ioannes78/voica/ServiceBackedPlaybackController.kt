package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.EvidencePlaybackExpectation
import io.github.ioannes78.voica.playback.AndroidPlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the existing sample-accurate playback engine as the single runtime while moving
 * background ownership to [PlaybackForegroundService]. UI and system media controls therefore
 * observe and control the same playback session instead of creating competing players.
 */
class ServiceBackedPlaybackController(
    context: Context,
    private val delegate: PlaybackController,
) : PlaybackController {
    private val appContext = context.applicationContext

    override val snapshot: StateFlow<PlaybackSnapshot> = delegate.snapshot
    override val stateFlow: StateFlow<PlaybackState> = delegate.stateFlow
    override val positionFlow: StateFlow<Long> = delegate.positionFlow

    override suspend fun load(recordingId: String) {
        delegate.load(recordingId)
    }

    /** Only the existing Android runtime can execute the verified transaction. */
    internal suspend fun playVerifiedEvidence(
        expected: EvidencePlaybackExpectation,
        requireCurrent: suspend () -> Boolean,
    ): Boolean {
        val runtime = delegate as? AndroidPlaybackController ?: return false
        // Start service only after the atomic command succeeds; starting it while
        // IDLE can cause the existing observer to stop itself during long hashing.
        val played = runtime.playVerifiedEvidence(expected, requireCurrent)
        if (played) PlaybackForegroundService.ensureStarted(appContext)
        return played
    }

    override suspend fun play() {
        PlaybackForegroundService.ensureStarted(appContext)
        delegate.play()
    }

    override suspend fun pause() {
        delegate.pause()
    }

    override suspend fun seekToSample(sampleIndex: Long) {
        delegate.seekToSample(sampleIndex)
    }

    override suspend fun setSpeed(speed: Float) {
        delegate.setSpeed(speed)
    }

    override suspend fun unload() {
        delegate.unload()
        PlaybackForegroundService.stop(appContext)
    }

    override suspend fun release() {
        delegate.release()
        PlaybackForegroundService.stop(appContext)
    }
}
