package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeStreamingAsrProcessorTest {
    @Test
    fun `vad starts true streaming with preroll and finalizes one native final`() {
        runBlocking {
            val vadEngine = FakeVadEngine(
                updates =
                    listOf(
                        StreamingVadUpdate(false),
                        StreamingVadUpdate(true),
                        StreamingVadUpdate(true),
                        StreamingVadUpdate(
                            false,
                            listOf(StreamingVadSpeechSegment(4, 12)),
                        ),
                    ),
            )
            val asrEngine = FakeAsrEngine()
            val processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine,
                    vadEngine,
                    RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
                )

            processor.accept(StreamingAudioEvent.SessionStarted(1))
            processor.accept(
                StreamingAudioEvent.EpochStarted(
                    sessionId = 1,
                    epochId = 1,
                    reason = RealtimeCanonicalEpochStartReason.SESSION_START,
                ),
            )

            val outputs = mutableListOf<RealtimeAsrPipelineEvent>()
            for (start in listOf(0L, 4L, 8L, 12L)) {
                outputs += processor.accept(StreamingAudioEvent.PcmFrame(frame(start)))
            }

            val hypotheses = outputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()
            assertTrue(hypotheses.any { !it.hypothesis.isFinal })
            val final = hypotheses.single { it.hypothesis.isFinal }
            assertEquals(RealtimeAsrFinalizationReason.VAD_SPEECH_END, final.finalizationReason)
            assertEquals(0L, final.recognizerBaseSampleIndex)
            assertEquals(16L, final.acceptedEndSampleIndexExclusive)
            assertEquals("final-16", final.hypothesis.text)
            assertEquals(1, asrEngine.sessions.size)
            assertEquals(1, asrEngine.sessions.single().finishCalls)

            val vadSegment = outputs.filterIsInstance<RealtimeAsrPipelineEvent.VadSegment>().single()
            assertEquals(4L, vadSegment.startSampleIndex)
            assertEquals(12L, vadSegment.endSampleIndexExclusive)
        }
    }

    @Test
    fun `discontinuity finalizes old recognizer and starts new continuity at live edge`() {
        runBlocking {
            val vadEngine = FakeVadEngine(
                updates = listOf(StreamingVadUpdate(true), StreamingVadUpdate(true)),
            )
            val asrEngine = FakeAsrEngine()
            val processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine,
                    vadEngine,
                    RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
                )
            processor.accept(StreamingAudioEvent.SessionStarted(7))
            processor.accept(
                StreamingAudioEvent.EpochStarted(7, 9, RealtimeCanonicalEpochStartReason.SESSION_START),
            )
            processor.accept(StreamingAudioEvent.PcmFrame(frame(start = 0, sessionId = 7, epochId = 9)))

            val gapOutputs =
                processor.accept(
                    StreamingAudioEvent.Discontinuity(
                        sessionId = 7,
                        epochId = 9,
                        resumeAtSampleIndex = 100,
                        reason = StreamingAudioDiscontinuityReason.BUFFER_OVERFLOW,
                        droppedFrames = 2,
                        droppedSamples = 8,
                    ),
                )
            val oldFinal = gapOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>().single { it.hypothesis.isFinal }
            assertEquals(RealtimeAsrFinalizationReason.DISCONTINUITY, oldFinal.finalizationReason)
            val boundary = gapOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Discontinuity>().single()
            assertEquals(100L, boundary.resumeAtSampleIndex)

            val newOutputs =
                processor.accept(
                    StreamingAudioEvent.PcmFrame(frame(start = 100, sessionId = 7, epochId = 9)),
                )
            val newPartial = newOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>().single()
            assertFalse(newPartial.hypothesis.isFinal)
            assertEquals(100L, newPartial.recognizerBaseSampleIndex)
            assertTrue(newPartial.continuityIndex > oldFinal.continuityIndex)
        }
    }

    @Test
    fun `pause commit finalizes immediately and repeated pause is idempotent`() {
        runBlocking {
            val vadEngine = FakeVadEngine(
                updates = listOf(StreamingVadUpdate(true), StreamingVadUpdate(true)),
            )
            val asrEngine = FakeAsrEngine()
            val processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine,
                    vadEngine,
                    RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
                )
            processor.accept(StreamingAudioEvent.SessionStarted(11))
            processor.accept(
                StreamingAudioEvent.EpochStarted(11, 21, RealtimeCanonicalEpochStartReason.SESSION_START),
            )
            processor.accept(StreamingAudioEvent.PcmFrame(frame(start = 0, sessionId = 11, epochId = 21)))

            val pauseOutputs = processor.onPauseCommitted()
            val pausedFinal = pauseOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>().single()
            assertTrue(pausedFinal.hypothesis.isFinal)
            assertEquals(RealtimeAsrFinalizationReason.EPOCH_BOUNDARY, pausedFinal.finalizationReason)
            assertEquals(1, asrEngine.sessions.single().finishCalls)

            assertTrue(processor.onPauseCommitted().isEmpty())
            assertEquals(1, asrEngine.sessions.single().finishCalls)

            processor.accept(
                StreamingAudioEvent.EpochStarted(11, 22, RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY),
            )
            val resumedOutputs =
                processor.accept(
                    StreamingAudioEvent.PcmFrame(frame(start = 0, sessionId = 11, epochId = 22)),
                )
            val resumedPartial = resumedOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>().single()
            assertFalse(resumedPartial.hypothesis.isFinal)
            assertTrue(resumedPartial.continuityIndex > pausedFinal.continuityIndex)
            assertEquals(2, asrEngine.sessions.size)
        }
    }

    @Test
    fun `session end after pause does not emit duplicate final`() {
        runBlocking {
            val vadEngine = FakeVadEngine(updates = listOf(StreamingVadUpdate(true)))
            val asrEngine = FakeAsrEngine()
            val processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine,
                    vadEngine,
                    RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 8),
                )
            processor.accept(StreamingAudioEvent.SessionStarted(31))
            processor.accept(
                StreamingAudioEvent.EpochStarted(31, 41, RealtimeCanonicalEpochStartReason.SESSION_START),
            )
            processor.accept(StreamingAudioEvent.PcmFrame(frame(start = 0, sessionId = 31, epochId = 41)))

            val pauseFinal = processor.onPauseCommitted()
                .filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()
                .single()
            assertTrue(pauseFinal.hypothesis.isFinal)

            val endOutputs =
                processor.accept(
                    StreamingAudioEvent.SessionEnded(
                        sessionId = 31,
                        finalEpochId = 41,
                        finalSampleIndexExclusive = 4,
                        reason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = false,
                    ),
                )
            assertTrue(endOutputs.none { it is RealtimeAsrPipelineEvent.Hypothesis })
            assertTrue(endOutputs.single() is RealtimeAsrPipelineEvent.SessionEnded)
            assertEquals(1, asrEngine.sessions.single().finishCalls)
        }
    }

    @Test
    fun `session end flushes short tail and finalizes exactly once`() {
        runBlocking {
            val vadEngine = FakeVadEngine(
                updates = listOf(StreamingVadUpdate(true), StreamingVadUpdate(false)),
                finishUpdates = listOf(StreamingVadUpdate(false)),
            )
            val asrEngine = FakeAsrEngine()
            val processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine,
                    vadEngine,
                    RealtimeStreamingAsrConfig(targetChunkSamples = 8, vadPrerollSamples = 8),
                )
            processor.accept(StreamingAudioEvent.SessionStarted(1))
            processor.accept(StreamingAudioEvent.EpochStarted(1, 1, RealtimeCanonicalEpochStartReason.SESSION_START))
            processor.accept(StreamingAudioEvent.PcmFrame(frame(start = 0)))

            val endOutputs =
                processor.accept(
                    StreamingAudioEvent.SessionEnded(
                        sessionId = 1,
                        finalEpochId = 1,
                        finalSampleIndexExclusive = 4,
                        reason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = false,
                    ),
                )
            val final = endOutputs.filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>().single { it.hypothesis.isFinal }
            assertEquals(RealtimeAsrFinalizationReason.SESSION_END, final.finalizationReason)
            assertEquals(4L, final.acceptedEndSampleIndexExclusive)
            assertEquals(1, asrEngine.sessions.single().finishCalls)
            assertTrue(endOutputs.last() is RealtimeAsrPipelineEvent.SessionEnded)
        }
    }

    private fun frame(
        start: Long,
        sessionId: Long = 1,
        epochId: Long = 1,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = epochId,
        startSampleIndex = start,
        samples = ShortArray(4) { 1_000 },
        epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
    )

    private class FakeAsrEngine : StreamingAsrEngine {
        override val model = streamingDescriptor()
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = true,
                supportsPartial = true,
                supportsTokenTiming = true,
                supportsLanguageDetection = false,
                supportsConfidence = false,
                supportsInverseTextNormalization = false,
                punctuationCapability = PunctuationCapability.NONE,
                supportsSecondPass = false,
            )
        val sessions = mutableListOf<FakeAsrSession>()

        override suspend fun openSession(): StreamingAsrSession =
            FakeAsrSession().also(sessions::add)

        override fun close() = Unit
    }

    private class FakeAsrSession : StreamingAsrSession {
        var accepted = 0
        var finishCalls = 0
        private var closed = false

        override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) {
            check(!closed)
            accepted += count
        }

        override suspend fun decode(): AsrHypothesis =
            AsrHypothesis(
                text = "partial-$accepted",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis {
            finishCalls += 1
            return AsrHypothesis(
                text = "final-$accepted",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )
        }

        override suspend fun reset() {
            accepted = 0
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeVadEngine(
        updates: List<StreamingVadUpdate>,
        finishUpdates: List<StreamingVadUpdate> = emptyList(),
    ) : StreamingVadEngine {
        override val model = vadDescriptor()
        private val updates = ArrayDeque(updates)
        private val finishUpdates = ArrayDeque(finishUpdates)

        override suspend fun openSession(): StreamingVadSession = object : StreamingVadSession {
            override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int): StreamingVadUpdate =
                if (updates.isEmpty()) StreamingVadUpdate(false) else updates.removeFirst()

            override suspend fun finishInput(): StreamingVadUpdate =
                if (finishUpdates.isEmpty()) StreamingVadUpdate(false) else finishUpdates.removeFirst()

            override suspend fun reset() = Unit
            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private companion object {
        fun streamingDescriptor() =
            descriptor(
                id = "zipformer-small-bilingual",
                kind = ModelKind.ASR_STREAMING,
                streaming = true,
            )

        fun vadDescriptor() = descriptor("silero-vad-int8", ModelKind.VAD, false)

        fun descriptor(id: String, kind: ModelKind, streaming: Boolean) =
            ModelDescriptor(
                modelId = id,
                kind = kind,
                displayName = id,
                version = "v1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = if (kind == ModelKind.VAD) setOf("und") else setOf("zh", "en"),
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = streaming,
                        supportsPartial = streaming,
                        supportsTokenTiming = streaming,
                    ),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = io.github.ioannes78.voica.model.ModelPackageFormat.ZIP,
                downloadUrl = "https://example.invalid/$id.zip",
                downloadSizeBytes = 1,
                installedSizeBytes = 1,
                packageSha256 = "a".repeat(64),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.bin",
                            sizeBytes = 1,
                            sha256 = "b".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = null,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "test",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "production",
                autoUpdateEligible = false,
            )
    }
}
