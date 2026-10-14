package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.model.ModelDescriptor
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeMultiUtteranceContinuityTest {
    @Test
    fun `second VAD utterance starts fresh recognizer continuity before partial`() = runBlocking {
        val processor =
            RealtimeStreamingAsrProcessor(
                asrEngine = FakeAsrEngine(),
                vadEngine =
                    FakeVadEngine(
                        listOf(
                            StreamingVadUpdate(speechDetected = true),
                            StreamingVadUpdate(
                                speechDetected = false,
                                completedSegments = listOf(StreamingVadSpeechSegment(0, 8)),
                            ),
                            StreamingVadUpdate(speechDetected = true),
                            StreamingVadUpdate(
                                speechDetected = false,
                                completedSegments = listOf(StreamingVadSpeechSegment(8, 16)),
                            ),
                        ),
                    ),
                config = RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 4),
            )
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 2)
        val outputs = mutableListOf<RealtimeAsrPipelineEvent>()

        suspend fun accept(event: StreamingAudioEvent) {
            processor.accept(event).forEach { raw ->
                outputs += stabilizer.accept(raw)
            }
        }

        accept(StreamingAudioEvent.SessionStarted(1))
        accept(
            StreamingAudioEvent.EpochStarted(
                sessionId = 1,
                epochId = 7,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        listOf(0L, 4L, 8L, 12L).forEach { start ->
            accept(StreamingAudioEvent.PcmFrame(frame(start)))
        }

        val finals =
            outputs
                .filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()
                .filter { it.hypothesis.isFinal }
        assertEquals(listOf("final-1", "final-2"), finals.map { it.hypothesis.text })
        assertEquals(listOf(1L, 2L), finals.map { it.continuityIndex })

        val secondBoundaryIndex =
            outputs.indexOfFirst {
                it is RealtimeAsrPipelineEvent.ContinuityStarted && it.continuityIndex == 2L
            }
        val secondPartialIndex =
            outputs.indexOfFirst {
                it is RealtimeAsrPipelineEvent.Hypothesis &&
                    it.continuityIndex == 2L &&
                    !it.hypothesis.isFinal
            }
        assertTrue(secondBoundaryIndex >= 0)
        assertTrue(secondPartialIndex > secondBoundaryIndex)
        val secondBoundary = outputs[secondBoundaryIndex] as RealtimeAsrPipelineEvent.ContinuityStarted
        assertEquals(8L, secondBoundary.startSampleIndex)
        assertEquals(null, secondBoundary.epochReason)
    }

    @Test
    fun `stabilizer still rejects partial after final without continuity transition`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 2)
        stabilizer.accept(
            RealtimeAsrPipelineEvent.ContinuityStarted(
                sessionId = 1,
                epochId = 7,
                continuityIndex = 1,
                startSampleIndex = 0,
                epochReason = null,
            ),
        )
        stabilizer.accept(hypothesis(text = "partial", isFinal = false))
        stabilizer.accept(hypothesis(text = "final", isFinal = true))

        var rejected = false
        try {
            stabilizer.accept(hypothesis(text = "illegal", isFinal = false))
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun frame(start: Long) =
        RealtimeCanonicalPcmFrame(
            sessionId = 1,
            epochId = 7,
            startSampleIndex = start,
            samples = ShortArray(4) { 1_000 },
            epochStartReason =
                if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
        )

    private fun hypothesis(
        text: String,
        isFinal: Boolean,
    ) = RealtimeAsrPipelineEvent.Hypothesis(
        sessionId = 1,
        epochId = 7,
        continuityIndex = 1,
        recognizerBaseSampleIndex = 0,
        acceptedEndSampleIndexExclusive = 8,
        hypothesis =
            AsrHypothesis(
                text = text,
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = isFinal,
            ),
        finalizationReason =
            if (isFinal) RealtimeAsrFinalizationReason.VAD_SPEECH_END else null,
    )

    private class FakeAsrEngine : StreamingAsrEngine {
        override val model: ModelDescriptor
            get() = error("not used by processor test")
        override val capabilities: AsrCapabilities
            get() = error("not used by processor test")

        private var nextSessionId = 0

        override suspend fun openSession(): StreamingAsrSession {
            nextSessionId += 1
            return FakeAsrSession(nextSessionId)
        }

        override fun close() = Unit
    }

    private class FakeAsrSession(
        private val sessionId: Int,
    ) : StreamingAsrSession {
        override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) = Unit

        override suspend fun decode(): AsrHypothesis =
            AsrHypothesis(
                text = "partial-$sessionId",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis =
            AsrHypothesis(
                text = "final-$sessionId",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )

        override suspend fun reset() = Unit
        override fun close() = Unit
    }

    private class FakeVadEngine(
        updates: List<StreamingVadUpdate>,
    ) : StreamingVadEngine {
        override val model: ModelDescriptor
            get() = error("not used by processor test")

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
