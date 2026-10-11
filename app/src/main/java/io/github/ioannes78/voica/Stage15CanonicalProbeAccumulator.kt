package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent

data class Stage15CanonicalProbeSnapshot(
    val frames: Long = 0,
    val samples: Long = 0,
    val sessionsSeen: Long = 0,
    val epochsSeen: Long = 0,
    val sessionStartedEvents: Long = 0,
    val epochStartedEvents: Long = 0,
    val sessionEndedEvents: Long = 0,
    val consumerDiscontinuities: Long = 0,
    val drainTimeoutEnds: Long = 0,
    val sessionEndMismatchCount: Long = 0,
    val gapCount: Long = 0,
    val overlapCount: Long = 0,
    val invalidEpochStartCount: Long = 0,
    val lastSessionEndReason: RealtimeCanonicalSessionEndReason? = null,
    val lastSessionId: Long? = null,
    val lastEpochId: Long? = null,
    val lastEndSampleIndexExclusive: Long = 0,
)

/** Independent QA observer for the Stage 15.5 canonical consumer seam. */
internal class Stage15CanonicalProbeAccumulator {
    private val lock = Any()
    private var frames = 0L
    private var samples = 0L
    private var sessionsSeen = 0L
    private var epochsSeen = 0L
    private var sessionStartedEvents = 0L
    private var epochStartedEvents = 0L
    private var sessionEndedEvents = 0L
    private var consumerDiscontinuities = 0L
    private var drainTimeoutEnds = 0L
    private var sessionEndMismatchCount = 0L
    private var gapCount = 0L
    private var overlapCount = 0L
    private var invalidEpochStartCount = 0L
    private var lastSessionEndReason: RealtimeCanonicalSessionEndReason? = null
    private var lastSessionId: Long? = null
    private var lastEpochId: Long? = null
    private var lastEndSampleIndexExclusive = 0L

    fun accept(event: RealtimeCanonicalStreamEvent) = synchronized(lock) {
        when (event) {
            is RealtimeCanonicalStreamEvent.SessionStarted -> {
                sessionStartedEvents += 1
            }

            is RealtimeCanonicalStreamEvent.EpochStarted -> {
                epochStartedEvents += 1
            }

            is RealtimeCanonicalStreamEvent.PcmFrame -> acceptFrameLocked(event.frame)

            is RealtimeCanonicalStreamEvent.ConsumerDiscontinuity -> {
                consumerDiscontinuities += 1
            }

            is RealtimeCanonicalStreamEvent.SessionEnded -> {
                sessionEndedEvents += 1
                if (event.drainTimedOut) drainTimeoutEnds += 1
                lastSessionEndReason = event.reason
                if (
                    lastSessionId == event.sessionId &&
                    event.finalSampleIndexExclusive != lastEndSampleIndexExclusive
                ) {
                    sessionEndMismatchCount += 1
                }
            }
        }
    }

    private fun acceptFrameLocked(frame: RealtimeCanonicalPcmFrame) {
        val sessionChanged = lastSessionId != frame.sessionId
        val epochChanged = sessionChanged || lastEpochId != frame.epochId
        if (sessionChanged) sessionsSeen += 1
        if (epochChanged) {
            epochsSeen += 1
            if (frame.startSampleIndex != 0L || frame.epochStartReason == null) {
                invalidEpochStartCount += 1
            }
        } else {
            when {
                frame.startSampleIndex > lastEndSampleIndexExclusive -> gapCount += 1
                frame.startSampleIndex < lastEndSampleIndexExclusive -> overlapCount += 1
            }
        }

        frames += 1
        samples += frame.sampleCount
        lastSessionId = frame.sessionId
        lastEpochId = frame.epochId
        lastEndSampleIndexExclusive = frame.endSampleIndexExclusive
    }

    fun snapshot(): Stage15CanonicalProbeSnapshot = synchronized(lock) {
        Stage15CanonicalProbeSnapshot(
            frames = frames,
            samples = samples,
            sessionsSeen = sessionsSeen,
            epochsSeen = epochsSeen,
            sessionStartedEvents = sessionStartedEvents,
            epochStartedEvents = epochStartedEvents,
            sessionEndedEvents = sessionEndedEvents,
            consumerDiscontinuities = consumerDiscontinuities,
            drainTimeoutEnds = drainTimeoutEnds,
            sessionEndMismatchCount = sessionEndMismatchCount,
            gapCount = gapCount,
            overlapCount = overlapCount,
            invalidEpochStartCount = invalidEpochStartCount,
            lastSessionEndReason = lastSessionEndReason,
            lastSessionId = lastSessionId,
            lastEpochId = lastEpochId,
            lastEndSampleIndexExclusive = lastEndSampleIndexExclusive,
        )
    }

    fun reset() = synchronized(lock) {
        frames = 0
        samples = 0
        sessionsSeen = 0
        epochsSeen = 0
        sessionStartedEvents = 0
        epochStartedEvents = 0
        sessionEndedEvents = 0
        consumerDiscontinuities = 0
        drainTimeoutEnds = 0
        sessionEndMismatchCount = 0
        gapCount = 0
        overlapCount = 0
        invalidEpochStartCount = 0
        lastSessionEndReason = null
        lastSessionId = null
        lastEpochId = null
        lastEndSampleIndexExclusive = 0
    }
}
