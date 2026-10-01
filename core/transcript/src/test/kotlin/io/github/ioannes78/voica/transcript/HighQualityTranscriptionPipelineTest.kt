package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighQualityTranscriptionPipelineTest {
    @Test
    fun secondPassUsesOnlySpeechAndKeepsSecondPassTokenProvenance() = runBlocking {
        val resolver = FakeResolver(ShortArray(12) { (it + 1).toShort() })
        val punctuationOpened = booleanArrayOf(false)
        val second = FakeSecondPassEngine(fail = false)

        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory =
                    VadEngineFactory {
                        FakeVadEngine(
                            listOf(
                                SpeechSegment(2, 5),
                                SpeechSegment(8, 10),
                            ),
                        )
                    },
                asrEngineFactory = StreamingAsrEngineFactory { FakeFirstPassEngine() },
                secondPassAsrEngineFactory = SecondPassAsrEngineFactory { second },
                punctuationEngineFactory =
                    PunctuationEngineFactory {
                        punctuationOpened[0] = true
                        FakePunctuationEngine()
                    },
                readChunkSamples = 4,
            )

        val result = pipeline.transcribe("recording-1")

        assertTrue(result.secondPassApplied)
        assertEquals(null, result.secondPassFallbackError)
        assertEquals(3, resolver.openCount)
        assertTrue(resolver.openedSources.all { it.closed })
        assertEquals(listOf(3, 2), second.acceptedCounts)
        assertEquals(
            listOf(
                listOf<Short>(3, 4, 5),
                listOf<Short>(9, 10),
            ),
            second.acceptedSamples.map { samples -> samples.map { it } },
        )
        assertFalse(punctuationOpened[0])

        val first = result.transcriptSegments[0]
        assertEquals("first-1", first.firstPassRawText)
        assertEquals("second-1。", first.secondPassRawText)
        assertEquals("second-1。", first.finalText)
        assertEquals(TokenSource.SECOND_PASS, first.tokens.single().source)
        assertEquals(2L, first.tokens.single().startSampleIndex)
        assertEquals("zh", first.detectedLanguage)
    }

    @Test
    fun secondPassLoadFailureFallsBackToFirstPassAndPunctuation() = runBlocking {
        val resolver = FakeResolver(ShortArray(12) { (it + 1).toShort() })
        val punctuation = FakePunctuationEngine()

        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory =
                    VadEngineFactory {
                        FakeVadEngine(listOf(SpeechSegment(2, 5)))
                    },
                asrEngineFactory = StreamingAsrEngineFactory { FakeFirstPassEngine() },
                secondPassAsrEngineFactory =
                    SecondPassAsrEngineFactory {
                        error("second-pass unavailable")
                    },
                punctuationEngineFactory = PunctuationEngineFactory { punctuation },
                readChunkSamples = 4,
            )

        val result = pipeline.transcribe("recording-1")

        assertFalse(result.secondPassApplied)
        assertNotNull(result.secondPassFallbackError)
        assertTrue(result.secondPassFallbackError!!.contains("second-pass unavailable"))
        assertEquals("first-1。", result.transcriptSegments.single().finalText)
        assertEquals(null, result.transcriptSegments.single().secondPassRawText)
        assertEquals(TokenSource.FIRST_PASS, result.transcriptSegments.single().tokens.single().source)
        assertTrue(punctuation.closed)
        assertTrue(resolver.openedSources.all { it.closed })
    }

    @Test
    fun partialSecondPassWithoutTerminalPunctuationUsesFallbackPunctuation() = runBlocking {
        val resolver = FakeResolver(ShortArray(8) { (it + 1).toShort() })
        val second =
            FakeSecondPassEngine(
                fail = false,
                addTerminalPunctuation = false,
            )
        val punctuation = FakePunctuationEngine()

        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory =
                    VadEngineFactory {
                        FakeVadEngine(listOf(SpeechSegment(0, 4)))
                    },
                asrEngineFactory = StreamingAsrEngineFactory { FakeFirstPassEngine() },
                secondPassAsrEngineFactory = SecondPassAsrEngineFactory { second },
                punctuationEngineFactory = PunctuationEngineFactory { punctuation },
                readChunkSamples = 4,
            )

        val result = pipeline.transcribe("recording-1")

        assertTrue(result.secondPassApplied)
        assertEquals("second-1。", result.transcriptSegments.single().finalText)
        assertTrue(punctuation.closed)
    }

    @Test
    fun punctuationCapabilityRulesAreDeterministic() {
        assertTrue(needsPunctuationFallback("没有标点", PunctuationCapability.NONE))
        assertFalse(needsPunctuationFallback("已有标点。", PunctuationCapability.PARTIAL))
        assertTrue(needsPunctuationFallback("只有逗号，继续", PunctuationCapability.PARTIAL))
        assertFalse(needsPunctuationFallback("model output", PunctuationCapability.RELIABLE))
        assertFalse(needsPunctuationFallback("", PunctuationCapability.NONE))
    }

    private class FakeResolver(
        private val samples: ShortArray,
    ) : PcmSourceResolver {
        var openCount = 0
        val openedSources = mutableListOf<FakePcmSource>()

        override suspend fun resolvePcmSource(recordingId: String): PcmSource {
            openCount += 1
            return FakePcmSource(samples.copyOf()).also(openedSources::add)
        }
    }

    private class FakePcmSource(
        private val samples: ShortArray,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = samples.size.toLong()
        var closed = false
        private var cursor = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= samples.size) return null
            val count = minOf(maxSamples, samples.size - cursor)
            samples.copyInto(
                destination = target,
                destinationOffset = targetOffset,
                startIndex = cursor,
                endIndex = cursor + count,
            )
            val start = cursor.toLong()
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeVadEngine(
        private val segments: List<SpeechSegment>,
    ) : VadEngine {
        override val model = descriptor(ModelKind.VAD, "vad")

        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            val buffer = ShortArray(4)
            while (source.read(buffer) != null) {
                // Consume the canonical source like the production VAD.
            }
            return segments
        }

        override fun close() = Unit
    }

    private class FakeFirstPassEngine : StreamingAsrEngine {
        override val model = descriptor(ModelKind.ASR_STREAMING, "first")
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
        private var sessionIndex = 0

        override suspend fun openSession(): StreamingAsrSession {
            sessionIndex += 1
            return FakeFirstPassSession(sessionIndex)
        }

        override fun close() = Unit
    }

    private class FakeFirstPassSession(
        private val index: Int,
    ) : StreamingAsrSession {
        override suspend fun acceptSamples(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ) = Unit

        override suspend fun decode() =
            AsrHypothesis(
                text = "",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput() =
            AsrHypothesis(
                text = "first-$index",
                tokens = listOf(RelativeTimedToken("f", 0L)),
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )

        override suspend fun reset() = Unit

        override fun close() = Unit
    }

    private class FakeSecondPassEngine(
        private val fail: Boolean,
        private val addTerminalPunctuation: Boolean = true,
    ) : SecondPassAsrEngine {
        override val model = descriptor(ModelKind.ASR_SECOND_PASS, "second")
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = false,
                supportsPartial = false,
                supportsTokenTiming = true,
                supportsLanguageDetection = true,
                supportsConfidence = false,
                supportsInverseTextNormalization = true,
                punctuationCapability = PunctuationCapability.PARTIAL,
                supportsSecondPass = true,
            )
        val acceptedCounts = mutableListOf<Int>()
        val acceptedSamples = mutableListOf<ShortArray>()
        private var callIndex = 0

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ): AsrHypothesis {
            if (fail) error("second-pass inference failed")
            callIndex += 1
            acceptedCounts += samples.size
            acceptedSamples += samples.copyOf()
            val suffix = if (addTerminalPunctuation) "。" else ""
            return AsrHypothesis(
                text = "second-$callIndex$suffix",
                tokens = listOf(RelativeTimedToken("s", 0L)),
                detectedLanguage = "zh",
                punctuationCapability = PunctuationCapability.PARTIAL,
                isFinal = true,
            )
        }

        override fun close() = Unit
    }

    private class FakePunctuationEngine : PunctuationEngine {
        override val model = descriptor(ModelKind.PUNCTUATION, "punct")
        var closed = false

        override suspend fun addPunctuation(text: String): String = text + "。"

        override fun close() {
            closed = true
        }
    }

    companion object {
        private fun descriptor(
            kind: ModelKind,
            id: String,
        ) =
            ModelDescriptor(
                modelId = id,
                kind = kind,
                displayName = id,
                version = "v1",
                revision = 1,
                runtimeId = "test",
                runtimeVersionMin = null,
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = kind == ModelKind.ASR_STREAMING,
                        supportsPartial = kind == ModelKind.ASR_STREAMING,
                        supportsTokenTiming =
                            kind == ModelKind.ASR_STREAMING ||
                                kind == ModelKind.ASR_SECOND_PASS,
                        supportsLanguageDetection = kind == ModelKind.ASR_SECOND_PASS,
                        supportsInverseTextNormalization = kind == ModelKind.ASR_SECOND_PASS,
                        supportsSecondPass = kind == ModelKind.ASR_SECOND_PASS,
                    ),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/$id.bin",
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                packageSha256 = "a".repeat(64),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.bin",
                            sizeBytes = 1L,
                            sha256 = "b".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 20,
                appVersionMax = null,
                licenseId = "test",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "test",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "test",
                autoUpdateEligible = false,
            )
    }
}
