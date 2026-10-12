package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimePcmWavSegmentFinalization
import io.github.ioannes78.voica.audio.RealtimePcmWavSegmentWriter
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

fun interface RealtimeLocalAudioTargetFactory {
    fun createTarget(canonicalSessionId: Long): File
}

enum class RealtimeLocalAudioFailureCode {
    QUEUE_OVERFLOW,
    WORKER_FAILURE,
}

sealed interface RealtimeLocalAudioResult {
    val canonicalSessionId: Long?

    data class Finalized(
        override val canonicalSessionId: Long,
        val finalEpochId: Long?,
        val finalSampleIndexExclusive: Long,
        val endReason: RealtimeCanonicalSessionEndReason,
        val drainTimedOut: Boolean,
        val media: RealtimePcmWavSegmentFinalization,
    ) : RealtimeLocalAudioResult

    data class Failed(
        override val canonicalSessionId: Long?,
        val code: RealtimeLocalAudioFailureCode,
        val message: String,
        val salvagedMedia: RealtimePcmWavSegmentFinalization?,
    ) : RealtimeLocalAudioResult
}

data class RealtimeLocalAudioSideSinkSnapshot(
    val closed: Boolean,
    val degraded: Boolean,
    val activeSessionId: Long?,
    val acceptedEvents: Long,
    val acceptedPcmFrames: Long,
    val acceptedPcmSamples: Long,
    val rejectedEvents: Long,
    val finalizedSegments: Long,
    val salvagedFailedSegments: Long,
    val workerFailures: Long,
    val resultHandlerFailures: Long,
    val lastFailureCode: RealtimeLocalAudioFailureCode?,
    val lastFailureMessage: String?,
)

/**
 * Bounded asynchronous local-audio branch below the unique canonical source pump.
 *
 * [offer] only copies/enqueues canonical events and never performs file IO. The worker owns exactly
 * one [RealtimePcmWavSegmentWriter] per canonical session. If its bounded queue overflows, the sink
 * enters a terminal degraded state, rejects later events, and salvages only the contiguous PCM that
 * was already accepted; it never fills the missing interval with silence or pretends completeness.
 */
