package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTranscriptionPipelineTest {
    @Test
    fun `pipeline connects partial stable final punctuation and canonical timing`() = runBlocking {
        val punctuation = FakePunctuationEngine()
        val pipeline = pipeline(punctuation)

        pipeline.accept(StreamingAudioEvent.SessionStarted(1))
        pipeline.accept(
            StreamingAudioEvent.EpochStarted(
                sessionId = 1,
                epochId = 2,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )

        val first = pipeline.accept(StreamingAudioEvent.PcmFrame(frame(start = 0)))
        val second = pipeline.accept(StreamingAudioEvent.PcmFrame(frame(start = 4)))
        val end =
            pipeline.accept(
                StreamingAudioEvent.SessionEnded(
                    sessionId = 1,
                    finalEpochId = 2,
                    finalSampleIndexExclusive = 8,
                    reason = RealtimeCanonicalSessionEndReason.STOP,
                    drainTimedOut = false,
                ),
            )

        val firstHypothesis = first.filterIsInstance<RealtimeTranscriptionOutput.Hypothesis>().single()
        assertEquals(AsrHypothesisStability.PARTIAL, firstHypothesis.stability)
        assertEquals("你好世", firstHypothesis.displayText)
        assertFalse(firstHypothesis.punctuationAttempted)

        val secondHypotheses = second.filterIsInstance<RealtimeTranscriptionOutput.Hypothesis>()
        assertEquals(
            listOf(AsrHypothesisStability.STABLE, AsrHypothesisStability.PARTIAL),
            secondHypotheses.map { it.stability },
        )
        assertEquals("你好世", secondHypotheses.first().displayText)
        assertFalse(secondHypotheses.first().punctuationAttempted)

        val final = end.filterIsInstance<RealtimeTranscriptionOutput.Hypothesis>().single()
        assertEquals(AsrHypothesisStability.FINAL, final.stability)
        assertEquals("你好世界", final.rawText)
        assertEquals("你好世界。", final.displayText)
        assertTrue(final.punctuationAttempted)
        assertTrue(final.externalPunctuationApplied)
        assertEquals(1, punctuation.calls)
        assertEquals(listOf(0L, 4L), final.canonicalTokens.map { it.startSampleIndex })
        assertEquals(listOf(4L, 8L), final.canonicalTokens.map { it.endSampleIndexExclusive })
        assertTrue(end.last() is RealtimeTranscriptionOutput.Lifecycle)
    }

    @Test
    fun `pause finalization is punctuated once and repeated pause stays idempotent`() = runBlocking {
        val punctuation = FakePunctuationEngine()
        val pipeline = pipeline(punctuation)

        pipeline.accept(StreamingAudioEvent.SessionStarted(3))
        pipeline.accept(
            StreamingAudioEvent.EpochStarted(
                sessionId = 3,
                epochId = 4,
                reason = RealtimeCanonicalEpochStartReason.SESSION_START,
            ),
        )
        pipeline.accept(StreamingAudioEvent.PcmFrame(frame(start = 0, sessionId = 3, epochId = 4)))

        val pause = pipeline.onPauseCommitted()
        val final = pause.filterIsInstance<RealtimeTranscriptionOutput.Hypothesis>().single()
        assertEquals(AsrHypothesisStability.FINAL, final.stability)
        assertEquals(RealtimeAsrFinalizationReason.EPOCH_BOUNDARY, final.source.finalizationReason)
        assertEquals("你好世界。", final.displayText)
        assertEquals(1, punctuation.calls)

        assertTrue(pipeline.onPauseCommitted().isEmpty())
        assertEquals(1, punctuation.calls)
    }

    private fun pipeline(punctuation: FakePunctuationEngine): RealtimeTranscriptionPipeline {
        val processor =
            RealtimeStreamingAsrProcessor(
                asrEngine = FakeAsrEngine(),
                vadEngine = FakeVadEngine(),
                config = RealtimeStreamingAsrConfig(targetChunkSamples = 4, vadPrerollSamples = 4),
            )
        return RealtimeTranscriptionPipeline(
            processor = processor,
            stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 2),
            punctuationProcessor = RealtimePunctuationProcessor(punctuation),
        )
    }

    private fun frame(
        start: Long,
        sessionId: Long = 1,
        epochId: Long = 2,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = epochId,
        startSampleIndex = start,
        samples = ShortArray(4) { 1_000 },
        epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
    )

    private class FakeAsrEngine : StreamingAsrEngine {
        override val model = descriptor("zipformer-small-bilingual", ModelKind.ASR_STREAMING, streaming = true)
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

        override suspend fun openSession(): StreamingAsrSession = FakeAsrSession()
        override fun close() = Unit
    }

    private class FakeAsrSession : StreamingAsrSession {
        private var accepted = 0

        override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) {
            accepted += count
        }

        override suspend fun decode(): AsrHypothesis =
            AsrHypothesis(
                text = if (accepted <= 4) "你好世" else "你好世界",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis =
            AsrHypothesis(
                text = "你好世界",
                tokens =
                    listOf(
                        RelativeTimedToken("你好", 0, 4),
                        RelativeTimedToken("世界", 4, accepted.toLong()),
                    ),
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )

        override suspend fun reset() {
            accepted = 0
        }

        override fun close() = Unit
    }

    private class FakeVadEngine : StreamingVadEngine {
        override val model = descriptor("silero-vad-int8", ModelKind.VAD, streaming = false)

        override suspend fun openSession(): StreamingVadSession = object : StreamingVadSession {
            override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) =
                StreamingVadUpdate(speechDetected = true)

            override suspend fun finishInput() = StreamingVadUpdate(speechDetected = false)
            override suspend fun reset() = Unit
            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private class FakePunctuationEngine : PunctuationEngine {
        override val model = descriptor("ct-transformer-zh-en-int8", ModelKind.PUNCTUATION, streaming = false)
        var calls = 0

        override suspend fun addPunctuation(text: String): String {
            calls += 1
            return "$text。"
        }

        override fun close() = Unit
    }

    private companion object {
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
                packageFormat = ModelPackageFormat.ZIP,
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
