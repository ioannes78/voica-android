package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.model.ModelDescriptor
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeFirstAudioContractTest {
    @Test
    fun `late VAD activation never drops canonical audio before first ASR accept`() = runBlocking {
        var nowNs = 0L
        val asr = CapturingAsrEngine()
        val vad =
            SequenceVadEngine(
                listOf(
                    StreamingVadUpdate(false),
                    StreamingVadUpdate(false),
                    StreamingVadUpdate(false),
                    StreamingVadUpdate(true),
                ),
            )
        val processor =
            RealtimeStreamingAsrProcessor(
                asrEngine = asr,
                vadEngine = vad,
                config = RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
                monotonicNs = { nowNs.also { nowNs += 1_000_000L } },
            )

        processor.accept(StreamingAudioEvent.SessionStarted(1))
        processor.accept(
            StreamingAudioEvent.EpochStarted(
                sessionId = 1,
                epochId = 1,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )

        listOf(0L, 4L, 8L, 12L).forEach { start ->
            processor.accept(StreamingAudioEvent.PcmFrame(frame(start)))
        }

        assertEquals(1, asr.sessions.size)
        assertEquals(16, asr.sessions.single().acceptedSamples)
        val startup = processor.startupSnapshot()
        assertEquals(0L, startup.firstPcmSampleIndex)
        assertEquals(0L, startup.firstAsrAcceptedSampleIndex)
        assertEquals(0L, startup.skippedSamplesBeforeFirstAsrAccept)
        assertEquals(16L, startup.firstVadSpeechSampleIndex)
        assertNotNull(startup.firstAsrAcceptLatencyMs)
        assertTrue(startup.firstAsrAcceptLatencyMs!! >= 0.0)
    }

    @Test
    fun `new recognizer continuity after VAD final starts at next canonical chunk`() = runBlocking {
        val asr = CapturingAsrEngine()
        val vad =
            SequenceVadEngine(
                listOf(
                    StreamingVadUpdate(true),
                    StreamingVadUpdate(
                        false,
                        listOf(StreamingVadSpeechSegment(0, 8)),
                    ),
                    StreamingVadUpdate(false),
                ),
            )
        val processor =
            RealtimeStreamingAsrProcessor(
                asrEngine = asr,
                vadEngine = vad,
                config = RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
            )
        processor.accept(StreamingAudioEvent.SessionStarted(1))
        processor.accept(
            StreamingAudioEvent.EpochStarted(
                sessionId = 1,
                epochId = 1,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )

        processor.accept(StreamingAudioEvent.PcmFrame(frame(0)))
        val finalOutputs = processor.accept(StreamingAudioEvent.PcmFrame(frame(4)))
        val final =
            finalOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()
                .single { it.hypothesis.isFinal }
        assertEquals(0L, final.recognizerBaseSampleIndex)
        assertEquals(8L, final.acceptedEndSampleIndexExclusive)

        val nextOutputs = processor.accept(StreamingAudioEvent.PcmFrame(frame(8)))
        val boundary =
            nextOutputs.filterIsInstance<RealtimeAsrPipelineEvent.ContinuityStarted>().single()
        val partial =
            nextOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()
                .single { !it.hypothesis.isFinal }
        assertEquals(8L, boundary.startSampleIndex)
        assertEquals(8L, partial.recognizerBaseSampleIndex)
        assertEquals(12L, partial.acceptedEndSampleIndexExclusive)
    }

    private fun frame(start: Long) =
        RealtimeCanonicalPcmFrame(
            sessionId = 1,
            epochId = 1,
            startSampleIndex = start,
            samples = ShortArray(4) { 1_000 },
            epochStartReason =
                if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
        )

    private class CapturingAsrEngine : StreamingAsrEngine {
        override val model: ModelDescriptor
            get() = error("not used")
        override val capabilities: AsrCapabilities
            get() = error("not used")
        val sessions = mutableListOf<CapturingAsrSession>()

        override suspend fun openSession(): StreamingAsrSession =
            CapturingAsrSession().also(sessions::add)

        override fun close() = Unit
    }

    private class CapturingAsrSession : StreamingAsrSession {
        var acceptedSamples = 0

        override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) {
            acceptedSamples += count
        }

        override suspend fun decode(): AsrHypothesis =
            AsrHypothesis(
                text = "partial-$acceptedSamples",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis =
            AsrHypothesis(
                text = "final-$acceptedSamples",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )

        override suspend fun reset() = Unit
        override fun close() = Unit
    }

    private class SequenceVadEngine(
        updates: List<StreamingVadUpdate>,
    ) : StreamingVadEngine {
        override val model: ModelDescriptor
            get() = error("not used")
        private val updates = ArrayDeque(updates)

        override suspend fun openSession(): StreamingVadSession =
            object : StreamingVadSession {
                override suspend fun acceptSamples(
                    samples: ShortArray,
                    offset: Int,
                    count: Int,
                ): StreamingVadUpdate =
                    if (updates.isEmpty()) StreamingVadUpdate(false) else updates.removeFirst()

                override suspend fun finishInput(): StreamingVadUpdate = StreamingVadUpdate(false)
                override suspend fun reset() = Unit
                override fun close() = Unit
            }

        override fun close() = Unit
    }
}
