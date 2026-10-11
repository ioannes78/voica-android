package io.github.ioannes78.voica.audio

import java.io.Closeable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

enum class RealtimeCanonicalSessionEndReason {
    STOP,
    DEVICE_RECORDING_ENDED,
    TRANSPORT_CLOSED,
    SUPERSEDED_BY_NEW_SESSION,
    RUNTIME_CLOSED,
}

enum class RealtimeCanonicalConsumerDiscontinuityReason {
    BACKPRESSURE,
}

sealed interface RealtimeCanonicalStreamEvent {
    val sessionId: Long

    data class SessionStarted(
        override val sessionId: Long,
    ) : RealtimeCanonicalStreamEvent

    data class EpochStarted(
        override val sessionId: Long,
        val epochId: Long,
        val reason: RealtimeCanonicalEpochStartReason,
    ) : RealtimeCanonicalStreamEvent

    data class PcmFrame(
        val frame: RealtimeCanonicalPcmFrame,
    ) : RealtimeCanonicalStreamEvent {
        override val sessionId: Long
            get() = frame.sessionId
    }

    data class ConsumerDiscontinuity(
        override val sessionId: Long,
        val epochId: Long,
        val resumeAtSampleIndex: Long,
        val reason: RealtimeCanonicalConsumerDiscontinuityReason,
        val droppedFrames: Long,
        val droppedSamples: Long,
    ) : RealtimeCanonicalStreamEvent

    data class SessionEnded(
        override val sessionId: Long,
        val finalEpochId: Long?,
        val finalSampleIndexExclusive: Long,
        val reason: RealtimeCanonicalSessionEndReason,
        val drainTimedOut: Boolean,
    ) : RealtimeCanonicalStreamEvent
}

enum class RealtimeCanonicalOfferResult {
    ACCEPTED,
    ACCEPTED_AFTER_DISCONTINUITY,
    REJECTED,
}

enum class RealtimeCanonicalSessionEndResult {
    ENDED,
    ENDED_AFTER_TIMEOUT,
    NO_ACTIVE_SESSION,
    SUPERSEDED,
    CLOSED,
}

data class RealtimeCanonicalAudioSourceSnapshot(
    val sessionActive: Boolean = false,
    val activeSessionId: Long? = null,
    val activeEpochId: Long? = null,
    val queuedPcmFrames: Int = 0,
    val queuedPcmSamples: Int = 0,
    val peakQueuedPcmFrames: Int = 0,
    val peakQueuedPcmSamples: Int = 0,
    val offeredFrames: Long = 0,
    val offeredSamples: Long = 0,
    val sessionStartedEvents: Long = 0,
    val epochStartedEvents: Long = 0,
    val sessionEndedEvents: Long = 0,
    val consumerDiscontinuities: Long = 0,
    val overflowEvents: Long = 0,
    val droppedFrames: Long = 0,
    val droppedSamples: Long = 0,
    val rejectedAfterClose: Long = 0,
    val lastSessionEndReason: RealtimeCanonicalSessionEndReason? = null,
    val lastSessionEndDrainTimedOut: Boolean = false,
)

/**
 * Stage 15.5 canonical consumer seam.
 *
 * PCM buffering is deliberately bounded and independent from BLE/Opus. A slow downstream consumer
 * never blocks the decoder worker: stale queued PCM is discarded and a self-identifying consumer
 * discontinuity is inserted before the live-edge frame. Lifecycle events are low-volume and remain
 * ordered with PCM events.
 */
