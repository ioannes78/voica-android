package io.github.ioannes78.voica.audio

/** Why the first frame of a canonical realtime epoch starts a fresh sample timeline. */
enum class RealtimeCanonicalEpochStartReason {
    SESSION_START,
    CONTROL_BOUNDARY,
    DISCONTINUITY,
    DECODER_RECOVERY,
}

/**
 * Canonical realtime PCM frame exposed above the transport/codec layer.
 *
 * Stage 16 consumers must treat [sessionId] and [epochId] as opaque identities. [startSampleIndex]
 * is epoch-local and is the only media-time truth inside an epoch. Unknown transport gaps are never
 * converted into synthetic PCM or wall-clock-derived sample offsets.
 */
data class RealtimeCanonicalPcmFrame(
    val sessionId: Long,
    val epochId: Long,
    val startSampleIndex: Long,
    val samples: ShortArray,
    val epochStartReason: RealtimeCanonicalEpochStartReason? = null,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(epochId > 0L) { "epochId must be positive" }
        require(startSampleIndex >= 0L) { "startSampleIndex must be non-negative" }
        require(samples.isNotEmpty()) { "canonical realtime frame must contain PCM samples" }
        if (startSampleIndex == 0L) {
            require(epochStartReason != null) {
                "the first canonical frame in an epoch must carry an epochStartReason"
            }
        }
    }

    val sampleCount: Int
        get() = samples.size

    val endSampleIndexExclusive: Long
        get() = Math.addExact(startSampleIndex, samples.size.toLong())

    val sampleRateHz: Int
        get() = CanonicalPcmProfile.SAMPLE_RATE_HZ

    val channelCount: Int
        get() = CanonicalPcmProfile.CHANNEL_COUNT
}
