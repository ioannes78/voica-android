package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSourceSnapshot
import io.github.ioannes78.voica.audio.RealtimeCanonicalOfferResult
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndResult
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PhoneMicrophoneRealtimeBridgeSnapshot(
    val offeredFrames: Long,
    val offeredSamples: Long,
    val acceptedFrames: Long,
    val discontinuityAcceptedFrames: Long,
    val rejectedFrames: Long,
    val rejectedSamples: Long,
    val lastOfferedSessionId: Long?,
    val lastOfferedEpochId: Long?,
    val lastOfferedEndSampleIndexExclusive: Long?,
    val closed: Boolean,
    val source: RealtimeCanonicalAudioSourceSnapshot,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A.1 phone realtime bridge]")
        appendLine(
            "offeredFrames=$offeredFrames offeredSamples=$offeredSamples " +
                "acceptedFrames=$acceptedFrames acceptedAfterDiscontinuity=$discontinuityAcceptedFrames",
        )
        appendLine(
            "rejectedFrames=$rejectedFrames rejectedSamples=$rejectedSamples " +
                "lastBoundary=${lastOfferedSessionId ?: -1}/${lastOfferedEpochId ?: -1}/" +
                "${lastOfferedEndSampleIndexExclusive ?: -1} closed=$closed",
        )
        append(
            "source queuedFrames=${source.queuedPcmFrames} queuedSamples=${source.queuedPcmSamples} " +
                "overflow=${source.overflowEvents} droppedFrames=${source.droppedFrames} " +
                "droppedSamples=${source.droppedSamples}",
        )
    }
}

/**
 * Best-effort realtime fan-out that sits strictly after formal phone WAV persistence.
 *
 * [offer] never throws into the recording path. Bounded-source overflow is an ASR discontinuity and
 * cannot remove or rewrite PCM already accepted by the formal recorder.
 */
internal class PhoneMicrophoneRealtimeCanonicalBridge(
    maxQueuedPcmFrames: Int = DEFAULT_MAX_QUEUED_PCM_FRAMES,
    maxQueuedPcmSamples: Int = DEFAULT_MAX_QUEUED_PCM_SAMPLES,
) : PhoneMicrophoneCanonicalPostWriteSink,
    CanonicalRealtimeEventSource,
    Closeable {
    private val source =
        RealtimeCanonicalAudioSource(
            maxQueuedPcmFrames = maxQueuedPcmFrames,
            maxQueuedPcmSamples = maxQueuedPcmSamples,
        )
    private val closed = AtomicBoolean(false)
    private val offeredFrames = AtomicLong(0L)
    private val offeredSamples = AtomicLong(0L)
    private val acceptedFrames = AtomicLong(0L)
    private val discontinuityAcceptedFrames = AtomicLong(0L)
    private val rejectedFrames = AtomicLong(0L)
    private val rejectedSamples = AtomicLong(0L)
    private val boundaryLock = Any()
    private var lastOfferedSessionId: Long? = null
    private var lastOfferedEpochId: Long? = null
    private var lastOfferedEndSampleIndexExclusive: Long? = null

    override fun offer(frame: RealtimeCanonicalPcmFrame) {
        offeredFrames.incrementAndGet()
        offeredSamples.addAndGet(frame.sampleCount.toLong())
        synchronized(boundaryLock) {
            lastOfferedSessionId = frame.sessionId
            lastOfferedEpochId = frame.epochId
            lastOfferedEndSampleIndexExclusive = frame.endSampleIndexExclusive
        }
        when (source.offer(frame)) {
            RealtimeCanonicalOfferResult.ACCEPTED -> acceptedFrames.incrementAndGet()
            RealtimeCanonicalOfferResult.ACCEPTED_AFTER_DISCONTINUITY -> {
                acceptedFrames.incrementAndGet()
                discontinuityAcceptedFrames.incrementAndGet()
            }
            RealtimeCanonicalOfferResult.REJECTED -> {
                rejectedFrames.incrementAndGet()
                rejectedSamples.addAndGet(frame.sampleCount.toLong())
            }
        }
    }

    override suspend fun receive(): RealtimeCanonicalStreamEvent? = source.receive()

    suspend fun endAfterStop(): RealtimeCanonicalSessionEndResult =
        source.endActiveSessionAfterQuiet(
            reason = RealtimeCanonicalSessionEndReason.STOP,
            quietPeriodMs = 0L,
            maxDrainMs = 0L,
        )

    fun endImmediately(reason: RealtimeCanonicalSessionEndReason): RealtimeCanonicalSessionEndResult =
        source.endActiveSessionImmediately(reason)

    fun snapshot(): PhoneMicrophoneRealtimeBridgeSnapshot {
        val boundary = synchronized(boundaryLock) {
            Triple(lastOfferedSessionId, lastOfferedEpochId, lastOfferedEndSampleIndexExclusive)
        }
        return PhoneMicrophoneRealtimeBridgeSnapshot(
            offeredFrames = offeredFrames.get(),
            offeredSamples = offeredSamples.get(),
            acceptedFrames = acceptedFrames.get(),
            discontinuityAcceptedFrames = discontinuityAcceptedFrames.get(),
            rejectedFrames = rejectedFrames.get(),
            rejectedSamples = rejectedSamples.get(),
            lastOfferedSessionId = boundary.first,
            lastOfferedEpochId = boundary.second,
            lastOfferedEndSampleIndexExclusive = boundary.third,
            closed = closed.get(),
            source = source.snapshot(),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        source.close()
    }

    private companion object {
        const val DEFAULT_MAX_QUEUED_PCM_FRAMES = 100
        const val DEFAULT_MAX_QUEUED_PCM_SAMPLES = 32_000
    }
}