class RealtimeCanonicalAudioSource(
    private val maxQueuedPcmFrames: Int = DEFAULT_MAX_QUEUED_PCM_FRAMES,
    private val maxQueuedPcmSamples: Int = DEFAULT_MAX_QUEUED_PCM_SAMPLES,
    private val nowNanos: () -> Long = System::nanoTime,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : Closeable {
    init {
        require(maxQueuedPcmFrames > 0)
        require(maxQueuedPcmSamples > 0)
    }

    private val lock = Any()
    private val queue = ArrayDeque<RealtimeCanonicalStreamEvent>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private var closed = false
    private var sessionActive = false
    private var activeSessionId: Long? = null
    private var activeEpochId: Long? = null
    private var lastEndSampleIndexExclusive = 0L
    private var lastFrameAtNanos = 0L
    private var queuedPcmFrames = 0
    private var queuedPcmSamples = 0

    private var peakQueuedPcmFrames = 0
    private var peakQueuedPcmSamples = 0
    private var offeredFrames = 0L
    private var offeredSamples = 0L
    private var sessionStartedEvents = 0L
    private var epochStartedEvents = 0L
    private var sessionEndedEvents = 0L
    private var consumerDiscontinuities = 0L
    private var overflowEvents = 0L
    private var droppedFrames = 0L
    private var droppedSamples = 0L
    private var rejectedAfterClose = 0L
    private var lastSessionEndReason: RealtimeCanonicalSessionEndReason? = null
    private var lastSessionEndDrainTimedOut = false

    fun offer(frame: RealtimeCanonicalPcmFrame): RealtimeCanonicalOfferResult {
        var shouldSignal = false
        val result = synchronized(lock) {
            if (closed) {
                rejectedAfterClose += 1
                return@synchronized RealtimeCanonicalOfferResult.REJECTED
            }

            if (activeSessionId != frame.sessionId) {
                if (sessionActive) {
                    enqueueSessionEndedLocked(
                        reason = RealtimeCanonicalSessionEndReason.SUPERSEDED_BY_NEW_SESSION,
                        drainTimedOut = false,
                    )
                }
                activeSessionId = frame.sessionId
                activeEpochId = null
                lastEndSampleIndexExclusive = 0L
                sessionActive = true
                queue.addLast(RealtimeCanonicalStreamEvent.SessionStarted(frame.sessionId))
                sessionStartedEvents += 1
            }

            if (activeEpochId != frame.epochId) {
                val reason = requireNotNull(frame.epochStartReason) {
                    "first frame of canonical epoch must carry epochStartReason"
                }
                activeEpochId = frame.epochId
                queue.addLast(
                    RealtimeCanonicalStreamEvent.EpochStarted(
                        sessionId = frame.sessionId,
                        epochId = frame.epochId,
                        reason = reason,
                    ),
                )
                epochStartedEvents += 1
            }

            val wouldOverflow =
                queuedPcmFrames + 1 > maxQueuedPcmFrames ||
                    queuedPcmSamples + frame.sampleCount > maxQueuedPcmSamples

            val recoveredOverflow = if (wouldOverflow) {
                overflowEvents += 1
                val dropped = dropQueuedPcmLocked()
                if (dropped.first > 0L) {
                    consumerDiscontinuities += 1
                    queue.addLast(
                        RealtimeCanonicalStreamEvent.ConsumerDiscontinuity(
                            sessionId = frame.sessionId,
                            epochId = frame.epochId,
                            resumeAtSampleIndex = frame.startSampleIndex,
                            reason = RealtimeCanonicalConsumerDiscontinuityReason.BACKPRESSURE,
                            droppedFrames = dropped.first,
                            droppedSamples = dropped.second,
                        ),
                    )
                }
                true
            } else {
                false
            }

            queue.addLast(RealtimeCanonicalStreamEvent.PcmFrame(frame))
            queuedPcmFrames += 1
            queuedPcmSamples += frame.sampleCount
            peakQueuedPcmFrames = maxOf(peakQueuedPcmFrames, queuedPcmFrames)
            peakQueuedPcmSamples = maxOf(peakQueuedPcmSamples, queuedPcmSamples)
            offeredFrames += 1
            offeredSamples += frame.sampleCount
            lastEndSampleIndexExclusive = frame.endSampleIndexExclusive
            lastFrameAtNanos = nowNanos()
            shouldSignal = true

            if (recoveredOverflow) {
                RealtimeCanonicalOfferResult.ACCEPTED_AFTER_DISCONTINUITY
            } else {
                RealtimeCanonicalOfferResult.ACCEPTED
            }
        }

        if (shouldSignal) signal.trySend(Unit)
        return result
    }

    suspend fun receive(): RealtimeCanonicalStreamEvent? {
        while (true) {
            synchronized(lock) {
                removeFirstLocked()?.let { return it }
                if (closed) return null
            }
            if (signal.receiveCatching().getOrNull() == null) return null
        }
    }

    /**
     * Immediately appends a terminal lifecycle event after all already-accepted PCM.
     *
     * Callers must first stop/join the producer so no PCM can be offered after this terminal event.
     * This is the transport/runtime-close path; normal STOP continues to use quiet-drain semantics.
     */
    fun endActiveSessionImmediately(
        reason: RealtimeCanonicalSessionEndReason,
    ): RealtimeCanonicalSessionEndResult {
        require(reason != RealtimeCanonicalSessionEndReason.STOP) {
            "STOP must use endActiveSessionAfterQuiet()"
        }
        var shouldSignal = false
        val result = synchronized(lock) {
            if (closed) return@synchronized RealtimeCanonicalSessionEndResult.CLOSED
            if (!sessionActive) return@synchronized RealtimeCanonicalSessionEndResult.NO_ACTIVE_SESSION
            enqueueSessionEndedLocked(reason = reason, drainTimedOut = false)
            shouldSignal = true
            RealtimeCanonicalSessionEndResult.ENDED
        }
        if (shouldSignal) signal.trySend(Unit)
        return result
    }

    suspend fun endActiveSessionAfterQuiet(
        reason: RealtimeCanonicalSessionEndReason = RealtimeCanonicalSessionEndReason.STOP,
        quietPeriodMs: Long = DEFAULT_STOP_DRAIN_QUIET_MS,
        maxDrainMs: Long = DEFAULT_STOP_DRAIN_MAX_MS,
    ): RealtimeCanonicalSessionEndResult {
        require(quietPeriodMs >= 0L)
        require(maxDrainMs >= quietPeriodMs)

        val capturedSessionId = synchronized(lock) {
            if (closed) return RealtimeCanonicalSessionEndResult.CLOSED
            if (!sessionActive) return RealtimeCanonicalSessionEndResult.NO_ACTIVE_SESSION
            requireNotNull(activeSessionId)
        }
        val startedAtNanos = nowNanos()
        val quietNanos = quietPeriodMs * NANOS_PER_MILLISECOND
        val maxDrainNanos = maxDrainMs * NANOS_PER_MILLISECOND

        while (true) {
            var completed: RealtimeCanonicalSessionEndResult? = null
            var sleepMs = 1L
            var shouldSignal = false
            synchronized(lock) {
                if (closed) {
                    completed = RealtimeCanonicalSessionEndResult.CLOSED
                    return@synchronized
                }
                if (!sessionActive || activeSessionId != capturedSessionId) {
                    completed = RealtimeCanonicalSessionEndResult.SUPERSEDED
                    return@synchronized
                }

                val now = nowNanos()
                val quietFor = (now - lastFrameAtNanos).coerceAtLeast(0L)
                val drainingFor = (now - startedAtNanos).coerceAtLeast(0L)
                val quietComplete = quietFor >= quietNanos
                val timedOut = drainingFor >= maxDrainNanos
                if (quietComplete || timedOut) {
                    enqueueSessionEndedLocked(reason = reason, drainTimedOut = timedOut && !quietComplete)
                    completed = if (timedOut && !quietComplete) {
                        RealtimeCanonicalSessionEndResult.ENDED_AFTER_TIMEOUT
                    } else {
                        RealtimeCanonicalSessionEndResult.ENDED
                    }
                    shouldSignal = true
                } else {
                    val remainingQuietMs = ((quietNanos - quietFor) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                    val remainingDrainMs = ((maxDrainNanos - drainingFor) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                    sleepMs = minOf(remainingQuietMs, remainingDrainMs, DRAIN_POLL_MAX_MS)
                }
            }
            if (shouldSignal) signal.trySend(Unit)
            completed?.let { return it }
            sleeper(sleepMs)
        }
    }

    fun snapshot(): RealtimeCanonicalAudioSourceSnapshot = synchronized(lock) {
        RealtimeCanonicalAudioSourceSnapshot(
            sessionActive = sessionActive,
            activeSessionId = activeSessionId,
            activeEpochId = activeEpochId,
            queuedPcmFrames = queuedPcmFrames,
            queuedPcmSamples = queuedPcmSamples,
            peakQueuedPcmFrames = peakQueuedPcmFrames,
            peakQueuedPcmSamples = peakQueuedPcmSamples,
            offeredFrames = offeredFrames,
            offeredSamples = offeredSamples,
            sessionStartedEvents = sessionStartedEvents,
            epochStartedEvents = epochStartedEvents,
            sessionEndedEvents = sessionEndedEvents,
            consumerDiscontinuities = consumerDiscontinuities,
            overflowEvents = overflowEvents,
            droppedFrames = droppedFrames,
            droppedSamples = droppedSamples,
            rejectedAfterClose = rejectedAfterClose,
            lastSessionEndReason = lastSessionEndReason,
            lastSessionEndDrainTimedOut = lastSessionEndDrainTimedOut,
        )
    }

    /** Resets counters only. Queue ordering and active lifecycle state remain untouched. */
    fun resetDiagnostics() = synchronized(lock) {
        peakQueuedPcmFrames = queuedPcmFrames
        peakQueuedPcmSamples = queuedPcmSamples
        offeredFrames = 0
        offeredSamples = 0
        sessionStartedEvents = 0
        epochStartedEvents = 0
        sessionEndedEvents = 0
        consumerDiscontinuities = 0
        overflowEvents = 0
        droppedFrames = 0
        droppedSamples = 0
        rejectedAfterClose = 0
        lastSessionEndReason = null
        lastSessionEndDrainTimedOut = false
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queue.clear()
            queuedPcmFrames = 0
            queuedPcmSamples = 0
            sessionActive = false
            activeSessionId = null
            activeEpochId = null
        }
        signal.close()
    }

    private fun enqueueSessionEndedLocked(
        reason: RealtimeCanonicalSessionEndReason,
        drainTimedOut: Boolean,
    ) {
        val sessionId = activeSessionId ?: return
        queue.addLast(
            RealtimeCanonicalStreamEvent.SessionEnded(
                sessionId = sessionId,
                finalEpochId = activeEpochId,
                finalSampleIndexExclusive = lastEndSampleIndexExclusive,
                reason = reason,
                drainTimedOut = drainTimedOut,
            ),
        )
        sessionEndedEvents += 1
        lastSessionEndReason = reason
        lastSessionEndDrainTimedOut = drainTimedOut
        sessionActive = false
        activeSessionId = null
        activeEpochId = null
        lastEndSampleIndexExclusive = 0L
        lastFrameAtNanos = 0L
    }

    private fun removeFirstLocked(): RealtimeCanonicalStreamEvent? {
        if (queue.isEmpty()) return null
        val event = queue.removeFirst()
        if (event is RealtimeCanonicalStreamEvent.PcmFrame) {
            queuedPcmFrames -= 1
            queuedPcmSamples -= event.frame.sampleCount
        }
        return event
    }

    private fun dropQueuedPcmLocked(): Pair<Long, Long> {
        if (queuedPcmFrames == 0) return 0L to 0L

        val retained = ArrayDeque<RealtimeCanonicalStreamEvent>()
        var removedFrames = 0L
        var removedSamples = 0L
        while (queue.isNotEmpty()) {
            when (val event = queue.removeFirst()) {
                is RealtimeCanonicalStreamEvent.PcmFrame -> {
                    removedFrames += 1
                    removedSamples += event.frame.sampleCount
                }
                else -> retained.addLast(event)
            }
        }
        queue.addAll(retained)
        queuedPcmFrames = 0
        queuedPcmSamples = 0
        droppedFrames += removedFrames
        droppedSamples += removedSamples
        return removedFrames to removedSamples
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_PCM_FRAMES = 32
        const val DEFAULT_MAX_QUEUED_PCM_SAMPLES = 10_240
        const val DEFAULT_STOP_DRAIN_QUIET_MS = 250L
        const val DEFAULT_STOP_DRAIN_MAX_MS = 1_500L
        private const val DRAIN_POLL_MAX_MS = 25L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
