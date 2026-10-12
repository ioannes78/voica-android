package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDurableOutputSinkTest {
    @Test
    fun `sink filters ephemeral outputs and persists durable facts in order`() = runBlocking {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val persisted = mutableListOf<RealtimeTranscriptionOutput>()
        val sink =
            RealtimeDurableOutputSink(
                scope = scope,
                persist = { persisted += it },
                queueCapacity = 8,
                workerDispatcher = dispatcher,
            )
        try {
            val outputs =
                listOf(
                    lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)),
                    partial("正在"),
                    lifecycle(
                        RealtimeAsrPipelineEvent.ContinuityStarted(
                            sessionId = 1,
                            epochId = 1,
                            continuityIndex = 1,
                            startSampleIndex = null,
                            epochReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                        ),
                    ),
                    stable("正在测试"),
                    lifecycle(
                        RealtimeAsrPipelineEvent.VadSegment(
                            sessionId = 1,
                            epochId = 1,
                            continuityIndex = 1,
                            startSampleIndex = 0,
                            endSampleIndexExclusive = 320,
                        ),
                    ),
                    final("正在测试。"),
                    lifecycle(
                        RealtimeAsrPipelineEvent.Discontinuity(
                            sessionId = 1,
                            epochId = 1,
                            resumeAtSampleIndex = 640,
                            reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
                            droppedFrames = 1,
                            droppedSamples = 320,
                        ),
                    ),
                    lifecycle(
                        RealtimeAsrPipelineEvent.SessionEnded(
                            sessionId = 1,
                            reason = RealtimeCanonicalSessionEndReason.STOP,
                            drainTimedOut = false,
                        ),
                    ),
                )

            outputs.forEach { output ->
                assertEquals(
                    RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED,
                    sink.offer(output),
                )
            }
            sink.close()
            dispatcher.runAll()
            sink.join()

            assertEquals(5, persisted.size)
            assertTrue((persisted[0] as RealtimeTranscriptionOutput.Lifecycle).event is RealtimeAsrPipelineEvent.SessionStarted)
            assertTrue((persisted[1] as RealtimeTranscriptionOutput.Lifecycle).event is RealtimeAsrPipelineEvent.ContinuityStarted)
            assertEquals(AsrHypothesisStability.FINAL, (persisted[2] as RealtimeTranscriptionOutput.Hypothesis).stability)
            assertTrue((persisted[3] as RealtimeTranscriptionOutput.Lifecycle).event is RealtimeAsrPipelineEvent.Discontinuity)
            assertTrue((persisted[4] as RealtimeTranscriptionOutput.Lifecycle).event is RealtimeAsrPipelineEvent.SessionEnded)

            val snapshot = sink.snapshot()
            assertTrue(snapshot.closed)
            assertTrue(snapshot.workerCompleted)
            assertFalse(snapshot.degraded)
            assertEquals(8L, snapshot.offeredOutputs)
            assertEquals(5L, snapshot.acceptedDurableOutputs)
            assertEquals(3L, snapshot.filteredEphemeralOutputs)
            assertEquals(5L, snapshot.persistedOutputs)
            assertEquals(0L, snapshot.rejectedDurableOutputs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queue overflow drains accepted durable prefix then degrades`() = runBlocking {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val persisted = mutableListOf<RealtimeTranscriptionOutput>()
        val failures = mutableListOf<RealtimeDurableOutputFailure>()
        val sink =
            RealtimeDurableOutputSink(
                scope = scope,
                persist = { persisted += it },
                onFailure = { failures += it },
                queueCapacity = 2,
                workerDispatcher = dispatcher,
            )
        try {
            assertEquals(
                RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED,
                sink.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(9))),
            )
            assertEquals(
                RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED,
                sink.offer(
                    lifecycle(
                        RealtimeAsrPipelineEvent.ContinuityStarted(
                            sessionId = 9,
                            epochId = 1,
                            continuityIndex = 1,
                            startSampleIndex = null,
                            epochReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                        ),
                    ),
                ),
            )
            assertEquals(
                RealtimeTranscriptionOutputSinkOfferResult.REJECTED,
                sink.offer(final("不能入队")),
            )
            assertEquals(
                RealtimeTranscriptionOutputSinkOfferResult.REJECTED,
                sink.offer(lifecycle(sessionEnd(9))),
            )

            dispatcher.runAll()
            sink.join()

            assertEquals(2, persisted.size)
            assertEquals(1, failures.size)
            assertEquals(RealtimeDurableOutputFailureCode.QUEUE_OVERFLOW, failures.single().code)
            val snapshot = sink.snapshot()
            assertTrue(snapshot.degraded)
            assertEquals(2L, snapshot.acceptedDurableOutputs)
            assertEquals(2L, snapshot.rejectedDurableOutputs)
            assertEquals(2L, snapshot.persistedOutputs)
            assertEquals(1L, snapshot.overflowFailures)
        } finally {
            sink.close()
            scope.cancel()
        }
    }

    @Test
    fun `persistence exception stops later durable writes without escaping realtime path`() = runBlocking {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val attempted = mutableListOf<String>()
        val failures = mutableListOf<RealtimeDurableOutputFailure>()
        val sink =
            RealtimeDurableOutputSink(
                scope = scope,
                persist = { output ->
                    val key = output.key()
                    attempted += key
                    if (key == "continuity") error("room unavailable")
                },
                onFailure = { failures += it },
                queueCapacity = 4,
                workerDispatcher = dispatcher,
            )
        try {
            sink.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(3)))
            sink.offer(
                lifecycle(
                    RealtimeAsrPipelineEvent.ContinuityStarted(
                        sessionId = 3,
                        epochId = 1,
                        continuityIndex = 1,
                        startSampleIndex = null,
                        epochReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                    ),
                ),
            )
            sink.offer(final("后续 final 不应落库"))
            sink.close()
            dispatcher.runAll()
            sink.join()

            assertEquals(listOf("session", "continuity"), attempted)
            assertEquals(1, failures.size)
            assertEquals(RealtimeDurableOutputFailureCode.PERSISTENCE_FAILURE, failures.single().code)
            val snapshot = sink.snapshot()
            assertTrue(snapshot.degraded)
            assertEquals(1L, snapshot.persistedOutputs)
            assertEquals(1L, snapshot.persistenceFailures)
        } finally {
            scope.cancel()
        }
    }

    private fun lifecycle(event: RealtimeAsrPipelineEvent): RealtimeTranscriptionOutput.Lifecycle =
        RealtimeTranscriptionOutput.Lifecycle(event)

    private fun sessionEnd(sessionId: Long) =
        RealtimeAsrPipelineEvent.SessionEnded(
            sessionId = sessionId,
            reason = RealtimeCanonicalSessionEndReason.STOP,
            drainTimedOut = false,
        )

    private fun partial(text: String) = hypothesis(text, AsrHypothesisStability.PARTIAL)

    private fun stable(text: String) = hypothesis(text, AsrHypothesisStability.STABLE)

    private fun final(text: String) = hypothesis(text, AsrHypothesisStability.FINAL)

    private fun hypothesis(
        text: String,
        stability: AsrHypothesisStability,
    ): RealtimeTranscriptionOutput.Hypothesis {
        val isFinal = stability == AsrHypothesisStability.FINAL
        val display =
            AsrHypothesis(
                text = text,
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = isFinal,
                stability = stability,
            )
        val source =
            RealtimeAsrPipelineEvent.Hypothesis(
                sessionId = 1,
                epochId = 1,
                continuityIndex = 1,
                recognizerBaseSampleIndex = 0,
                acceptedEndSampleIndexExclusive = 320,
                hypothesis = display,
                finalizationReason =
                    if (isFinal) RealtimeAsrFinalizationReason.SESSION_END else null,
            )
        return RealtimeTranscriptionOutput.Hypothesis(
            source = source,
            rawText = text,
            displayHypothesis = display,
            canonicalTokens = emptyList(),
            punctuationAttempted = false,
            externalPunctuationApplied = false,
            externalPunctuationFailed = false,
        )
    }

    private fun RealtimeTranscriptionOutput.key(): String =
        when (this) {
            is RealtimeTranscriptionOutput.Hypothesis -> "hypothesis"
            is RealtimeTranscriptionOutput.Lifecycle ->
                when (event) {
                    is RealtimeAsrPipelineEvent.SessionStarted -> "session"
                    is RealtimeAsrPipelineEvent.ContinuityStarted -> "continuity"
                    is RealtimeAsrPipelineEvent.Discontinuity -> "discontinuity"
                    is RealtimeAsrPipelineEvent.SessionEnded -> "ended"
                    is RealtimeAsrPipelineEvent.VadSegment -> "vad"
                    is RealtimeAsrPipelineEvent.Hypothesis -> "invalid"
                }
        }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            var guard = 0
            while (tasks.isNotEmpty()) {
                check(guard++ < 1_000) { "manual dispatcher did not quiesce" }
                tasks.removeFirst().run()
            }
        }
    }
}
