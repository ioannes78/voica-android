package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class RealtimeTranscriptionOutputSinkOfferResult {
    ACCEPTED,
    REJECTED,
}

/** Non-blocking fan-out seam for Room/UI workers below realtime inference. */
fun interface RealtimeTranscriptionOutputSink {
    fun offer(output: RealtimeTranscriptionOutput): RealtimeTranscriptionOutputSinkOfferResult
}

/** Testable event-processor seam around the concrete Stage 16A transcription pipeline. */
interface RealtimeTranscriptionEventProcessor {
    suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput>

    suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput>
}

class RealtimeTranscriptionPipelineEventProcessor(
    private val delegate: RealtimeTranscriptionPipeline,
) : RealtimeTranscriptionEventProcessor {
    override suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> =
        delegate.accept(event)

    override suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> =
        delegate.onPauseCommitted()
}

data class RealtimeProcessedCanonicalBoundary(
    val sessionId: Long,
    val epochId: Long,
    val endSampleIndexExclusive: Long,
) {
    init {
        require(sessionId > 0L)
        require(epochId > 0L)
        require(endSampleIndexExclusive > 0L)
    }
}

enum class RealtimeTranscriptionSessionRunnerState {
    NEW,
    RUNNING,
    TERMINATED,
    FAILED,
}

data class RealtimeTranscriptionSessionRunnerSnapshot(
    val state: RealtimeTranscriptionSessionRunnerState,
    val processedEvents: Long,
    val processedPcmFrames: Long,
    val processedPcmSamples: Long,
    val emittedOutputs: Long,
    val outputSinkOffers: Long,
    val outputSinkRejects: Long,
    val outputSinkFailures: Long,
    val pauseCommits: Long,
    val lastProcessedBoundary: RealtimeProcessedCanonicalBoundary?,
    val lastSessionEndReason: RealtimeCanonicalSessionEndReason?,
    val terminalReason: RealtimeCanonicalSessionEndReason?,
    val lastFailure: String?,
    val coordinator: RealtimeTranscriptCoordinatorSnapshot,
)

enum class RealtimePauseFinalizationStatus {
    COMMITTED,
    NOT_RUNNING,
    BOUNDARY_OVERRUN,
    TIMED_OUT,
}

data class RealtimePauseFinalizationOutcome(
    val status: RealtimePauseFinalizationStatus,
    val emittedOutputs: Int = 0,
    val processedBoundary: RealtimeProcessedCanonicalBoundary? = null,
)

/**
 * Owns the ordered Stage 16A source-pump -> ASR-consumer execution for one physical realtime run.
 *
 * [RealtimeTranscriptCoordinator] remains the sole destructive canonical-source consumer. Its pump
 * can continue across TRANSPORT_CLOSED / SUPERSEDED session boundaries; the logical runner terminates
 * only after STOP, DEVICE_RECORDING_ENDED, RUNTIME_CLOSED, source close, explicit coroutine
 * cancellation, or an unrecoverable processor exception.
 *
 * Output sinks must be non-blocking. Room/file/UI work belongs behind their own queues so it cannot
 * stall ASR inference or canonical ingest.
 */
