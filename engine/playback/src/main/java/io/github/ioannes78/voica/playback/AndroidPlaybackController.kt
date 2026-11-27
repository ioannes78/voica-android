package io.github.ioannes78.voica.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.EvidencePlaybackExpectation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackError
import io.github.ioannes78.voica.audio.PlaybackErrorCategory
import io.github.ioannes78.voica.audio.PlaybackErrorCode
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidPlaybackController(
    context: Context,
    sourceResolver: AudioSourceResolver,
    private val scope: CoroutineScope,
) : PlaybackController {
    private val appContext = context.applicationContext
    private val environmentMutex = Mutex()
    private val core = StreamingPlaybackController(
        sourceResolver = sourceResolver,
        sinkFactory = AndroidAudioTrackSinkFactory(),
        scope = scope,
    )
    private val focusPolicy = PlaybackFocusPolicy()
    private var released = false
    private val interruptionEpoch = AtomicLong(0L)

    private val audioFocus = PlaybackAudioFocusManager(appContext) { change ->
        if (change != FocusChange.GAIN) interruptionEpoch.incrementAndGet()
        scope.launch { handleAudioFocusChange(change) }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                interruptionEpoch.incrementAndGet()
                PlaybackOutputDiagnostics.environmentEvent("BECOMING_NOISY")
                scope.launch { handleNoisyOutput() }
            }
        }
    }

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            PlaybackOutputDiagnostics.environmentEvent(
                buildOutputDeviceEnvironmentEvent(
                    prefix = "OUTPUT_DEVICE_ADDED",
                    devices = addedDevices,
                ),
            )
            scope.launch { core.rebaseOutputPosition() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            PlaybackOutputDiagnostics.environmentEvent(
                buildOutputDeviceEnvironmentEvent(
                    prefix = "OUTPUT_DEVICE_REMOVED",
                    devices = removedDevices,
                ),
            )
            scope.launch { core.rebaseOutputPosition() }
        }
    }

    override val snapshot: StateFlow<PlaybackSnapshot> = core.snapshot
    override val stateFlow: StateFlow<PlaybackState> = core.stateFlow
    override val positionFlow: StateFlow<Long> = core.positionFlow

    init {
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        audioFocus.audioManager.registerAudioDeviceCallback(
            routeCallback,
            Handler(Looper.getMainLooper()),
        )
    }

    override suspend fun load(recordingId: String) {
        core.load(recordingId)
    }

    /** Dedicated evidence command; regular playback APIs remain unchanged. */
    suspend fun playVerifiedEvidence(
        expected: EvidencePlaybackExpectation,
        requireCurrent: suspend () -> Boolean,
    ): Boolean = environmentMutex.withLock {
        if (released) return@withLock false
        val epoch = interruptionEpoch.get()
        val previousPlaying = core.snapshot.value.state == PlaybackState.PLAYING
        if (!audioFocus.request()) return@withLock false
        focusPolicy.cancelResume()
        var success = false
        try {
            success = core.playVerifiedEvidence(expected) {
                interruptionEpoch.get() == epoch && requireCurrent() &&
                    interruptionEpoch.get() == epoch
            }
            success
        } finally {
            if (!success) withContext(NonCancellable) {
                // Failed preflight can retain a previously playing session and its focus.
                if (!previousPlaying || core.snapshot.value.state != PlaybackState.PLAYING) {
                    audioFocus.abandon()
                }
            }
        }
    }

    override suspend fun play() {
        environmentMutex.withLock {
            if (released) return
            if (
                core.snapshot.value.state !in
                    setOf(
                        PlaybackState.READY,
                        PlaybackState.PAUSED,
                        PlaybackState.COMPLETED,
                    )
            ) {
                return
            }
            if (!audioFocus.request()) {
                core.reportRecoverableError(
                    PlaybackError(
                        code = PlaybackErrorCode.AUDIO_FOCUS_DENIED,
                        category = PlaybackErrorCategory.AUDIO_FOCUS,
                        recoverable = true,
                        diagnosticDetail = "audio focus request denied",
                    ),
                )
                return
            }
            focusPolicy.cancelResume()
            core.play()
        }
    }

    override suspend fun pause() {
        environmentMutex.withLock {
            if (released) return
            focusPolicy.cancelResume()
            core.pause()
            audioFocus.abandon()
        }
    }

    override suspend fun seekToSample(sampleIndex: Long) {
        core.seekToSample(sampleIndex)
    }

    override suspend fun setSpeed(speed: Float) {
        core.setSpeed(speed)
    }

    override suspend fun unload() {
        environmentMutex.withLock {
            if (released) return
            focusPolicy.cancelResume()
            audioFocus.abandon()
            core.unload()
        }
    }

    override suspend fun release() {
        environmentMutex.withLock {
            if (released) return
            released = true
            focusPolicy.cancelResume()
            audioFocus.abandon()
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
            audioFocus.audioManager.unregisterAudioDeviceCallback(routeCallback)
            core.release()
        }
    }

    private suspend fun handleNoisyOutput() {
        environmentMutex.withLock {
            if (released) return
            focusPolicy.cancelResume()
            core.pause()
            audioFocus.abandon()
        }
    }

    private suspend fun handleAudioFocusChange(change: FocusChange) {
        environmentMutex.withLock {
            if (released) return
            when (change) {
                FocusChange.GAIN -> {
                    if (focusPolicy.consumeResumeOnGain()) {
                        core.play()
                    }
                }
                FocusChange.LOSS_TRANSIENT,
                FocusChange.DUCK,
                -> {
                    focusPolicy.onTransientLoss(
                        wasPlaying =
                            core.snapshot.value.state == PlaybackState.PLAYING,
                    )
                    core.pause()
                }
                FocusChange.LOSS -> {
                    focusPolicy.cancelResume()
                    core.pause()
                    audioFocus.abandon()
                }
            }
        }
    }
}

private fun buildOutputDeviceEnvironmentEvent(
    prefix: String,
    devices: Array<out AudioDeviceInfo>?,
): String {
    val outputs = devices.orEmpty().filter { it.isSink }
    if (outputs.isEmpty()) return "$prefix:none"
    return prefix + ":" + outputs.joinToString(",") { device ->
        "${device.id}/${playbackOutputDeviceTypeLabel(device.type)}"
    }
}

private enum class FocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    DUCK,
}

private class PlaybackAudioFocusManager(
    context: Context,
    private val onChange: (FocusChange) -> Unit,
) {
    val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val listener = AudioManager.OnAudioFocusChangeListener { value ->
        val mapped = when (value) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                FocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                FocusChange.DUCK
            else -> null
        }
        if (mapped != null) onChange(mapped)
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener)
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
