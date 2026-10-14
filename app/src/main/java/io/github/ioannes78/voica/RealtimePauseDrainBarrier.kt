package io.github.ioannes78.voica

import kotlinx.coroutines.delay

enum class RealtimePauseDrainStatus {
    QUIESCENT,
    NO_CANONICAL_MEDIA,
    INVALID_PHASE,
    TRANSPORT_CHANGED,
    AUDIO_GATE_REOPENED,
    AUDIO_ACCEPTED_AFTER_PAUSE,
    TIMED_OUT,
}

data class RealtimePausedCanonicalBoundary(
    val sessionId: Long,
    val epochId: Long,
    val endSampleIndexExclusive: Long,
    val transportGeneration: Long,
) {
    init {
        require(sessionId > 0L)
        require(epochId > 0L)
        require(endSampleIndexExclusive >= 0L)
        require(transportGeneration > 0L)
    }
}

data class RealtimePauseDrainOutcome(
    val status: RealtimePauseDrainStatus,
    val boundary: RealtimePausedCanonicalBoundary? = null,
    val acceptedBlocksAtPause: Long = 0L,
    val receivedBlocks: Long = 0L,
    val packetizedPackets: Long = 0L,
    val settledPackets: Long = 0L,
)

/**
 * Stage 16A pause barrier above the frozen Stage 15 media path.
 *
 * `RealtimeAudioRuntime.pause()` closes the generation-aware audio gate only after the device PAUSE
 * write succeeds. At that point no later realtime frame can enter the encoded ingress, but blocks
 * accepted just before the gate closed may still be inside the single-consumer decoder pipeline.
 * Finalizing ASR immediately would therefore risk cutting off already-accepted canonical media.
 *
 * This barrier captures the final accepted-block count after PAUSE, then waits until:
 *
 * 1. encoded ingress is empty;
 * 2. the canonical pipeline has received every accepted block;
 * 3. every packet already packetized by that pipeline has reached a terminal decode/validation
 *    outcome.
 *
 * Only then is the current canonical `(session, epoch, sampleIndex)` boundary returned. Wall clock is
 * used only as a bounded wait timeout; it never advances media time or fabricates samples.
 */
class RealtimePauseDrainBarrier(
    private val snapshotProvider: () -> RealtimeAudioRuntimeSnapshot,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val nowNanos: () -> Long = System::nanoTime,
) {
    suspend fun await(
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): RealtimePauseDrainOutcome {
        require(maxWaitMs > 0L)
        require(pollMs > 0L)

        val initial = snapshotProvider()
        if (initial.phase != RealtimeRuntimePhase.PAUSED) {
            return initial.outcome(RealtimePauseDrainStatus.INVALID_PHASE)
        }
        if (initial.fence.audioGateOpen) {
            return initial.outcome(RealtimePauseDrainStatus.AUDIO_GATE_REOPENED)
        }
        val generation = initial.transportGeneration
            ?: return initial.outcome(RealtimePauseDrainStatus.TRANSPORT_CHANGED)
        val acceptedAtPause = initial.ingress.acceptedBlocks
        val startedAt = nowNanos()
        val maxWaitNanos = maxWaitMs * NANOS_PER_MILLISECOND

        while (true) {
            val current = snapshotProvider()
            if (current.phase != RealtimeRuntimePhase.PAUSED) {
                return current.outcome(
                    status = RealtimePauseDrainStatus.INVALID_PHASE,
                    acceptedAtPause = acceptedAtPause,
                )
            }
            if (current.transportGeneration != generation) {
                return current.outcome(
                    status = RealtimePauseDrainStatus.TRANSPORT_CHANGED,
                    acceptedAtPause = acceptedAtPause,
                )
            }
            if (current.fence.audioGateOpen) {
                return current.outcome(
                    status = RealtimePauseDrainStatus.AUDIO_GATE_REOPENED,
                    acceptedAtPause = acceptedAtPause,
                )
            }
            if (current.ingress.acceptedBlocks != acceptedAtPause) {
                return current.outcome(
                    status = RealtimePauseDrainStatus.AUDIO_ACCEPTED_AFTER_PAUSE,
                    acceptedAtPause = acceptedAtPause,
                )
            }

            val pipeline = current.pipeline
            val settledPackets = pipeline.settledPacketCount()
            val drained =
                current.ingress.queueBlocks == 0 &&
                    pipeline.receivedBlocks >= acceptedAtPause &&
                    pipeline.packetizedPackets >= acceptedAtPause * EXPECTED_PACKETS_PER_BLOCK &&
                    settledPackets >= pipeline.packetizedPackets

            if (drained) {
                val source = current.source
                val sessionId = source.activeSessionId
                val epochId = source.activeEpochId
                if (!source.sessionActive || sessionId == null || epochId == null) {
                    return current.outcome(
                        status = RealtimePauseDrainStatus.NO_CANONICAL_MEDIA,
                        acceptedAtPause = acceptedAtPause,
                    )
                }
                return current.outcome(
                    status = RealtimePauseDrainStatus.QUIESCENT,
                    acceptedAtPause = acceptedAtPause,
                    boundary =
                        RealtimePausedCanonicalBoundary(
                            sessionId = sessionId,
                            epochId = epochId,
                            endSampleIndexExclusive = pipeline.nextSampleIndex,
                            transportGeneration = generation,
                        ),
                )
            }

            if ((nowNanos() - startedAt).coerceAtLeast(0L) >= maxWaitNanos) {
                return current.outcome(
                    status = RealtimePauseDrainStatus.TIMED_OUT,
                    acceptedAtPause = acceptedAtPause,
                )
            }
            sleeper(pollMs)
        }
    }

    private fun RealtimeAudioRuntimeSnapshot.outcome(
        status: RealtimePauseDrainStatus,
        acceptedAtPause: Long = ingress.acceptedBlocks,
        boundary: RealtimePausedCanonicalBoundary? = null,
    ): RealtimePauseDrainOutcome =
        RealtimePauseDrainOutcome(
            status = status,
            boundary = boundary,
            acceptedBlocksAtPause = acceptedAtPause,
            receivedBlocks = pipeline.receivedBlocks,
            packetizedPackets = pipeline.packetizedPackets,
            settledPackets = pipeline.settledPacketCount(),
        )

    private fun RealtimeCanonicalAudioPipelineSnapshot.settledPacketCount(): Long =
        decodeSuccess + decodeFailures + invalidOpusPackets + zeroSampleDecodeFailures

    private companion object {
        const val EXPECTED_PACKETS_PER_BLOCK = 4L
        const val DEFAULT_MAX_WAIT_MS = 1_500L
        const val DEFAULT_POLL_MS = 5L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
