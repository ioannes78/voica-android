package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import java.util.ArrayDeque

/**
 * Wall-clock latency from Stage 16 canonical PCM ingestion to text output return.
 *
 * Canonical sample indexes are correlation keys only. They are never derived from wall clock and
 * this probe never rewrites media time. Native engine execution time remains a separate metric.
 */
data class Stage16ARealtimeLatencyStatSnapshot(
    val count: Long = 0L,
    val averageMs: Double? = null,
    val minMs: Double? = null,
    val maxMs: Double? = null,
)

data class Stage16ARealtimeOutputLatencySnapshot(
    val partial: Stage16ARealtimeLatencyStatSnapshot = Stage16ARealtimeLatencyStatSnapshot(),
    val stable: Stage16ARealtimeLatencyStatSnapshot = Stage16ARealtimeLatencyStatSnapshot(),
    val final: Stage16ARealtimeLatencyStatSnapshot = Stage16ARealtimeLatencyStatSnapshot(),
    val correlatedOutputs: Long = 0L,
    val uncorrelatedOutputs: Long = 0L,
)

class Stage16ARealtimeOutputLatencyProbe(
    private val delegate: RealtimeTranscriptionEventProcessor,
    private val nowNanos: () -> Long = System::nanoTime,
    private val maxTrackedBoundaries: Int = DEFAULT_MAX_TRACKED_BOUNDARIES,
) : RealtimeTranscriptionEventProcessor {
    init {
        require(maxTrackedBoundaries > 0)
    }

    private val lock = Any()
    private val boundaries = ArrayDeque<BoundaryArrival>()
    private val partial = LatencyAccumulator()
    private val stable = LatencyAccumulator()
    private val final = LatencyAccumulator()
    private var correlatedOutputs = 0L
    private var uncorrelatedOutputs = 0L

    override suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> {
        if (event is StreamingAudioEvent.PcmFrame) {
            val observedAtNanos = nowNanos()
            synchronized(lock) {
                boundaries.addLast(
                    BoundaryArrival(
                        sessionId = event.frame.sessionId,
                        epochId = event.frame.epochId,
                        endSampleIndexExclusive = event.frame.endSampleIndexExclusive,
                        observedAtNanos = observedAtNanos,
                    ),
                )
                while (boundaries.size > maxTrackedBoundaries) {
                    boundaries.removeFirst()
                }
            }
        }

        return delegate.accept(event).also(::recordOutputs)
    }

    override suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> =
        delegate.onPauseCommitted().also(::recordOutputs)

    fun snapshot(): Stage16ARealtimeOutputLatencySnapshot = synchronized(lock) {
        Stage16ARealtimeOutputLatencySnapshot(
            partial = partial.snapshot(),
            stable = stable.snapshot(),
            final = final.snapshot(),
            correlatedOutputs = correlatedOutputs,
            uncorrelatedOutputs = uncorrelatedOutputs,
        )
    }

    private fun recordOutputs(outputs: List<RealtimeTranscriptionOutput>) {
        if (outputs.isEmpty()) return
        val returnedAtNanos = nowNanos()
        synchronized(lock) {
            outputs.forEach { output ->
                val hypothesis = output as? RealtimeTranscriptionOutput.Hypothesis ?: return@forEach
                val source = hypothesis.source
                val boundary = boundaries.firstOrNull { candidate ->
                    candidate.sessionId == source.sessionId &&
                        candidate.epochId == source.epochId &&
                        candidate.endSampleIndexExclusive >= source.acceptedEndSampleIndexExclusive
                }
                if (boundary == null || returnedAtNanos < boundary.observedAtNanos) {
                    uncorrelatedOutputs += 1
                    return@forEach
                }

                val latencyNanos = returnedAtNanos - boundary.observedAtNanos
                when (hypothesis.stability) {
                    AsrHypothesisStability.PARTIAL -> partial.record(latencyNanos)
                    AsrHypothesisStability.STABLE -> stable.record(latencyNanos)
                    AsrHypothesisStability.FINAL -> final.record(latencyNanos)
                }
                correlatedOutputs += 1
            }
        }
    }

    private data class BoundaryArrival(
        val sessionId: Long,
        val epochId: Long,
        val endSampleIndexExclusive: Long,
        val observedAtNanos: Long,
    )

    private class LatencyAccumulator {
        private var count = 0L
        private var totalNanos = 0L
        private var minNanos = Long.MAX_VALUE
        private var maxNanos = 0L

        fun record(valueNanos: Long) {
            require(valueNanos >= 0L)
            count += 1
            totalNanos = saturatingAdd(totalNanos, valueNanos)
            minNanos = minOf(minNanos, valueNanos)
            maxNanos = maxOf(maxNanos, valueNanos)
        }

        fun snapshot(): Stage16ARealtimeLatencyStatSnapshot {
            if (count == 0L) return Stage16ARealtimeLatencyStatSnapshot()
            return Stage16ARealtimeLatencyStatSnapshot(
                count = count,
                averageMs = totalNanos.toDouble() / count / NANOS_PER_MS,
                minMs = minNanos.toDouble() / NANOS_PER_MS,
                maxMs = maxNanos.toDouble() / NANOS_PER_MS,
            )
        }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    companion object {
        private const val DEFAULT_MAX_TRACKED_BOUNDARIES = 8_192
        private const val NANOS_PER_MS = 1_000_000.0
    }
}
