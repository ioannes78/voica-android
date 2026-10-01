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
import org.junit.Assert.assertTrue
import org.junit.Test

class FastTranscriptionPipelineTest {
    @Test
    fun fastPipelineFeedsOnlySpeechAndPreservesAbsoluteTimeline() = runBlocking {
        val samples = ShortArray(12) { index -> (index + 1).toShort() }
        val resolver = FakeResolver(samples)
        val vad = FakeVadEngine(listOf(SpeechSegment(2, 5), SpeechSegment(8, 10)))
        val asr = FakeStreamingAsrEngine()
        val punctuation = FakePunctuationEngine()
        val progress = mutableListOf<TranscriptionProgress>()

        val pipeline =
            FastTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory = VadEngineFactory { vad },
                asrEngineFactory = StreamingAsrEngineFactory { asr },
                punctuationEngineFactory = PunctuationEngineFactory { punctuation },
                readChunkSamples = 4,
            )

        val result = pipeline.transcribe("recording-1") { update ->
            progress += update
        }

        assertEquals(2, resolver.openCount)
        assertTrue(resolver.openedSources.all { it.closed })
        assertTrue(vad.closed)
        assertTrue(asr.closed)
        assertTrue(punctuation.closed)

        assertEquals(listOf(3, 2), asr.acceptedSampleCounts)
        assertEquals(2, result.transcriptSegments.size)
        assertEquals("raw-1。", result.transcriptSegments[0].finalText)
        assertEquals("raw-2。", result.transcriptSegments[1].finalText)
        assertEquals(2L, result.transcriptSegments[0].startSampleIndex)
        assertEquals(5L, result.transcriptSegments[0].endSampleIndexExclusive)
        assertEquals(2L, result.transcriptSegments[0].tokens.single().startSampleIndex)
        assertEquals(8L, result.transcriptSegments[1].tokens.single().startSampleIndex)

        val lastAsr =
            progress.last { it.phase == TranscriptionPhase.FIRST_PASS }
        assertEquals(5L, lastAsr.processedUnits)
        assertEquals(5L, lastAsr.totalUnits)

        val lastPunctuation =
            progress.last { it.phase == TranscriptionPhase.PUNCTUATION }
        assertEquals(2L, lastPunctuation.processedUnits)
        assertEquals(2L, lastPunctuation.totalUnits)
    }

    @Test
    fun noSpeechDoesNotLoadAsrOrPunctuation() = runBlocking {
        val resolver = FakeResolver(ShortArray(8))
        val vad = FakeVadEngine(emptyList())
        var asrOpened = false
        var punctuationOpened = false

        val pipeline =
            FastTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory = VadEngineFactory { vad },
                asrEngineFactory =
                    StreamingAsrEngineFactory {
                        asrOpened = true
                        FakeStreamingAsrEngine()
                    },
                punctuationEngineFactory =
                    PunctuationEngineFactory {
                        punctuationOpened = true
                        FakePunctuationEngine()
                    },
                readChunkSamples = 4,
            )

        val result = pipeline.transcribe("recording-1")

        assertTrue(result.transcriptSegments.isEmpty())
        assertEquals(1, resolver.openCount)
        assertTrue(!asrOpened)
        assertTrue(!punctuationOpened)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingVadSegments() {
        runBlocking {
            val pipeline =
                FastTranscriptionPipeline(
                    pcmSourceResolver = FakeResolver(ShortArray(12)),
                    vadEngineFactory =
                        VadEngineFactory {
                            FakeVadEngine(
                                listOf(
                                    SpeechSegment(2, 7),
                                    SpeechSegment(6, 9),
                                ),
                            )
                        },
                    asrEngineFactory = StreamingAsrEngineFactory { FakeStreamingAsrEngine() },
                    punctuationEngineFactory = PunctuationEngineFactory { FakePunctuationEngine() },
                    readChunkSamples = 4,
                )

            pipeline.transcribe("recording-1")
        }
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
        var closed = false

        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            val buffer = ShortArray(4)
            var processed = 0L
            while (true) {
                val read = source.read(buffer) ?: break
                processed += read.sampleCount
                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.VAD,
                        processedUnits = processed,
                        totalUnits = source.totalSampleCount,
                    ),
                )
            }
            return segments
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeStreamingAsrEngine : StreamingAsrEngine {
        override val model = descriptor(ModelKind.ASR_STREAMING, "asr")
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

        val acceptedSampleCounts = mutableListOf<Int>()
        private var sessionIndex = 0
        var closed = false

        override suspend fun openSession(): StreamingAsrSession {
            sessionIndex += 1
            return FakeSession(sessionIndex, acceptedSampleCounts)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSession(
        private val index: Int,
        private val acceptedSampleCounts: MutableList<Int>,
    ) : StreamingAsrSession {
        private var accepted = 0

        override suspend fun acceptSamples(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ) {
            accepted += count
        }

        override suspend fun decode() =
            AsrHypothesis(
                text = "",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis {
            acceptedSampleCounts += accepted
            return AsrHypothesis(
                text = "raw-$index",
                tokens = listOf(RelativeTimedToken("t", 0L)),
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )
        }

        override suspend fun reset() = Unit

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
            modelId: String,
        ) =
            ModelDescriptor(
                modelId = modelId,
                kind = kind,
                displayName = modelId,
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
                        supportsTokenTiming = kind == ModelKind.ASR_STREAMING,
                    ),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/$modelId.bin",
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
