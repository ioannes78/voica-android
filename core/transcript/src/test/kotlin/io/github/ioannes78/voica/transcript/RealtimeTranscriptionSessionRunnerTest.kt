package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import io.github.ioannes78.voica.audio.StreamingAudioBuffer
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTranscriptionSessionRunnerTest {
    @Test
    fun `runner survives transport close and terminates only on logical recording end`() = runBlocking {
        val source =
            ListSource(
                listOf(
                    RealtimeCanonicalStreamEvent.SessionStarted(1),
                    RealtimeCanonicalStreamEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
                    RealtimeCanonicalStreamEvent.PcmFrame(frame(1, 1, 0)),
                    RealtimeCanonicalStreamEvent.SessionEnded(
                        sessionId = 1,
                        finalEpochId = 1,
                        finalSampleIndexExclusive = 4,
                        reason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                        drainTimedOut = false,
                    ),
                    RealtimeCanonicalStreamEvent.SessionStarted(2),
                    RealtimeCanonicalStreamEvent.EpochStarted(2, 1, RealtimeCanonicalEpochStartReason.SESSION_START),
                    RealtimeCanonicalStreamEvent.PcmFrame(frame(2, 1, 0)),
                    RealtimeCanonicalStreamEvent.SessionEnded(
                        sessionId = 2,
                        finalEpochId = 1,
                        finalSampleIndexExclusive = 4,
                        reason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = false,
                    ),
                ),
            )
        val processor = RecordingProcessor()
        val runner = runner(source, processor)

        runner.run()

        assertEquals(8, processor.events.size)
        assertTrue(
            processor.events.any {
                it is StreamingAudioEvent.SessionEnded &&
                    it.reason == RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED
            },
        )
        val snapshot = runner.snapshot()
        assertEquals(RealtimeTranscriptionSessionRunnerState.TERMINATED, snapshot.state)
        assertEquals(2L, snapshot.processedPcmFrames)
        assertEquals(8L, snapshot.processedPcmSamples)
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, snapshot.terminalReason)
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, snapshot.lastSessionEndReason)
    }

    @Test
    fun `pause finalization waits for exact processed canonical boundary`() = runBlocking {
        val source = ChannelSource()
        val processor = RecordingProcessor()
        val runner = runner(source, processor)
        val runJob = launch { runner.run() }

        source.send(RealtimeCanonicalStreamEvent.SessionStarted(7))
        source.send(
            RealtimeCanonicalStreamEvent.EpochStarted(
                7,
                3,
                RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        source.send(RealtimeCanonicalStreamEvent.PcmFrame(frame(7, 3, 0)))
        withTimeout(1_000) {
            while (runner.snapshot().lastProcessedBoundary?.endSampleIndexExclusive != 4L) delay(1)
        }

        val pause =
            async {
                runner.finalizePausedContinuity(
                    sessionId = 7,
                    epochId = 3,
                    endSampleIndexExclusive = 8,
                    maxWaitMs = 1_000,
                    pollMs = 1,
                )
            }
        delay(20)
        assertFalse(pause.isCompleted)
        assertEquals(0, processor.pauseCalls)

        source.send(RealtimeCanonicalStreamEvent.PcmFrame(frame(7, 3, 4)))
        val outcome = withTimeout(1_000) { pause.await() }
        assertEquals(RealtimePauseFinalizationStatus.COMMITTED, outcome.status)
        assertEquals(8L, outcome.processedBoundary?.endSampleIndexExclusive)
        assertEquals(1, processor.pauseCalls)

        source.send(
            RealtimeCanonicalStreamEvent.SessionEnded(
                sessionId = 7,
                finalEpochId = 3,
                finalSampleIndexExclusive = 8,
                reason = RealtimeCanonicalSessionEndReason.STOP,
                drainTimedOut = false,
            ),
        )
        source.close()
        withTimeout(1_000) { runJob.join() }
    }

    @Test
    fun `pause finalization rejects boundary overrun`() = runBlocking {
        val source = ChannelSource()
        val processor = RecordingProcessor()
        val runner = runner(source, processor)
        val runJob = launch { runner.run() }

        source.send(RealtimeCanonicalStreamEvent.SessionStarted(1))
        source.send(
            RealtimeCanonicalStreamEvent.EpochStarted(
                1,
                1,
                RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        source.send(RealtimeCanonicalStreamEvent.PcmFrame(frame(1, 1, 0)))
        source.send(RealtimeCanonicalStreamEvent.PcmFrame(frame(1, 1, 4)))
        withTimeout(1_000) {
            while (runner.snapshot().lastProcessedBoundary?.endSampleIndexExclusive != 8L) delay(1)
        }

        val outcome =
            runner.finalizePausedContinuity(
                sessionId = 1,
                epochId = 1,
                endSampleIndexExclusive = 4,
                maxWaitMs = 50,
                pollMs = 1,
            )
        assertEquals(RealtimePauseFinalizationStatus.BOUNDARY_OVERRUN, outcome.status)
        assertEquals(0, processor.pauseCalls)

        source.send(
            RealtimeCanonicalStreamEvent.SessionEnded(
                sessionId = 1,
                finalEpochId = 1,
                finalSampleIndexExclusive = 8,
                reason = RealtimeCanonicalSessionEndReason.STOP,
                drainTimedOut = false,
            ),
        )
        source.close()
        runJob.join()
    }

    @Test
    fun `output sink rejection and exception are isolated from realtime inference`() = runBlocking {
        val source =
            ListSource(
                listOf(
                    RealtimeCanonicalStreamEvent.SessionStarted(4),
                    RealtimeCanonicalStreamEvent.SessionEnded(
                        sessionId = 4,
                        finalEpochId = null,
                        finalSampleIndexExclusive = 0,
                        reason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = false,
                    ),
                ),
            )
        val processor = RecordingProcessor(emitLifecycle = true)
        val rejecting = RealtimeTranscriptionOutputSink { RealtimeTranscriptionOutputSinkOfferResult.REJECTED }
        val failing = RealtimeTranscriptionOutputSink { error("sink failure") }
        val runner = runner(source, processor, listOf(rejecting, failing))

        runner.run()

        val snapshot = runner.snapshot()
        assertEquals(RealtimeTranscriptionSessionRunnerState.TERMINATED, snapshot.state)
        assertEquals(2L, snapshot.emittedOutputs)
        assertEquals(4L, snapshot.outputSinkOffers)
        assertEquals(2L, snapshot.outputSinkRejects)
        assertEquals(2L, snapshot.outputSinkFailures)
        assertEquals(RealtimeCanonicalSessionEndReason.STOP, snapshot.terminalReason)
    }

    private fun runner(
        source: CanonicalRealtimeEventSource,
        processor: RealtimeTranscriptionEventProcessor,
        sinks: List<RealtimeTranscriptionOutputSink> = emptyList(),
    ): RealtimeTranscriptionSessionRunner {
        val coordinator =
            RealtimeTranscriptCoordinator(
                source = source,
                outputBuffer = StreamingAudioBuffer(maxQueuedPcmFrames = 8, maxQueuedPcmSamples = 64),
            )
        return RealtimeTranscriptionSessionRunner(coordinator, processor, sinks)
    }

    private fun frame(sessionId: Long, epochId: Long, start: Long) =
        RealtimeCanonicalPcmFrame(
            sessionId = sessionId,
            epochId = epochId,
            startSampleIndex = start,
            samples = shortArrayOf(1, 2, 3, 4),
            epochStartReason =
                if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
        )

    private class RecordingProcessor(
        private val emitLifecycle: Boolean = false,
    ) : RealtimeTranscriptionEventProcessor {
        val events = mutableListOf<StreamingAudioEvent>()
        var pauseCalls = 0

        override suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> {
            events += event
            if (!emitLifecycle) return emptyList()
            return when (event) {
                is StreamingAudioEvent.SessionStarted ->
                    listOf(
                        RealtimeTranscriptionOutput.Lifecycle(
                            RealtimeAsrPipelineEvent.SessionStarted(event.sessionId),
                        ),
                    )

                is StreamingAudioEvent.SessionEnded ->
                    listOf(
                        RealtimeTranscriptionOutput.Lifecycle(
                            RealtimeAsrPipelineEvent.SessionEnded(
                                sessionId = event.sessionId,
                                reason = event.reason,
                                drainTimedOut = event.drainTimedOut,
                            ),
                        ),
                    )

                else -> emptyList()
            }
        }

        override suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> {
            pauseCalls += 1
            return emptyList()
        }
    }

    private class ListSource(events: List<RealtimeCanonicalStreamEvent>) : CanonicalRealtimeEventSource {
        private val events = ArrayDeque(events)

        override suspend fun receive(): RealtimeCanonicalStreamEvent? =
            if (events.isEmpty()) null else events.removeFirst()
    }

    private class ChannelSource : CanonicalRealtimeEventSource {
        private val channel = Channel<RealtimeCanonicalStreamEvent>(Channel.UNLIMITED)

        suspend fun send(event: RealtimeCanonicalStreamEvent) {
            channel.send(event)
        }

        fun close() {
            channel.close()
        }

        override suspend fun receive(): RealtimeCanonicalStreamEvent? =
            channel.receiveCatching().getOrNull()
    }
}
