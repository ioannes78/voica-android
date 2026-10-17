package io.github.ioannes78.voica.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor

class AndroidAudioTrackSinkFactory : PlaybackAudioSinkFactory {
    override fun create(
        descriptor: PlaybackAudioSourceDescriptor,
    ): PlaybackAudioSink {
        require(descriptor.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
        require(descriptor.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
        require(descriptor.bitsPerSample == CanonicalPcmProfile.BITS_PER_SAMPLE)

        val sampleRate = requireNotNull(descriptor.sampleRateHz)
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack min buffer unavailable: $minBuffer" }
        val preferred = (minBuffer.toLong() * BUFFER_MULTIPLIER)
            .coerceAtMost(MAX_BUFFER_BYTES.toLong())
            .toInt()
        val bufferBytes = maxOf(minBuffer, preferred, MIN_BUFFER_BYTES)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "AudioTrack initialization failed"
        }
        return AndroidAudioTrackSink(
            track = track,
            bufferBytes = bufferBytes,
        )
    }

    private companion object {
        const val BUFFER_MULTIPLIER = 4L
        const val MIN_BUFFER_BYTES = 8 * 1024
        const val MAX_BUFFER_BYTES = 64 * 1024
    }
}

private class AndroidAudioTrackSink(
    private val track: AudioTrack,
    bufferBytes: Int,
) : PlaybackAudioSink {
    private val timestamp = AudioTimestamp()
    private val routeHandler = Handler(Looper.getMainLooper())
    private var closed = false

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        if (!closed) {
            val routed = (router as? AudioTrack)?.routedDevice ?: track.routedDevice
            PlaybackOutputDiagnostics.actualRoute(
                device = routed,
                reason = "TRACK_ROUTED",
            )
        }
    }

    init {
        PlaybackOutputDiagnostics.sinkCreated(
            trackState = trackStateLabel(track.state),
            sampleRateHz = track.sampleRate,
            channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
            encoding = "PCM16",
            bufferBytes = bufferBytes,
            initialRoute = track.routedDevice,
        )
        track.addOnRoutingChangedListener(routingListener, routeHandler)
    }

    override val underrunCount: Int
        get() = if (closed) 0 else track.underrunCount

    override fun play() {
        check(!closed)
        track.play()
        PlaybackOutputDiagnostics.actualRoute(
            device = track.routedDevice,
            reason = "PLAY_START",
        )
        PlaybackOutputDiagnostics.playState(
            sinkPhase = "ACTIVE",
            playState = playStateLabel(track.playState),
            trackState = trackStateLabel(track.state),
            underrunCount = track.underrunCount,
        )
    }

    override fun pause() {
        if (!closed && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
            track.pause()
            PlaybackOutputDiagnostics.playState(
                sinkPhase = "PAUSED",
                playState = playStateLabel(track.playState),
                trackState = trackStateLabel(track.state),
                underrunCount = track.underrunCount,
            )
        }
    }

    override fun flush() {
        check(!closed)
        track.flush()
        PlaybackOutputDiagnostics.playState(
            sinkPhase = "FLUSHED",
            playState = playStateLabel(track.playState),
            trackState = trackStateLabel(track.state),
            underrunCount = track.underrunCount,
        )
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(!closed)
        return try {
            val result = track.write(
                buffer,
                offset,
                length,
                AudioTrack.WRITE_NON_BLOCKING,
            )
            PlaybackOutputDiagnostics.writeResult(
                requestedBytes = length,
                result = result,
                underrunCount = track.underrunCount,
            )
            result
        } catch (error: Throwable) {
            PlaybackOutputDiagnostics.writeFailure(
                detail = error.message ?: error::class.java.simpleName,
                underrunCount = track.underrunCount,
            )
            throw error
        }
    }

    override fun setSpeed(speed: Float): Boolean {
        check(!closed)
        return runCatching {
            track.playbackParams = PlaybackParams()
                .setSpeed(speed)
                .setPitch(1.0f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            true
        }.getOrDefault(false)
    }

    override fun position(): PlaybackSinkPosition {
        check(!closed)
        if (track.getTimestamp(timestamp)) {
            val result = PlaybackSinkPosition(
                framePosition = timestamp.framePosition.coerceAtLeast(0L),
                timestampNanos = timestamp.nanoTime,
                timestampAvailable = true,
            )
            PlaybackOutputDiagnostics.position(
                framePosition = result.framePosition,
                timestampAvailable = true,
                underrunCount = track.underrunCount,
            )
            return result
        }
        val result = PlaybackSinkPosition(
            framePosition = track.playbackHeadPosition.toLong() and UINT32_MASK,
            timestampAvailable = false,
        )
        PlaybackOutputDiagnostics.position(
            framePosition = result.framePosition,
            timestampAvailable = false,
            underrunCount = track.underrunCount,
        )
        return result
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { track.removeOnRoutingChangedListener(routingListener) }
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
            PlaybackOutputDiagnostics.sinkClosed()
        }
    }

    private companion object {
        const val UINT32_MASK = 0xFFFF_FFFFL
    }
}

private fun trackStateLabel(state: Int): String =
    when (state) {
        AudioTrack.STATE_INITIALIZED -> "INITIALIZED"
        AudioTrack.STATE_UNINITIALIZED -> "UNINITIALIZED"
        else -> "STATE_$state"
    }

private fun playStateLabel(state: Int): String =
    when (state) {
        AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
        AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
        AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
        else -> "PLAYSTATE_$state"
    }
