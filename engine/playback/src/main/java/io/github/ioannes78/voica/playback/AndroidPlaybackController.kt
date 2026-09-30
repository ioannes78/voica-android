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
    private var appForeground = true
    private var resumeEligibleAfterTransientLoss = false
    private var released = false

    private val audioFocus = PlaybackAudioFocusManager(appContext) { change ->
        scope.launch { handleAudioFocusChange(change) }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                scope.launch { handleNoisyOutput() }
            }
        }
    }

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scope.launch { core.rebaseOutputPosition() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
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

    override suspend fun play() {
        environmentMutex.withLock {
            if (released) return
            if (!appForeground) return
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
            resumeEligibleAfterTransientLoss = false
            core.play()
        }
    }

    override suspend fun pause() {
        environmentMutex.withLock {
            if (released) return
            resumeEligibleAfterTransientLoss = false
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
            resumeEligibleAfterTransientLoss = false
            audioFocus.abandon()
            core.unload()
        }
    }

    override suspend fun release() {
        environmentMutex.withLock {
            if (released) return
            released = true
            resumeEligibleAfterTransientLoss = false
            audioFocus.abandon()
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
            audioFocus.audioManager.unregisterAudioDeviceCallback(routeCallback)
            core.release()
        }
    }

    suspend fun setAppForeground(foreground: Boolean) {
        environmentMutex.withLock {
            if (released) return
            appForeground = foreground
            if (!foreground) {
                resumeEligibleAfterTransientLoss = false
                core.pause()
                audioFocus.abandon()
            }
        }
    }

    private suspend fun handleNoisyOutput() {
        environmentMutex.withLock {
            if (released) return
            resumeEligibleAfterTransientLoss = false
            core.pause()
            audioFocus.abandon()
        }
    }

    private suspend fun handleAudioFocusChange(change: FocusChange) {
        environmentMutex.withLock {
            if (released) return
            when (change) {
                FocusChange.GAIN -> {
                    if (
                        resumeEligibleAfterTransientLoss &&
                        appForeground
                    ) {
                        resumeEligibleAfterTransientLoss = false
                        core.play()
                    }
                }
                FocusChange.LOSS_TRANSIENT -> {
                    resumeEligibleAfterTransientLoss =
                        core.snapshot.value.state == PlaybackState.PLAYING
                    core.pause()
                }
                FocusChange.LOSS,
                FocusChange.DUCK,
                -> {
                    resumeEligibleAfterTransientLoss = false
                    core.pause()
                    audioFocus.abandon()
                }
            }
        }
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
        .setOnAudioFocusChangeListener(listener)
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
