package io.github.ioannes78.voica.audio

import java.io.Closeable

enum class CanonicalAudioStage {
    DECODING,
    NORMALIZING,
    WRITING,
    VERIFYING,
}

object CanonicalPcmProfile {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_SECOND =
        SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
    const val PROFILE_ID = "CANONICAL_PCM16_16000_MONO_WAV_V1"
}

enum class AudioContainerKind {
    WAV,
    OGG_OPUS,
    DEVICE_RAW_CANDIDATE,
    UNKNOWN,
}

data class PlaybackAudioSourceDescriptor(
    val recordingId: String,
    val assetId: String,
    val container: AudioContainerKind,
    val durationUs: Long?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val seekable: Boolean,
    val lengthBytes: Long,
)

interface SeekableAudioHandle : Closeable {
    val lengthBytes: Long

    fun readAt(
        offset: Long,
        target: ByteArray,
        targetOffset: Int = 0,
        length: Int = target.size - targetOffset,
    ): Int
}

data class PlaybackAudioSource(
    val descriptor: PlaybackAudioSourceDescriptor,
    val handle: SeekableAudioHandle,
)

interface AudioSourceResolver {
    suspend fun resolvePlaybackSource(recordingId: String): PlaybackAudioSource?
}

data class PcmReadResult(
    val startSampleIndex: Long,
    val sampleCount: Int,
)

interface PcmSource : Closeable {
    val sampleRateHz: Int
    val channelCount: Int

    /**
     * Reads signed PCM16 little-endian samples into [target].
     * The canonical Stage 8 contract is 16 kHz / mono, so one Short is one timeline sample.
     */
    suspend fun read(
        target: ShortArray,
        targetOffset: Int = 0,
        maxSamples: Int = target.size - targetOffset,
    ): PcmReadResult?
}

fun sampleIndexToTimeUs(sampleIndex: Long, sampleRateHz: Int): Long {
    require(sampleIndex >= 0L)
    require(sampleRateHz > 0)
    return sampleIndex * 1_000_000L / sampleRateHz
}
