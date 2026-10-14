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
)

/**
 * Engine/provider-independent bounded Stage 16 PCM buffer.
 *
 * PCM is bounded independently from low-volume lifecycle events, mirroring the Stage 15 canonical
 * seam. If a slow Stage 16 consumer would exceed the PCM hard limits, only the oldest PCM required
 * to make room is dropped. A [StreamingAudioEvent.Discontinuity] is inserted immediately before the
 * first retained PCM after the gap, preserving the newest possible backlog without hiding loss. No
 * silence or wall-clock-derived samples are synthesized.
 */
class StreamingAudioBuffer(
    private val maxQueuedPcmFrames: Int,
    private val maxQueuedPcmSamples: Int,
) : Closeable {
    init {
        require(maxQueuedPcmFrames > 0) { "maxQueuedPcmFrames must be positive" }
        require(maxQueuedPcmSamples > 0) { "maxQueuedPcmSamples must be positive" }
    }

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
                dropOldestPcmUntilFitsLocked(frame)
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
        }
        signal.close()
    }

    private fun removeFirstLocked(): StreamingAudioEvent? {
        if (queue.isEmpty()) return null
        val event = queue.removeFirst()
        if (event is StreamingAudioEvent.PcmFrame) {
            queuedPcmFrames -= 1
            queuedPcmSamples -= event.frame.sampleCount
        }
        return event
    }

    /**
     * Removes the minimum number of oldest PCM frames needed for [incoming] to fit. Lifecycle events
     * keep their relative order. The generated discontinuity sits immediately before the first
     * retained PCM after the gap; if no queued PCM remains it is placed before [incoming].
     */
    private fun dropOldestPcmUntilFitsLocked(incoming: RealtimeCanonicalPcmFrame) {
        val retained = ArrayDeque<StreamingAudioEvent>()
        var remainingFrames = queuedPcmFrames
        var remainingSamples = queuedPcmSamples
        var removedFrames = 0L
        var removedSamples = 0L
        var discontinuityInserted = false

        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            if (event is StreamingAudioEvent.PcmFrame &&
                (remainingFrames + 1 > maxQueuedPcmFrames ||
                    remainingSamples + incoming.sampleCount > maxQueuedPcmSamples)
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
                    StreamingAudioEvent.Discontinuity(
                        sessionId = event.frame.sessionId,
                        epochId = event.frame.epochId,
                        resumeAtSampleIndex = event.frame.startSampleIndex,
                        reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
                        droppedFrames = removedFrames,
                        droppedSamples = removedSamples,
                    ),
                )
                discontinuityInserted = true
            }
            retained.addLast(event)
        }

        check(removedFrames > 0L) { "overflow recovery must drop at least one queued PCM frame" }
        if (!discontinuityInserted) {
            retained.addLast(
                StreamingAudioEvent.Discontinuity(
                    sessionId = incoming.sessionId,
                    epochId = incoming.epochId,
                    resumeAtSampleIndex = incoming.startSampleIndex,
                    reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
                    droppedFrames = removedFrames,
                    droppedSamples = removedSamples,
                ),
            )
        }

        queue.addAll(retained)
        queuedPcmFrames = remainingFrames
        queuedPcmSamples = remainingSamples
        droppedFrames += removedFrames
        droppedSamples += removedSamples
    }
}
