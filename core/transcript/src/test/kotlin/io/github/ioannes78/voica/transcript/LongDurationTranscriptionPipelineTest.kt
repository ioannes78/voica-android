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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongDurationTranscriptionPipelineTest {
    @Test
    fun virtualThirtySixtyAndOneHundredTwentyMinuteSourcesRemainChunkBounded() =
        runBlocking {
            listOf(30, 60, 120).forEach { minutes ->
                val totalSamples =
                    Math.multiplyExact(
                        minutes.toLong() * 60L,
                        SAMPLE_RATE.toLong(),
                    )
                val speech =
                    SpeechSegment(
                        startSampleIndex = totalSamples - 8_000L,
                        endSampleIndexExclusive = totalSamples - 4_000L,
                    )
                val resolver = VirtualResolver(totalSamples)
                val vad = ScanningVadEngine(listOf(speech))
                val asr = CountingStreamingAsrEngine()
                val punctuation = SuffixPunctuationEngine()
                val progress = mutableListOf<TranscriptionProgress>()

                val pipeline =
                    FastTranscriptionPipeline(
                        pcmSourceResolver = resolver,
                        vadEngineFactory = VadEngineFactory { vad },
                        asrEngineFactory = StreamingAsrEngineFactory { asr },
                        punctuationEngineFactory =
                            PunctuationEngineFactory { punctuation },
                        readChunkSamples = READ_CHUNK,
                    )

                val result =
                    pipeline.transcribe("virtual-$minutes") { update ->
                        progress += update
                    }

                assertEquals(totalSamples, result.totalSampleCount)
                assertEquals(2, resolver.openCount)
                assertTrue(resolver.openedSources.all { it.closed })
                assertTrue(
                    resolver.openedSources.all {
                        it.maxRequestedSamples <= READ_CHUNK
                    },
                )
                assertTrue(
                    resolver.openedSources.all {
                        it.totalReadSamples == totalSamples
                    },
                )
                assertEquals(listOf(4_000), asr.completedSessionSampleCounts)
                assertEquals(1, result.transcriptSegments.size)
                assertEquals(
                    speech.startSampleIndex,
                    result.transcriptSegments.single().startSampleIndex,
                )
                assertEquals(
                    speech.endSampleIndexExclusive,
                    result.transcriptSegments.single().endSampleIndexExclusive,
                )
                assertEquals(
                    "long-audio。",
                    result.transcriptSegments.single().finalText,
                )

                val vadDone =
                    progress.last { it.phase == TranscriptionPhase.VAD }
                assertEquals(totalSamples, vadDone.processedUnits)
                assertEquals(totalSamples, vadDone.totalUnits)

                val asrDone =
                    progress.last { it.phase == TranscriptionPhase.FIRST_PASS }
                assertEquals(4_000L, asrDone.processedUnits)
                assertEquals(4_000L, asrDone.totalUnits)
            }
        }

    @Test
    fun cancellationDuringLongVadClosesSourceAndEngineWithoutOpeningAsr() =
        runBlocking {
            val totalSamples = 120L * 60L * SAMPLE_RATE
            val resolver =
                VirtualResolver(
                    totalSampleCount = totalSamples,
                    yieldEveryRead = true,
                )
            val started = CompletableDeferred<Unit>()
            val vad =
                ScanningVadEngine(
                    segments = emptyList(),
                    onFirstRead = { started.complete(Unit) },
                )
            var asrOpened = false
            var punctuationOpened = false

            val pipeline =
                FastTranscriptionPipeline(
                    pcmSourceResolver = resolver,
                    vadEngineFactory = VadEngineFactory { vad },
                    asrEngineFactory =
                        StreamingAsrEngineFactory {
                            asrOpened = true
                            CountingStreamingAsrEngine()
                        },
                    punctuationEngineFactory =
                        PunctuationEngineFactory {
                            punctuationOpened = true
                            SuffixPunctuationEngine()
                        },
                    readChunkSamples = READ_CHUNK,
                )

            val job = async {
                pipeline.transcribe("cancel-long")
            }
            started.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertTrue(vad.closed)
            assertEquals(1, resolver.openCount)
            assertTrue(resolver.openedSources.single().closed)
            assertTrue(resolver.openedSources.single().totalReadSamples < totalSamples)
            assertTrue(!asrOpened)
            assertTrue(!punctuationOpened)
        }

    private class VirtualResolver(
        private val totalSampleCount: Long,
        private val yieldEveryRead: Boolean = false,
    ) : PcmSourceResolver {
        var openCount = 0
        val openedSources = mutableListOf<VirtualPcmSource>()

        override suspend fun resolvePcmSource(recordingId: String): PcmSource {
            openCount += 1
            return VirtualPcmSource(
                totalSampleCount = totalSampleCount,
                yieldEveryRead = yieldEveryRead,
            ).also(openedSources::add)
        }
    }

    private class VirtualPcmSource(
        override val totalSampleCount: Long,
        private val yieldEveryRead: Boolean,
    ) : PcmSource {
        override val sampleRateHz = SAMPLE_RATE
        override val channelCount = 1
        var closed = false
        var maxRequestedSamples = 0
        var totalReadSamples = 0L
        private var cursor = 0L

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            currentCoroutineContext().ensureActive()
            if (yieldEveryRead) yield()
            if (cursor >= totalSampleCount) return null

            require(targetOffset >= 0)
            require(maxSamples > 0)
            require(targetOffset + maxSamples <= target.size)
            maxRequestedSamples = maxOf(maxRequestedSamples, maxSamples)

            val remaining = totalSampleCount - cursor
            val count = minOf(maxSamples.toLong(), remaining).toInt()
            // The virtual source is already zero-initialized. Advancing the
            // absolute sample cursor is sufficient for bounded-read tests and
            // avoids touching hundreds of millions of synthetic samples.
            val start = cursor
            cursor = Math.addExact(cursor, count.toLong())
            totalReadSamples = Math.addExact(totalReadSamples, count.toLong())
            return PcmReadResult(start, count)
        }

        override fun close() {
            closed = true
        }
    }

    private class ScanningVadEngine(
        private val segments: List<SpeechSegment>,
        private val onFirstRead: (() -> Unit)? = null,
    ) : VadEngine {
        override val model = descriptor(ModelKind.VAD, "vad")
        var closed = false

        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            val buffer = ShortArray(READ_CHUNK)
            var processed = 0L
            var first = true
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer) ?: break
                if (first) {
                    first = false
                    onFirstRead?.invoke()
                }
                processed = Math.addExact(processed, read.sampleCount.toLong())
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

    private class CountingStreamingAsrEngine : StreamingAsrEngine {
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
        val completedSessionSampleCounts = mutableListOf<Int>()

        override suspend fun openSession(): StreamingAsrSession =
            object : StreamingAsrSession {
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
                    completedSessionSampleCounts += accepted
                    return AsrHypothesis(
                        text = "long-audio",
                        tokens =
                            listOf(
                                RelativeTimedToken(
                                    text = "long-audio",
                                    startSampleOffset = 0L,
                                ),
                            ),
                        punctuationCapability = PunctuationCapability.NONE,
                        isFinal = true,
                    )
                }

                override suspend fun reset() = Unit

                override fun close() = Unit
            }

        override fun close() = Unit
    }

    private class SuffixPunctuationEngine : PunctuationEngine {
        override val model = descriptor(ModelKind.PUNCTUATION, "punct")

        override suspend fun addPunctuation(text: String): String =
            if (text.isBlank()) text else text + "。"

        override fun close() = Unit
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val READ_CHUNK = 4_096

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
                        supportsTokenTiming = kind == ModelKind.ASR_STREAMING,
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
