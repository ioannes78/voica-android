package io.github.ioannes78.voica.audio

import java.io.Closeable
import kotlinx.coroutines.channels.Channel

enum class StreamingAudioDiscontinuityReason {
    UPSTREAM_BACKPRESSURE,
    BUFFER_OVERFLOW,
}

/** Stage 16 normalized media event, still anchored to Stage 15 canonical identities. */
sealed interface StreamingAudioEvent {
    val sessionId: Long

    data class SessionStarted(
        override val sessionId: Long,
    ) : StreamingAudioEvent

    data class EpochStarted(
        override val sessionId: Long,
        val epochId: Long,
        val reason: RealtimeCanonicalEpochStartReason,
    ) : StreamingAudioEvent

    data class PcmFrame(
        val frame: RealtimeCanonicalPcmFrame,
    ) : StreamingAudioEvent {
        override val sessionId: Long
            get() = frame.sessionId
    }

    data class Discontinuity(
        override val sessionId: Long,
        val epochId: Long,
        val resumeAtSampleIndex: Long,
        val reason: StreamingAudioDiscontinuityReason,
        val droppedFrames: Long,
        val droppedSamples: Long,
    ) : StreamingAudioEvent

    data class SessionEnded(
        override val sessionId: Long,
        val finalEpochId: Long?,
        val finalSampleIndexExclusive: Long,
        val reason: RealtimeCanonicalSessionEndReason,
        val drainTimedOut: Boolean,
    ) : StreamingAudioEvent
}

enum class StreamingAudioBufferOfferResult {
    ACCEPTED,
    ACCEPTED_AFTER_DISCONTINUITY,
    REJECTED_AFTER_FINISH,
    REJECTED_OVERSIZED_FRAME,
}

data class StreamingAudioBufferSnapshot(
    val inputFinished: Boolean,
    val queuedPcmFrames: Int,
    val queuedPcmSamples: Int,
    val peakQueuedPcmFrames: Int,
    val peakQueuedPcmSamples: Int,
    val offeredPcmFrames: Long,
    val offeredPcmSamples: Long,
    val upstreamDiscontinuities: Long,
    val overflowEvents: Long,
    val droppedFrames: Long,
    val droppedSamples: Long,
    val rejectedAfterFinish: Long,
    val rejectedOversizedFrames: Long,
    val overflowEpisodes: Long = 0L,
    val maxConsecutiveDroppedFrames: Long = 0L,
    val maxConsecutiveDroppedSamples: Long = 0L,
)

/**
 * Engine/provider-independent bounded Stage 16 PCM buffer.
 *
 * PCM is bounded independently from low-volume lifecycle events, mirroring the Stage 15 canonical
 * seam. When a slow Stage 16 consumer reaches the hard PCM limit, the oldest PCM is dropped while a
 * single pending overflow discontinuity is moved forward and widened to cover the whole contiguous
 * loss episode. The first drop also creates a small hysteresis margin (75% recovery watermark), so
 * the consumer can resume without entering a one-frame-drop / one-finalize feedback loop. Once the
 * consumer drains below that watermark, later pressure starts a new episode. No silence or
 * wall-clock-derived samples are synthesized.
 */
