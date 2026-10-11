package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame

data class Stage15CanonicalProbeSnapshot(
    val frames: Long = 0,
    val samples: Long = 0,
    val sessionsSeen: Long = 0,
    val epochsSeen: Long = 0,
    val gapCount: Long = 0,
    val overlapCount: Long = 0,
    val invalidEpochStartCount: Long = 0,
    val lastSessionId: Long? = null,
    val lastEpochId: Long? = null,
    val lastEndSampleIndexExclusive: Long = 0,
)

/** Independent QA observer for the canonical frame seam. It never feeds media back into the pipeline. */
internal class Stage15CanonicalProbeAccumulator {
    private val lock = Any()
    private var frames = 0L
    private var samples = 0L
    private var sessionsSeen = 0L
    private var epochsSeen = 0L
    private var gapCount = 0L
    private var overlapCount = 0L
    private var invalidEpochStartCount = 0L
    private var lastSessionId: Long? = null
    private var lastEpochId: Long? = null
    private var lastEndSampleIndexExclusive = 0L

    fun accept(frame: RealtimeCanonicalPcmFrame) = synchronized(lock) {
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
            gapCount = gapCount,
            overlapCount = overlapCount,
            invalidEpochStartCount = invalidEpochStartCount,
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
        gapCount = 0
        overlapCount = 0
        invalidEpochStartCount = 0
        lastSessionId = null
        lastEpochId = null
        lastEndSampleIndexExclusive = 0
    }
}