class RealtimeLocalAudioSideSink(
    scope: CoroutineScope,
    private val targetFactory: RealtimeLocalAudioTargetFactory,
    private val resultHandler: suspend (RealtimeLocalAudioResult) -> Unit,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamingAudioSideSink, Closeable {
    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    private val queue = Channel<StreamingAudioEvent>(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val degraded = AtomicBoolean(false)
    private val acceptedEvents = AtomicLong(0)
    private val acceptedPcmFrames = AtomicLong(0)
    private val acceptedPcmSamples = AtomicLong(0)
    private val rejectedEvents = AtomicLong(0)
    private val finalizedSegments = AtomicLong(0)
    private val salvagedFailedSegments = AtomicLong(0)
    private val workerFailures = AtomicLong(0)
    private val resultHandlerFailures = AtomicLong(0)

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var lastFailureCode: RealtimeLocalAudioFailureCode? = null

    @Volatile
    private var lastFailureMessage: String? = null

    private var activeContext: SessionContext? = null
    private val worker: Job = scope.launch(workerDispatcher) { runWorker() }

    override fun offer(event: StreamingAudioEvent): StreamingAudioSideSinkOfferResult {
        if (closed.get() || degraded.get()) {
            rejectedEvents.incrementAndGet()
            return StreamingAudioSideSinkOfferResult.REJECTED
        }

        val queuedEvent = event.copyForAsyncOwnership()
        val offered = queue.trySend(queuedEvent)
        if (offered.isSuccess) {
            acceptedEvents.incrementAndGet()
            if (queuedEvent is StreamingAudioEvent.PcmFrame) {
                acceptedPcmFrames.incrementAndGet()
                acceptedPcmSamples.addAndGet(queuedEvent.frame.sampleCount.toLong())
            }
            return StreamingAudioSideSinkOfferResult.ACCEPTED
        }

        rejectedEvents.incrementAndGet()
        if (!offered.isClosed && degraded.compareAndSet(false, true)) {
            recordFailure(
                RealtimeLocalAudioFailureCode.QUEUE_OVERFLOW,
                "local realtime audio queue overflowed; persistence is incomplete",
            )
            queue.close(LocalAudioQueueOverflowException())
        }
        return StreamingAudioSideSinkOfferResult.REJECTED
    }

    fun snapshot(): RealtimeLocalAudioSideSinkSnapshot =
        RealtimeLocalAudioSideSinkSnapshot(
            closed = closed.get(),
            degraded = degraded.get(),
            activeSessionId = activeSessionId,
            acceptedEvents = acceptedEvents.get(),
            acceptedPcmFrames = acceptedPcmFrames.get(),
            acceptedPcmSamples = acceptedPcmSamples.get(),
            rejectedEvents = rejectedEvents.get(),
            finalizedSegments = finalizedSegments.get(),
            salvagedFailedSegments = salvagedFailedSegments.get(),
            workerFailures = workerFailures.get(),
            resultHandlerFailures = resultHandlerFailures.get(),
            lastFailureCode = lastFailureCode,
            lastFailureMessage = lastFailureMessage,
        )

    suspend fun join() {
        worker.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
    }

    private suspend fun runWorker() {
        try {
            for (event in queue) {
                handle(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            degraded.set(true)
            workerFailures.incrementAndGet()
            val code =
                if (failure is LocalAudioQueueOverflowException) {
                    RealtimeLocalAudioFailureCode.QUEUE_OVERFLOW
                } else {
                    RealtimeLocalAudioFailureCode.WORKER_FAILURE
                }
            recordFailure(code, failure.message ?: failure::class.java.simpleName)
            queue.close(failure)
            deliverFailure(code, failure)
        } finally {
            activeContext?.writer?.close()
            activeContext = null
            activeSessionId = null
        }
    }

    private suspend fun handle(event: StreamingAudioEvent) {
        when (event) {
            is StreamingAudioEvent.SessionStarted -> startSession(event.sessionId)

            is StreamingAudioEvent.EpochStarted -> {
                requireActiveSession(event.sessionId)
            }

            is StreamingAudioEvent.PcmFrame -> {
                val context = requireActiveSession(event.sessionId)
                context.writer.accept(event.frame)
            }

            is StreamingAudioEvent.Discontinuity -> {
                val context = requireActiveSession(event.sessionId)
                context.writer.markDiscontinuity(event.epochId, event.resumeAtSampleIndex)
            }

            is StreamingAudioEvent.SessionEnded -> finalizeSession(event)
        }
    }

    private fun startSession(sessionId: Long) {
        require(sessionId > 0L)
        check(activeContext == null) {
            "new canonical session arrived before local audio segment terminated"
        }
        val target = targetFactory.createTarget(sessionId)
        activeContext = SessionContext(sessionId, RealtimePcmWavSegmentWriter(target, sessionId))
        activeSessionId = sessionId
    }

    private suspend fun finalizeSession(event: StreamingAudioEvent.SessionEnded) {
        val context = requireActiveSession(event.sessionId)
        val media = context.writer.finalizeSegment()
        activeContext = null
        activeSessionId = null
        finalizedSegments.incrementAndGet()
        deliver(
            RealtimeLocalAudioResult.Finalized(
                canonicalSessionId = event.sessionId,
                finalEpochId = event.finalEpochId,
                finalSampleIndexExclusive = event.finalSampleIndexExclusive,
                endReason = event.reason,
                drainTimedOut = event.drainTimedOut,
                media = media,
            ),
        )
    }

    private fun requireActiveSession(sessionId: Long): SessionContext {
        val context = checkNotNull(activeContext) {
            "canonical event arrived without active local audio session"
        }
        require(context.sessionId == sessionId) {
            "canonical session mismatch in local audio worker"
        }
        return context
    }

    private suspend fun deliverFailure(
        code: RealtimeLocalAudioFailureCode,
        failure: Throwable,
    ) {
        val context = activeContext
        val salvaged = context?.let { current ->
            runCatching { current.writer.finalizeSegment() }
                .onFailure { current.writer.close() }
                .getOrNull()
        }
        if (salvaged != null) salvagedFailedSegments.incrementAndGet()
        activeContext = null
        activeSessionId = null
        try {
            resultHandler(
                RealtimeLocalAudioResult.Failed(
                    canonicalSessionId = context?.sessionId,
                    code = code,
                    message = failure.message ?: failure::class.java.simpleName,
                    salvagedMedia = salvaged,
                ),
            )
        } catch (_: Throwable) {
            resultHandlerFailures.incrementAndGet()
        }
    }

    private suspend fun deliver(result: RealtimeLocalAudioResult) {
        try {
            resultHandler(result)
        } catch (failure: Throwable) {
            resultHandlerFailures.incrementAndGet()
            throw ResultHandlerException(failure)
        }
    }

    private fun recordFailure(code: RealtimeLocalAudioFailureCode, message: String) {
        lastFailureCode = code
        lastFailureMessage = message
    }

    private fun StreamingAudioEvent.copyForAsyncOwnership(): StreamingAudioEvent =
        if (this is StreamingAudioEvent.PcmFrame) {
            copy(frame = frame.copy(samples = frame.samples.copyOf()))
        } else {
            this
        }

    private data class SessionContext(
        val sessionId: Long,
        val writer: RealtimePcmWavSegmentWriter,
    )

    private class LocalAudioQueueOverflowException : IllegalStateException(
        "local realtime audio queue overflow",
    )

    private class ResultHandlerException(cause: Throwable) : IllegalStateException(
        "local realtime audio result handler failed",
        cause,
    )

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 128
    }
}
