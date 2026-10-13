package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RealtimeAsrFinalizationReason
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage16ARealtimeOutputLatencyProbeTest {
    @Test
    fun `partial latency is measured from canonical frame ingestion to output return`() =
        runBlocking {
            var now = 1_000_000_000L
            val delegate = FakeProcessor(
                onAccept = {
                    now += 25_000_000L
                    listOf(output(AsrHypothesisStability.PARTIAL, acceptedEnd = 1_280L))
                },
            )
            val probe = Stage16ARealtimeOutputLatencyProbe(delegate, nowNanos = { now })

            probe.accept(frame(start = 0L, samples = 1_280))

            val snapshot = probe.snapshot()
            assertEquals(1L, snapshot.partial.count)
            assertEquals(25.0, snapshot.partial.averageMs!!, 0.0001)
            assertEquals(25.0, snapshot.partial.minMs!!, 0.0001)
            assertEquals(25.0, snapshot.partial.maxMs!!, 0.0001)
            assertEquals(1L, snapshot.correlatedOutputs)
            assertEquals(0L, snapshot.uncorrelatedOutputs)
        }

    @Test
    fun `chunk ending inside canonical frame correlates to enclosing frame arrival`() =
        runBlocking {
            var now = 0L
            val delegate = FakeProcessor(
                onAccept = {
                    now += 12_000_000L
                    listOf(output(AsrHypothesisStability.STABLE, acceptedEnd = 640L))
                },
            )
            val probe = Stage16ARealtimeOutputLatencyProbe(delegate, nowNanos = { now })

            probe.accept(frame(start = 0L, samples = 1_280))

            val snapshot = probe.snapshot()
            assertEquals(1L, snapshot.stable.count)
            assertEquals(12.0, snapshot.stable.averageMs!!, 0.0001)
        }

    @Test
    fun `pause final latency includes time since last correlated pcm boundary`() =
        runBlocking {
            var now = 0L
            val delegate = FakeProcessor(
                onAccept = { emptyList() },
                onPause = {
                    now += 20_000_000L
                    listOf(output(AsrHypothesisStability.FINAL, acceptedEnd = 1_280L))
                },
            )
            val probe = Stage16ARealtimeOutputLatencyProbe(delegate, nowNanos = { now })

            probe.accept(frame(start = 0L, samples = 1_280))
            now += 180_000_000L
            probe.onPauseCommitted()

            val snapshot = probe.snapshot()
            assertEquals(1L, snapshot.final.count)
            assertEquals(200.0, snapshot.final.averageMs!!, 0.0001)
            assertEquals(200.0, snapshot.final.maxMs!!, 0.0001)
        }

    @Test
    fun `missing boundary is reported as uncorrelated instead of fabricated latency`() =
        runBlocking {
            var now = 0L
            val delegate = FakeProcessor(
                onPause = {
                    now += 5_000_000L
                    listOf(output(AsrHypothesisStability.FINAL, acceptedEnd = 1_280L))
                },
            )
            val probe = Stage16ARealtimeOutputLatencyProbe(delegate, nowNanos = { now })

            probe.onPauseCommitted()

            val snapshot = probe.snapshot()
            assertEquals(0L, snapshot.final.count)
            assertNull(snapshot.final.averageMs)
            assertEquals(0L, snapshot.correlatedOutputs)
            assertEquals(1L, snapshot.uncorrelatedOutputs)
        }

    private fun frame(
        start: Long,
        samples: Int,
    ): StreamingAudioEvent.PcmFrame =
        StreamingAudioEvent.PcmFrame(
            RealtimeCanonicalPcmFrame(
                sessionId = 1L,
                epochId = 1L,
                startSampleIndex = start,
                samples = ShortArray(samples),
                epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
            ),
        )

    private fun output(
        stability: AsrHypothesisStability,
        acceptedEnd: Long,
    ): RealtimeTranscriptionOutput.Hypothesis {
        val isFinal = stability == AsrHypothesisStability.FINAL
        val nativeHypothesis =
            AsrHypothesis(
                text = "测试",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = isFinal,
                stability = if (isFinal) AsrHypothesisStability.FINAL else AsrHypothesisStability.PARTIAL,
            )
        val displayHypothesis = nativeHypothesis.copy(stability = stability)
        return RealtimeTranscriptionOutput.Hypothesis(
            source =
                RealtimeAsrPipelineEvent.Hypothesis(
                    sessionId = 1L,
                    epochId = 1L,
                    continuityIndex = 1L,
                    recognizerBaseSampleIndex = 0L,
                    acceptedEndSampleIndexExclusive = acceptedEnd,
                    hypothesis = nativeHypothesis,
                    finalizationReason =
                        if (isFinal) RealtimeAsrFinalizationReason.VAD_SPEECH_END else null,
                ),
            rawText = "测试",
            displayHypothesis = displayHypothesis,
            canonicalTokens = emptyList(),
            punctuationAttempted = false,
            externalPunctuationApplied = false,
            externalPunctuationFailed = false,
        )
    }

    private class FakeProcessor(
        private val onAccept: suspend (StreamingAudioEvent) -> List<RealtimeTranscriptionOutput> = { emptyList() },
        private val onPause: suspend () -> List<RealtimeTranscriptionOutput> = { emptyList() },
    ) : RealtimeTranscriptionEventProcessor {
        override suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> =
            onAccept(event)

        override suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> = onPause()
    }
}
