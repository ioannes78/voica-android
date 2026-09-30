package io.github.ioannes78.voica.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor

class AndroidAudioTrackSinkFactory : PlaybackAudioSinkFactory {
    override fun create(
        descriptor: PlaybackAudioSourceDescriptor,
    ): PlaybackAudioSink {
        require(descriptor.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
        require(descriptor.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
        require(descriptor.bitsPerSample == CanonicalPcmProfile.BITS_PER_SAMPLE)

        val minBuffer = AudioTrack.getMinBufferSize(
            descriptor.sampleRateHz,
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
                    .setSampleRate(descriptor.sampleRateHz)
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
        return AndroidAudioTrackSink(track)
    }

    private companion object {
        const val BUFFER_MULTIPLIER = 4L
        const val MIN_BUFFER_BYTES = 8 * 1024
        const val MAX_BUFFER_BYTES = 64 * 1024
    }
}

private class AndroidAudioTrackSink(
    private val track: AudioTrack,
) : PlaybackAudioSink {
    private val timestamp = AudioTimestamp()
    private var closed = false

    override val underrunCount: Int
        get() = if (closed) 0 else track.underrunCount

    override fun play() {
        check(!closed)
        track.play()
    }

    override fun pause() {
        if (!closed && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
            track.pause()
        }
    }

    override fun flush() {
        check(!closed)
        track.flush()
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(!closed)
        return track.write(
            buffer,
            offset,
            length,
            AudioTrack.WRITE_NON_BLOCKING,
        )
    }

    override fun setSpeed(speed: Float): Boolean {
        check(!closed)
        return runCatching {
            track.playbackParams = PlaybackParams()
                .setSpeed(speed)
                .setPitch(1.0f)
            true
        }.getOrDefault(false)
    }

    override fun position(): PlaybackSinkPosition {
        check(!closed)
        if (track.getTimestamp(timestamp)) {
            return PlaybackSinkPosition(
                framePosition = timestamp.framePosition.coerceAtLeast(0L),
                timestampNanos = timestamp.nanoTime,
                timestampAvailable = true,
            )
        }
        return PlaybackSinkPosition(
            framePosition = track.playbackHeadPosition.toLong() and UINT32_MASK,
            timestampAvailable = false,
        )
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private companion object {
        const val UINT32_MASK = 0xFFFF_FFFFL
    }
}