class RealtimeTranscriptionSessionRunner(
    private val coordinator: RealtimeTranscriptCoordinator,
    private val processor: RealtimeTranscriptionEventProcessor,
    outputSinks: List<RealtimeTranscriptionOutputSink> = emptyList(),
) {
    private val outputSinks = outputSinks.toList()
    private val started = AtomicBoolean(false)
    private val pipelineMutex = Mutex()
    private val processedEvents = AtomicLong(0)
    private val processedPcmFrames = AtomicLong(0)
    private val processedPcmSamples = AtomicLong(0)
    private val emittedOutputs = AtomicLong(0)
    private val outputSinkOffers = AtomicLong(0)
    private val outputSinkRejects = AtomicLong(0)
    private val outputSinkFailures = AtomicLong(0)
    private val pauseCommits = AtomicLong(0)

    @Volatile
    private var state = RealtimeTranscriptionSessionRunnerState.NEW

    @Volatile
    private var lastProcessedBoundary: RealtimeProcessedCanonicalBoundary? = null

    @Volatile
    private var lastSessionEndReason: RealtimeCanonicalSessionEndReason? = null

    @Volatile
    private var terminalReason: RealtimeCanonicalSessionEndReason? = null

    @Volatile
    private var lastFailure: String? = null

    /** May be invoked exactly once. The caller owns the surrounding Job/cancellation policy. */
    suspend fun run() {
        check(started.compareAndSet(false, true)) {
            "RealtimeTranscriptionSessionRunner.run() may only start once"
        }
        state = RealtimeTranscriptionSessionRunnerState.RUNNING
        try {
            coroutineScope {
                val pump = launch { coordinator.run() }
                try {
                    consumeUntilTerminal()
                } finally {
                    pump.cancelAndJoin()
                }
            }
            if (state == RealtimeTranscriptionSessionRunnerState.RUNNING) {
                state = RealtimeTranscriptionSessionRunnerState.TERMINATED
            }
        } catch (cancelled: CancellationException) {
            state = RealtimeTranscriptionSessionRunnerState.TERMINATED
            throw cancelled
        } catch (failure: Throwable) {
            lastFailure = "${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
            state = RealtimeTranscriptionSessionRunnerState.FAILED
            throw failure
        }
    }

    /**
     * Finalizes the recognizer continuity only after the app-level pause drain barrier has proven the
     * exact canonical boundary and this runner has processed that same frame boundary.
     *
     * The exact re-check while holding [pipelineMutex] prevents RESUME media from racing between the
     * boundary wait and `onPauseCommitted()`.
     */
    suspend fun finalizePausedContinuity(
        sessionId: Long,
        epochId: Long,
        endSampleIndexExclusive: Long,
        maxWaitMs: Long = DEFAULT_PAUSE_WAIT_MS,
        pollMs: Long = DEFAULT_PAUSE_POLL_MS,
    ): RealtimePauseFinalizationOutcome {
        require(sessionId > 0L)
        require(epochId > 0L)
        require(endSampleIndexExclusive > 0L)
        require(maxWaitMs > 0L)
        require(pollMs > 0L)
        if (state != RealtimeTranscriptionSessionRunnerState.RUNNING) {
            return RealtimePauseFinalizationOutcome(
                status = RealtimePauseFinalizationStatus.NOT_RUNNING,
                processedBoundary = lastProcessedBoundary,
            )
        }

        val reached =
            withTimeoutOrNull(maxWaitMs) {
                while (true) {
                    when (
                        boundaryState(
                            lastProcessedBoundary,
                            sessionId,
                            epochId,
                            endSampleIndexExclusive,
                        )
                    ) {
                        BoundaryState.EXACT -> return@withTimeoutOrNull true
                        BoundaryState.OVERRUN -> return@withTimeoutOrNull false
                        BoundaryState.WAITING -> delay(pollMs)
                    }
                }
            }
        if (reached != true) {
            val overrun =
                boundaryState(
                    lastProcessedBoundary,
                    sessionId,
                    epochId,
                    endSampleIndexExclusive,
                ) == BoundaryState.OVERRUN
            return RealtimePauseFinalizationOutcome(
                status =
                    if (overrun) {
                        RealtimePauseFinalizationStatus.BOUNDARY_OVERRUN
                    } else {
                        RealtimePauseFinalizationStatus.TIMED_OUT
                    },
                processedBoundary = lastProcessedBoundary,
            )
        }

        return pipelineMutex.withLock {
            if (state != RealtimeTranscriptionSessionRunnerState.RUNNING) {
                return@withLock RealtimePauseFinalizationOutcome(
                    status = RealtimePauseFinalizationStatus.NOT_RUNNING,
                    processedBoundary = lastProcessedBoundary,
                )
            }
            if (
                boundaryState(
                    lastProcessedBoundary,
                    sessionId,
                    epochId,
                    endSampleIndexExclusive,
                ) != BoundaryState.EXACT
            ) {
                return@withLock RealtimePauseFinalizationOutcome(
                    status = RealtimePauseFinalizationStatus.BOUNDARY_OVERRUN,
                    processedBoundary = lastProcessedBoundary,
                )
            }

            val outputs = processor.onPauseCommitted()
            offerOutputs(outputs)
            pauseCommits.incrementAndGet()
            RealtimePauseFinalizationOutcome(
                status = RealtimePauseFinalizationStatus.COMMITTED,
                emittedOutputs = outputs.size,
                processedBoundary = lastProcessedBoundary,
            )
        }
    }

    fun snapshot(): RealtimeTranscriptionSessionRunnerSnapshot =
        RealtimeTranscriptionSessionRunnerSnapshot(
            state = state,
            processedEvents = processedEvents.get(),
            processedPcmFrames = processedPcmFrames.get(),
            processedPcmSamples = processedPcmSamples.get(),
            emittedOutputs = emittedOutputs.get(),
            outputSinkOffers = outputSinkOffers.get(),
            outputSinkRejects = outputSinkRejects.get(),
            outputSinkFailures = outputSinkFailures.get(),
            pauseCommits = pauseCommits.get(),
            lastProcessedBoundary = lastProcessedBoundary,
            lastSessionEndReason = lastSessionEndReason,
            terminalReason = terminalReason,
            lastFailure = lastFailure,
            coordinator = coordinator.snapshot(),
        )

    private suspend fun consumeUntilTerminal() {
        while (true) {
            val event = coordinator.receive() ?: return
            processEvent(event)
            if (event is StreamingAudioEvent.SessionEnded) {
                lastSessionEndReason = event.reason
                if (event.reason.endsLogicalRealtimeRun()) {
                    terminalReason = event.reason
                    return
                }
            }
        }
    }

    private suspend fun processEvent(event: StreamingAudioEvent) {
        pipelineMutex.withLock {
            val outputs = processor.accept(event)
            offerOutputs(outputs)
            processedEvents.incrementAndGet()
            if (event is StreamingAudioEvent.PcmFrame) {
                processedPcmFrames.incrementAndGet()
                processedPcmSamples.addAndGet(event.frame.sampleCount.toLong())
                lastProcessedBoundary =
                    RealtimeProcessedCanonicalBoundary(
                        sessionId = event.frame.sessionId,
                        epochId = event.frame.epochId,
                        endSampleIndexExclusive = event.frame.endSampleIndexExclusive,
                    )
            }
        }
    }

    private fun offerOutputs(outputs: List<RealtimeTranscriptionOutput>) {
        if (outputs.isEmpty()) return
        emittedOutputs.addAndGet(outputs.size.toLong())
        outputs.forEach { output ->
            outputSinks.forEach { sink ->
                outputSinkOffers.incrementAndGet()
                try {
                    if (sink.offer(output) == RealtimeTranscriptionOutputSinkOfferResult.REJECTED) {
                        outputSinkRejects.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    outputSinkFailures.incrementAndGet()
                }
            }
        }
    }

    private fun boundaryState(
        processed: RealtimeProcessedCanonicalBoundary?,
        targetSessionId: Long,
        targetEpochId: Long,
        targetEndSampleIndexExclusive: Long,
    ): BoundaryState {
        processed ?: return BoundaryState.WAITING
        if (processed.sessionId != targetSessionId || processed.epochId != targetEpochId) {
            return BoundaryState.WAITING
        }
        return when {
            processed.endSampleIndexExclusive < targetEndSampleIndexExclusive -> BoundaryState.WAITING
            processed.endSampleIndexExclusive == targetEndSampleIndexExclusive -> BoundaryState.EXACT
            else -> BoundaryState.OVERRUN
        }
    }

    private fun RealtimeCanonicalSessionEndReason.endsLogicalRealtimeRun(): Boolean =
        this == RealtimeCanonicalSessionEndReason.STOP ||
            this == RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED ||
            this == RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED

    private enum class BoundaryState {
        WAITING,
        EXACT,
        OVERRUN,
    }

    private companion object {
        const val DEFAULT_PAUSE_WAIT_MS = 1_500L
        const val DEFAULT_PAUSE_POLL_MS = 5L
    }
}
