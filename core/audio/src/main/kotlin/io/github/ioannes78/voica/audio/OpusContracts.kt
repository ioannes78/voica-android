package io.github.ioannes78.voica.audio

import java.io.Closeable

data class OpusPacketInfo(
    val valid: Boolean,
    val errorCode: Int? = null,
    val toc: Int? = null,
    val channels: Int? = null,
    val frameCount: Int? = null,
    val samplesPerFrame48k: Int? = null,
    val totalSamples48k: Int? = null,
    val bandwidth: Int? = null,
    val payloadOffsetBytes: Int? = null,
    val minFrameBytes: Int? = null,
    val maxFrameBytes: Int? = null,
)

interface OpusPacketInspector {
    fun inspect(packet: ByteArray): OpusPacketInfo
}

interface OpusDecoder : Closeable {
    val sampleRateHz: Int
    val channelCount: Int

    /**
     * Decodes one complete Opus packet into interleaved PCM16.
     * Returned sample count is total shorts, not frames-per-channel.
     */
    fun decode(packet: ByteArray): ShortArray

    fun reset()
}

interface OpusDecoderFactory {
    val version: String

    fun create(
        sampleRateHz: Int,
        channelCount: Int,
    ): OpusDecoder
}
