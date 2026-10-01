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
    val bitsPerSample: Int? = null,
    val pcmDataOffsetBytes: Long? = null,
    val pcmDataSizeBytes: Long? = null,
    val bytesPerFrame: Int? = null,
    val totalSampleCount: Long? = null,
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

interface PcmSourceResolver {
    suspend fun resolvePcmSource(recordingId: String): PcmSource?
}

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

/**
 * Converts an absolute PCM sample index to media time using integer arithmetic.
 *
 * The returned value is floor(sampleIndex * 1_000_000 / sampleRateHz). Media
 * time is always derived from the absolute sample timeline, never the reverse.
 */
fun sampleIndexToTimeUs(sampleIndex: Long, sampleRateHz: Int): Long {
    require(sampleIndex >= 0L)
    require(sampleRateHz > 0)
    val wholeSeconds = sampleIndex / sampleRateHz
    val remainderSamples = sampleIndex % sampleRateHz
    return Math.addExact(
        Math.multiplyExact(wholeSeconds, 1_000_000L),
        remainderSamples * 1_000_000L / sampleRateHz,
    )
}

/**
 * Converts media time to an absolute PCM sample index using floor semantics.
 */
fun timeUsToSampleIndex(timeUs: Long, sampleRateHz: Int): Long {
    require(timeUs >= 0L)
    require(sampleRateHz > 0)
    val wholeSeconds = timeUs / 1_000_000L
    val remainderUs = timeUs % 1_000_000L
    return Math.addExact(
        Math.multiplyExact(wholeSeconds, sampleRateHz.toLong()),
        remainderUs * sampleRateHz / 1_000_000L,
    )
}

fun clampSampleIndex(sampleIndex: Long, totalSampleCount: Long): Long {
    require(totalSampleCount >= 0L)
    return sampleIndex.coerceIn(0L, totalSampleCount)
}

fun sampleIndexToPcmByteOffset(
    sampleIndex: Long,
    totalSampleCount: Long,
    pcmDataOffsetBytes: Long,
    bytesPerFrame: Int,
): Long {
    require(pcmDataOffsetBytes >= 0L)
    require(bytesPerFrame > 0)
    val clamped = clampSampleIndex(sampleIndex, totalSampleCount)
    return Math.addExact(
        pcmDataOffsetBytes,
        Math.multiplyExact(clamped, bytesPerFrame.toLong()),
    )
}