class StreamingAudioBuffer(
    private val maxQueuedPcmFrames: Int,
    private val maxQueuedPcmSamples: Int,
) : Closeable {
    init {
        require(maxQueuedPcmFrames > 0) { "maxQueuedPcmFrames must be positive" }
        require(maxQueuedPcmSamples > 0) { "maxQueuedPcmSamples must be positive" }
    }

    private val overflowRecoveryPcmFrames = maxOf(1, maxQueuedPcmFrames * 3 / 4)
    private val overflowRecoveryPcmSamples = maxOf(1, maxQueuedPcmSamples * 3 / 4)

    private val lock = Any()
    private val queue = ArrayDeque<StreamingAudioEvent>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private var inputFinished = false
    private var closed = false
    private var queuedPcmFrames = 0
    private var queuedPcmSamples = 0
    private var peakQueuedPcmFrames = 0
    private var peakQueuedPcmSamples = 0
    private var offeredPcmFrames = 0L
    private var offeredPcmSamples = 0L
    private var upstreamDiscontinuities = 0L
    private var overflowEvents = 0L
    private var overflowEpisodes = 0L
    private var overflowEpisodeActive = false
    private var currentOverflowEpisodeDroppedFrames = 0L
    private var currentOverflowEpisodeDroppedSamples = 0L
    private var maxConsecutiveDroppedFrames = 0L
    private var maxConsecutiveDroppedSamples = 0L
    private var droppedFrames = 0L
    private var droppedSamples = 0L
    private var rejectedAfterFinish = 0L
    private var rejectedOversizedFrames = 0L

    fun offer(event: StreamingAudioEvent): StreamingAudioBufferOfferResult {
        var shouldSignal = false
        val result = synchronized(lock) {
            if (inputFinished || closed) {
                rejectedAfterFinish += 1
                return@synchronized StreamingAudioBufferOfferResult.REJECTED_AFTER_FINISH
            }

            if (event !is StreamingAudioEvent.PcmFrame) {
                if (event is StreamingAudioEvent.Discontinuity &&
                    event.reason == StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE
                ) {
                    upstreamDiscontinuities += 1
                }
                queue.addLast(event)
                shouldSignal = true
                return@synchronized StreamingAudioBufferOfferResult.ACCEPTED
            }

            val frame = event.frame
            if (frame.sampleCount > maxQueuedPcmSamples) {
                rejectedOversizedFrames += 1
                return@synchronized StreamingAudioBufferOfferResult.REJECTED_OVERSIZED_FRAME
            }

            val wouldOverflow =
                queuedPcmFrames + 1 > maxQueuedPcmFrames ||
                    queuedPcmSamples + frame.sampleCount > maxQueuedPcmSamples

            val recoveredOverflow = if (wouldOverflow) {
                overflowEvents += 1
                val pendingGap = findPendingOverflowGapLocked(frame.sessionId, frame.epochId)
                val startsEpisode = !overflowEpisodeActive || pendingGap == null
                if (startsEpisode) {
                    beginOverflowEpisodeLocked()
                }
                val dropped =
                    dropOldestPcmUntilFitsLocked(
                        incoming = frame,
                        pendingGap = pendingGap,
                        recoverToWatermark = startsEpisode,
                    )
                noteOverflowDropLocked(dropped.newlyDroppedFrames, dropped.newlyDroppedSamples)
                true
            } else {
                false
            }

            queue.addLast(event)
            queuedPcmFrames += 1
            queuedPcmSamples += frame.sampleCount
            peakQueuedPcmFrames = maxOf(peakQueuedPcmFrames, queuedPcmFrames)
            peakQueuedPcmSamples = maxOf(peakQueuedPcmSamples, queuedPcmSamples)
            offeredPcmFrames += 1
            offeredPcmSamples += frame.sampleCount
            shouldSignal = true

            if (recoveredOverflow) {
                StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY
            } else {
                StreamingAudioBufferOfferResult.ACCEPTED
            }
        }

        if (shouldSignal) signal.trySend(Unit)
        return result
    }

    /** Marks the producer finished while allowing already queued events to drain in order. */
    fun finishInput() {
        val shouldSignal = synchronized(lock) {
            if (inputFinished || closed) return
            inputFinished = true
            true
        }
        if (shouldSignal) signal.trySend(Unit)
    }

    suspend fun receive(): StreamingAudioEvent? {
        while (true) {
            synchronized(lock) {
                removeFirstLocked()?.let { return it }
                if (inputFinished || closed) return null
            }
            if (signal.receiveCatching().getOrNull() == null) return null
        }
    }

    fun snapshot(): StreamingAudioBufferSnapshot = synchronized(lock) {
        StreamingAudioBufferSnapshot(
            inputFinished = inputFinished,
            queuedPcmFrames = queuedPcmFrames,
            queuedPcmSamples = queuedPcmSamples,
            peakQueuedPcmFrames = peakQueuedPcmFrames,
            peakQueuedPcmSamples = peakQueuedPcmSamples,
            offeredPcmFrames = offeredPcmFrames,
            offeredPcmSamples = offeredPcmSamples,
            upstreamDiscontinuities = upstreamDiscontinuities,
            overflowEvents = overflowEvents,
            droppedFrames = droppedFrames,
            droppedSamples = droppedSamples,
            rejectedAfterFinish = rejectedAfterFinish,
            rejectedOversizedFrames = rejectedOversizedFrames,
            overflowEpisodes = overflowEpisodes,
            maxConsecutiveDroppedFrames = maxConsecutiveDroppedFrames,
            maxConsecutiveDroppedSamples = maxConsecutiveDroppedSamples,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            inputFinished = true
            queue.clear()
            queuedPcmFrames = 0
            queuedPcmSamples = 0
            overflowEpisodeActive = false
        }
        signal.close()
    }

    private fun removeFirstLocked(): StreamingAudioEvent? {
        if (queue.isEmpty()) return null
        val event = queue.removeFirst()
        if (event is StreamingAudioEvent.PcmFrame) {
            queuedPcmFrames -= 1
            queuedPcmSamples -= event.frame.sampleCount
            if (
                overflowEpisodeActive &&
                queuedPcmFrames <= overflowRecoveryPcmFrames &&
                queuedPcmSamples <= overflowRecoveryPcmSamples
            ) {
                overflowEpisodeActive = false
                currentOverflowEpisodeDroppedFrames = 0L
                currentOverflowEpisodeDroppedSamples = 0L
            }
        }
        return event
    }

    private fun beginOverflowEpisodeLocked() {
        overflowEpisodeActive = true
        overflowEpisodes += 1
        currentOverflowEpisodeDroppedFrames = 0L
        currentOverflowEpisodeDroppedSamples = 0L
    }

    private fun noteOverflowDropLocked(frames: Long, samples: Long) {
        currentOverflowEpisodeDroppedFrames += frames
        currentOverflowEpisodeDroppedSamples += samples
        maxConsecutiveDroppedFrames =
            maxOf(maxConsecutiveDroppedFrames, currentOverflowEpisodeDroppedFrames)
        maxConsecutiveDroppedSamples =
            maxOf(maxConsecutiveDroppedSamples, currentOverflowEpisodeDroppedSamples)
        droppedFrames += frames
        droppedSamples += samples
    }

    private fun findPendingOverflowGapLocked(
        sessionId: Long,
        epochId: Long,
    ): StreamingAudioEvent.Discontinuity? =
        queue.firstOrNull { event ->
            event is StreamingAudioEvent.Discontinuity &&
                event.reason == StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW &&
                event.sessionId == sessionId &&
                event.epochId == epochId
        } as? StreamingAudioEvent.Discontinuity

    /**
     * Removes oldest PCM while preserving lifecycle ordering. A first overflow drops only enough to
     * reach the recovery watermark; subsequent hard-limit hits before the pending gap is consumed
     * extend that same gap. This keeps one expensive ASR/VAD boundary for one contiguous pressure
     * episode while retaining the newest possible backlog.
     */
    private fun dropOldestPcmUntilFitsLocked(
        incoming: RealtimeCanonicalPcmFrame,
        pendingGap: StreamingAudioEvent.Discontinuity?,
        recoverToWatermark: Boolean,
    ): OverflowDropResult {
        val retained = ArrayDeque<StreamingAudioEvent>()
        var remainingFrames = queuedPcmFrames
        var remainingSamples = queuedPcmSamples
        var removedFrames = 0L
        var removedSamples = 0L
        var discontinuityInserted = false

        val targetFrames =
            if (recoverToWatermark) overflowRecoveryPcmFrames else maxQueuedPcmFrames
        val targetSamples =
            if (recoverToWatermark) {
                maxOf(incoming.sampleCount, overflowRecoveryPcmSamples)
            } else {
                maxQueuedPcmSamples
            }

        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            if (event === pendingGap) {
                continue
            }
            if (event is StreamingAudioEvent.PcmFrame &&
                (remainingFrames + 1 > targetFrames ||
                    remainingSamples + incoming.sampleCount > targetSamples)
            ) {
                remainingFrames -= 1
                remainingSamples -= event.frame.sampleCount
                removedFrames += 1
                removedSamples += event.frame.sampleCount
                continue
            }

            if (event is StreamingAudioEvent.PcmFrame &&
                removedFrames > 0L &&
                !discontinuityInserted
            ) {
                retained.addLast(
                    overflowGap(
                        frame = event.frame,
                        pendingGap = pendingGap,
                        newlyDroppedFrames = removedFrames,
                        newlyDroppedSamples = removedSamples,
                    ),
                )
                discontinuityInserted = true
            }
            retained.addLast(event)
        }

        check(removedFrames > 0L) { "overflow recovery must drop at least one queued PCM frame" }
        if (!discontinuityInserted) {
            retained.addLast(
                overflowGap(
                    frame = incoming,
                    pendingGap = pendingGap,
                    newlyDroppedFrames = removedFrames,
                    newlyDroppedSamples = removedSamples,
                ),
            )
        }

        queue.addAll(retained)
        queuedPcmFrames = remainingFrames
        queuedPcmSamples = remainingSamples
        return OverflowDropResult(removedFrames, removedSamples)
    }

    private fun overflowGap(
        frame: RealtimeCanonicalPcmFrame,
        pendingGap: StreamingAudioEvent.Discontinuity?,
        newlyDroppedFrames: Long,
        newlyDroppedSamples: Long,
    ) = StreamingAudioEvent.Discontinuity(
        sessionId = frame.sessionId,
        epochId = frame.epochId,
        resumeAtSampleIndex = frame.startSampleIndex,
        reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
        droppedFrames = (pendingGap?.droppedFrames ?: 0L) + newlyDroppedFrames,
        droppedSamples = (pendingGap?.droppedSamples ?: 0L) + newlyDroppedSamples,
    )

    private data class OverflowDropResult(
        val newlyDroppedFrames: Long,
        val newlyDroppedSamples: Long,
    )
}
