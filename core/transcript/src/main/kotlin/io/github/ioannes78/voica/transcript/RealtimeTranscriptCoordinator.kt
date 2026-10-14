package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import io.github.ioannes78.voica.audio.StreamingAudioBuffer
import io.github.ioannes78.voica.audio.StreamingAudioBufferOfferResult
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class StreamingAudioSideSinkOfferResult {
    ACCEPTED,
    REJECTED,
}

/**
 * Non-blocking canonical fan-out seam owned by the unique Stage 16 source pump.
 *
 * Implementations must only enqueue/copy work and return. Disk IO, Room, native inference, or any
 * other blocking work belongs on the sink's own worker. Rejection is explicit and never backpressures
 * Stage 15 canonical ingest.
 */
fun interface StreamingAudioSideSink {
    fun offer(event: StreamingAudioEvent): StreamingAudioSideSinkOfferResult
}

data class RealtimeTranscriptCoordinatorSnapshot(
    val started: Boolean,
    val completed: Boolean,
    val consumedSourceEvents: Long,
    val consumedPcmFrames: Long,
    val consumedPcmSamples: Long,
    val consumedUpstreamDiscontinuities: Long,
    val sideSinkOffers: Long,
    val sideSinkRejects: Long,
    val sideSinkFailures: Long,
)

/**
 * Single production consumer of the Stage 15 canonical realtime source.
 *
 * Stage 16 code must fan out below this coordinator instead of allowing multiple modules to race on
 * the destructive Stage 15 `receive()` seam. The ASR path uses its own bounded buffer. Canonical side
 * sinks receive every source event before ASR-buffer admission so local audio persistence can remain
 * independent from recognizer slowdown/overflow.
 */
class RealtimeTranscriptCoordinator(
    private val source: CanonicalRealtimeEventSource,
    private val outputBuffer: StreamingAudioBuffer,
    sideSinks: List<StreamingAudioSideSink> = emptyList(),
) {
    private val sideSinks = sideSinks.toList()
    private val started = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val consumedSourceEvents = AtomicLong(0)
    private val consumedPcmFrames = AtomicLong(0)
    private val consumedPcmSamples = AtomicLong(0)
    private val consumedUpstreamDiscontinuities = AtomicLong(0)
    private val sideSinkOffers = AtomicLong(0)
    private val sideSinkRejects = AtomicLong(0)
    private val sideSinkFailures = AtomicLong(0)

    /** Runs the unique Stage 15 source pump. It may be invoked exactly once per coordinator. */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "RealtimeTranscriptCoordinator.run() may only start once" }
        try {
            while (true) {
                val sourceEvent = source.receive() ?: break
                consumedSourceEvents.incrementAndGet()
                val event = sourceEvent.toStreamingAudioEvent()
                if (event is StreamingAudioEvent.PcmFrame) {
                    consumedPcmFrames.incrementAndGet()
                    consumedPcmSamples.addAndGet(event.frame.sampleCount.toLong())
                } else if (event is StreamingAudioEvent.Discontinuity) {
                    consumedUpstreamDiscontinuities.incrementAndGet()
                }

                fanOut(event)

                when (outputBuffer.offer(event)) {
                    StreamingAudioBufferOfferResult.ACCEPTED,
                    StreamingAudioBufferOfferResult.ACCEPTED_AFTER_DISCONTINUITY,
                    -> Unit

                    StreamingAudioBufferOfferResult.REJECTED_AFTER_FINISH ->
                        error("streaming audio buffer finished before canonical source")

                    StreamingAudioBufferOfferResult.REJECTED_OVERSIZED_FRAME ->
                        error("canonical PCM frame exceeds configured Stage 16 streaming buffer limit")
                }
            }
        } finally {
            outputBuffer.finishInput()
            completed.set(true)
        }
    }

    suspend fun receive(): StreamingAudioEvent? = outputBuffer.receive()

    fun snapshot(): RealtimeTranscriptCoordinatorSnapshot = RealtimeTranscriptCoordinatorSnapshot(
        started = started.get(),
        completed = completed.get(),
        consumedSourceEvents = consumedSourceEvents.get(),
        consumedPcmFrames = consumedPcmFrames.get(),
        consumedPcmSamples = consumedPcmSamples.get(),
        consumedUpstreamDiscontinuities = consumedUpstreamDiscontinuities.get(),
        sideSinkOffers = sideSinkOffers.get(),
        sideSinkRejects = sideSinkRejects.get(),
        sideSinkFailures = sideSinkFailures.get(),
    )

    private fun fanOut(event: StreamingAudioEvent) {
        sideSinks.forEach { sink ->
            sideSinkOffers.incrementAndGet()
            try {
                if (sink.offer(event) == StreamingAudioSideSinkOfferResult.REJECTED) {
                    sideSinkRejects.incrementAndGet()
                }
            } catch (_: Exception) {
                sideSinkFailures.incrementAndGet()
            }
        }
    }

    private fun RealtimeCanonicalStreamEvent.toStreamingAudioEvent(): StreamingAudioEvent = when (this) {
        is RealtimeCanonicalStreamEvent.SessionStarted ->
            StreamingAudioEvent.SessionStarted(sessionId)

        is RealtimeCanonicalStreamEvent.EpochStarted ->
            StreamingAudioEvent.EpochStarted(sessionId, epochId, reason)

        is RealtimeCanonicalStreamEvent.PcmFrame ->
            StreamingAudioEvent.PcmFrame(frame)

        is RealtimeCanonicalStreamEvent.ConsumerDiscontinuity ->
            StreamingAudioEvent.Discontinuity(
                sessionId = sessionId,
                epochId = epochId,
                resumeAtSampleIndex = resumeAtSampleIndex,
                reason = StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE,
                droppedFrames = droppedFrames,
                droppedSamples = droppedSamples,
            )

        is RealtimeCanonicalStreamEvent.SessionEnded ->
            StreamingAudioEvent.SessionEnded(
                sessionId = sessionId,
                finalEpochId = finalEpochId,
                finalSampleIndexExclusive = finalSampleIndexExclusive,
                reason = reason,
                drainTimedOut = drainTimedOut,
            )
    }
}
