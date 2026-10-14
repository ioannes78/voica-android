package io.github.ioannes78.voica.transcript

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class RealtimeDurableOutputFailureCode {
    QUEUE_OVERFLOW,
    PERSISTENCE_FAILURE,
}

data class RealtimeDurableOutputFailure(
    val code: RealtimeDurableOutputFailureCode,
    val message: String,
)

data class RealtimeDurableOutputSinkSnapshot(
    val closed: Boolean,
    val workerCompleted: Boolean,
    val degraded: Boolean,
    val offeredOutputs: Long,
    val acceptedDurableOutputs: Long,
    val filteredEphemeralOutputs: Long,
    val rejectedDurableOutputs: Long,
    val persistedOutputs: Long,
    val overflowFailures: Long,
    val persistenceFailures: Long,
    val failureHandlerFailures: Long,
    val lastFailure: RealtimeDurableOutputFailure?,
)

/**
 * Bounded non-blocking persistence branch below realtime inference.
 *
 * Only durable product facts enter this queue: FINAL hypotheses and lifecycle events required to
 * reconstruct session/continuity/discontinuity/end lineage. PARTIAL/STABLE hypotheses and VAD-only
 * segments remain ephemeral so high-frequency live text can never flood Room persistence.
 *
 * [offer] never suspends and never performs Room/file IO. Queue overflow is a terminal degraded
 * condition: already accepted prefix items are drained in order, later outputs are rejected, and the
 * failure callback is invoked from the worker. A persistence exception also degrades the branch and
 * discards later queued work because durable ordering can no longer be guaranteed. Neither failure
 * propagates into the ASR/session runner or device recording lifecycle.
 */
class RealtimeDurableOutputSink(
    scope: CoroutineScope,
    private val persist: suspend (RealtimeTranscriptionOutput) -> Unit,
    private val onFailure: suspend (RealtimeDurableOutputFailure) -> Unit = {},
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RealtimeTranscriptionOutputSink, Closeable {
    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    private val queue = Channel<RealtimeTranscriptionOutput>(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val workerCompleted = AtomicBoolean(false)
    private val degraded = AtomicBoolean(false)
    private val offeredOutputs = AtomicLong(0)
    private val acceptedDurableOutputs = AtomicLong(0)
    private val filteredEphemeralOutputs = AtomicLong(0)
    private val rejectedDurableOutputs = AtomicLong(0)
    private val persistedOutputs = AtomicLong(0)
    private val overflowFailures = AtomicLong(0)
    private val persistenceFailures = AtomicLong(0)
    private val failureHandlerFailures = AtomicLong(0)

    @Volatile
    private var lastFailure: RealtimeDurableOutputFailure? = null

    private val worker: Job = scope.launch(workerDispatcher) { runWorker() }

    override fun offer(output: RealtimeTranscriptionOutput): RealtimeTranscriptionOutputSinkOfferResult {
        offeredOutputs.incrementAndGet()
        if (!output.requiresDurablePersistence()) {
            filteredEphemeralOutputs.incrementAndGet()
            return RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED
        }
        if (closed.get() || degraded.get()) {
            rejectedDurableOutputs.incrementAndGet()
            return RealtimeTranscriptionOutputSinkOfferResult.REJECTED
        }

        val result = queue.trySend(output.copyForAsyncOwnership())
        if (result.isSuccess) {
            acceptedDurableOutputs.incrementAndGet()
            return RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED
        }

        rejectedDurableOutputs.incrementAndGet()
        if (!result.isClosed && degraded.compareAndSet(false, true)) {
            overflowFailures.incrementAndGet()
            val failure =
                RealtimeDurableOutputFailure(
                    code = RealtimeDurableOutputFailureCode.QUEUE_OVERFLOW,
                    message = "realtime durable output queue overflowed",
                )
            lastFailure = failure
            queue.close(DurableOutputQueueOverflowException())
        }
        return RealtimeTranscriptionOutputSinkOfferResult.REJECTED
    }

    fun snapshot(): RealtimeDurableOutputSinkSnapshot =
        RealtimeDurableOutputSinkSnapshot(
            closed = closed.get(),
            workerCompleted = workerCompleted.get(),
            degraded = degraded.get(),
            offeredOutputs = offeredOutputs.get(),
            acceptedDurableOutputs = acceptedDurableOutputs.get(),
            filteredEphemeralOutputs = filteredEphemeralOutputs.get(),
            rejectedDurableOutputs = rejectedDurableOutputs.get(),
            persistedOutputs = persistedOutputs.get(),
            overflowFailures = overflowFailures.get(),
            persistenceFailures = persistenceFailures.get(),
            failureHandlerFailures = failureHandlerFailures.get(),
            lastFailure = lastFailure,
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
            for (output in queue) {
                persist(output)
                persistedOutputs.incrementAndGet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            degraded.set(true)
            val durableFailure =
                if (failure is DurableOutputQueueOverflowException) {
                    lastFailure
                        ?: RealtimeDurableOutputFailure(
                            RealtimeDurableOutputFailureCode.QUEUE_OVERFLOW,
                            failure.message ?: "realtime durable output queue overflowed",
                        )
                } else {
                    persistenceFailures.incrementAndGet()
                    RealtimeDurableOutputFailure(
                        code = RealtimeDurableOutputFailureCode.PERSISTENCE_FAILURE,
                        message = failure.message ?: failure.javaClass.simpleName,
                    ).also { lastFailure = it }
                }

            if (durableFailure.code == RealtimeDurableOutputFailureCode.PERSISTENCE_FAILURE) {
                queue.cancel(
                    CancellationException("durable persistence ordering lost after failure"),
                )
            }
            deliverFailure(durableFailure)
        } finally {
            workerCompleted.set(true)
        }
    }

    private suspend fun deliverFailure(failure: RealtimeDurableOutputFailure) {
        try {
            onFailure(failure)
        } catch (_: Throwable) {
            failureHandlerFailures.incrementAndGet()
        }
    }

    private fun RealtimeTranscriptionOutput.requiresDurablePersistence(): Boolean =
        when (this) {
            is RealtimeTranscriptionOutput.Hypothesis ->
                stability == AsrHypothesisStability.FINAL

            is RealtimeTranscriptionOutput.Lifecycle ->
                when (event) {
                    is RealtimeAsrPipelineEvent.SessionStarted,
                    is RealtimeAsrPipelineEvent.ContinuityStarted,
                    is RealtimeAsrPipelineEvent.Discontinuity,
                    is RealtimeAsrPipelineEvent.SessionEnded,
                    -> true

                    is RealtimeAsrPipelineEvent.VadSegment -> false
                    is RealtimeAsrPipelineEvent.Hypothesis ->
                        error("hypothesis must not be wrapped as lifecycle output")
                }
        }

    private fun RealtimeTranscriptionOutput.copyForAsyncOwnership(): RealtimeTranscriptionOutput =
        when (this) {
            is RealtimeTranscriptionOutput.Lifecycle -> this
            is RealtimeTranscriptionOutput.Hypothesis ->
                copy(
                    source =
                        source.copy(
                            hypothesis =
                                source.hypothesis.copy(
                                    tokens = source.hypothesis.tokens.toList(),
                                ),
                        ),
                    displayHypothesis =
                        displayHypothesis.copy(
                            tokens = displayHypothesis.tokens.toList(),
                        ),
                    canonicalTokens = canonicalTokens.toList(),
                )
        }

    private class DurableOutputQueueOverflowException : IllegalStateException(
        "realtime durable output queue overflowed",
    )

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
    }
}
