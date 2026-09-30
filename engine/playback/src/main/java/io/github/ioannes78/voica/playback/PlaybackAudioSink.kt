package io.github.ioannes78.voica.playback

import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import java.io.Closeable

data class PlaybackSinkPosition(
    val framePosition: Long,
    val timestampNanos: Long? = null,
    val timestampAvailable: Boolean = false,
)

interface PlaybackAudioSink : Closeable {
    val underrunCount: Int

    fun play()
    fun pause()
    fun flush()
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
    fun setSpeed(speed: Float): Boolean
    fun position(): PlaybackSinkPosition

    override fun close()
}

fun interface PlaybackAudioSinkFactory {
    fun create(descriptor: PlaybackAudioSourceDescriptor): PlaybackAudioSink
}
