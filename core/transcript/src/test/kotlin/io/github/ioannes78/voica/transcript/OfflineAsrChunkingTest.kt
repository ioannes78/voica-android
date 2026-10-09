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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAsrChunkingTest {
    @Test
    fun partitionCoversThirtySecondBoundaryDeterministically() {
        val limit = 30L * 16_000L

        assertEquals(
            listOf(limit - 1L),
            lengths(partitionSpeechSegmentsForOfflineAsr(listOf(SpeechSegment(0, limit - 1L)), limit)),
        )
        assertEquals(
            listOf(limit),
            lengths(partitionSpeechSegmentsForOfflineAsr(listOf(SpeechSegment(0, limit)), limit)),
        )
        assertEquals(
            listOf(limit, 1L),
            lengths(partitionSpeechSegmentsForOfflineAsr(listOf(SpeechSegment(0, limit + 1L)), limit)),
        )
        assertEquals(
            listOf(limit, 800L),
            lengths(partitionSpeechSegmentsForOfflineAsr(listOf(SpeechSegment(0, limit + 800L)), limit)),
        )
        assertEquals(
            listOf(limit, limit, 1L),
            lengths(partitionSpeechSegmentsForOfflineAsr(listOf(SpeechSegment(0, limit * 2L + 1L)), limit)),
        )
    }

    @Test
    fun oversizedContinuousSpeechIsChunkedInternallyButRemainsOneProductSegment() = runBlocking {
        val resolver = FakeResolver(ShortArray(11) { (it + 1).toShort() })
        val offline = RecordingOfflineEngine()
        val secondPassProgress = mutableListOf<Pair<Long?, Long?>>()
        val original = SpeechSegment(1, 11)
        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory = VadEngineFactory { FakeVadEngine(listOf(original)) },
                secondPassAsrEngineFactory = SecondPassAsrEngineFactory { offline },
                punctuationEngineFactory = null,
                readChunkSamples = 3,
                maxSecondPassSegmentSamples = 4,
            )

        val result =
            pipeline.transcribe(
                recordingId = "recording-boundary",
                progressListener =
                    ProgressListener { progress ->
                        if (progress.phase == TranscriptionPhase.SECOND_PASS) {
                            secondPassProgress += progress.processedUnits to progress.totalUnits
                        }
                    },
            )

        assertEquals(listOf(4, 4, 2), offline.acceptedCounts)
        assertTrue(offline.acceptedCounts.all { it <= 4 })
        assertEquals(listOf(original), result.speechSegments)
        assertEquals(1, result.transcriptSegments.size)
        val transcript = result.transcriptSegments.single()
        assertEquals(1L, transcript.startSampleIndex)
        assertEquals(11L, transcript.endSampleIndexExclusive)
        assertEquals("块1块2块3", transcript.secondPassRawText)
        assertEquals(listOf(1L, 5L, 9L), transcript.tokens.map { it.startSampleIndex })
        assertEquals(
            listOf(0L to 10L, 4L to 10L, 8L to 10L, 10L to 10L),
            secondPassProgress,
        )
    }

    @Test
    fun cancellationBetweenConsumerChunksStopsFurtherOfflineAsrWork() = runBlocking {
        val resolver = FakeResolver(ShortArray(11) { (it + 1).toShort() })
        val offline = RecordingOfflineEngine()
        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = resolver,
                vadEngineFactory = VadEngineFactory { FakeVadEngine(listOf(SpeechSegment(1, 11))) },
                secondPassAsrEngineFactory = SecondPassAsrEngineFactory { offline },
                punctuationEngineFactory = null,
                readChunkSamples = 3,
                maxSecondPassSegmentSamples = 4,
            )

        val failure =
            runCatching {
                pipeline.transcribe(
                    recordingId = "recording-cancel",
                    progressListener =
                        ProgressListener { progress ->
                            if (
                                progress.phase == TranscriptionPhase.SECOND_PASS &&
                                progress.processedUnits == 4L
                            ) {
                                throw CancellationException("test cancellation")
                            }
                        },
                )
            }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf(4), offline.acceptedCounts)
        assertTrue(offline.closed)
        assertTrue(resolver.openedSources.all { it.closed })
    }

    @Test
    fun mergeUsesConservativeTimingWhenAnyChunkLacksTokens() {
        val original = SpeechSegment(100, 110)
        val partition = partitionSpeechSegmentsForOfflineAsr(listOf(original), 4)
        val results =
            partition.chunks.mapIndexed { index, chunk ->
                OfflineSegment(
                    segmentIndex = index,
                    speechSegment = chunk,
                    hypothesis =
                        AsrHypothesis(
                            text = "块${index + 1}",
                            tokens =
                                if (index == 1) emptyList() else listOf(RelativeTimedToken("块", 0L)),
                            detectedLanguage = "zh",
                            punctuationCapability = PunctuationCapability.RELIABLE,
                            isFinal = true,
                        ),
                    absoluteTokens =
                        if (index == 1) {
                            emptyList()
                        } else {
                            listOf(TranscriptToken("块", chunk.startSampleIndex, null, TokenSource.SECOND_PASS))
                        },
                )
            }

        val merged = mergeOfflineAsrChunks(partition, results).single()

        assertEquals(original, merged.speechSegment)
        assertEquals("块1块2块3", merged.hypothesis.text)
        assertTrue(merged.absoluteTokens.isEmpty())
    }

    @Test
    fun asciiChunkBoundaryAddsOnlyRequiredWordSeparator() {
        assertEquals("hello world", mergeChunkTexts(listOf("hello", "world")))
        assertEquals("你好世界", mergeChunkTexts(listOf("你好", "世界")))
        assertEquals("hello，world", mergeChunkTexts(listOf("hello，", "world")))
    }

    private fun lengths(partition: OfflineAsrPartition): List<Long> =
        partition.chunks.map { it.sampleCount }

    private class FakeResolver(
        private val samples: ShortArray,
    ) : PcmSourceResolver {
        val openedSources = mutableListOf<FakePcmSource>()

        override suspend fun resolvePcmSource(recordingId: String): PcmSource =
            FakePcmSource(samples.copyOf()).also(openedSources::add)
    }

    private class FakePcmSource(
        private val samples: ShortArray,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = samples.size.toLong()
        private var cursor = 0
        var closed = false

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= samples.size) return null
            val count = minOf(maxSamples, samples.size - cursor)
            samples.copyInto(target, targetOffset, cursor, cursor + count)
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
            while (source.read(buffer) != null) Unit
            return segments
        }

        override fun close() = Unit
    }

    private class RecordingOfflineEngine : SecondPassAsrEngine {
        override val model = descriptor(ModelKind.ASR_SECOND_PASS, "offline")
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = false,
                supportsPartial = false,
                supportsTokenTiming = true,
                supportsLanguageDetection = true,
                supportsConfidence = false,
                supportsInverseTextNormalization = true,
                punctuationCapability = PunctuationCapability.RELIABLE,
                supportsSecondPass = true,
            )
        val acceptedCounts = mutableListOf<Int>()
        var closed = false

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ): AsrHypothesis {
            acceptedCounts += samples.size
            val index = acceptedCounts.size
            return AsrHypothesis(
                text = "块$index",
                tokens = listOf(RelativeTimedToken("块", 0L)),
                detectedLanguage = "zh",
                punctuationCapability = PunctuationCapability.RELIABLE,
                isFinal = true,
            )
        }

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
                        supportsStreaming = false,
                        supportsPartial = false,
                        supportsTokenTiming = kind == ModelKind.ASR_SECOND_PASS,
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
