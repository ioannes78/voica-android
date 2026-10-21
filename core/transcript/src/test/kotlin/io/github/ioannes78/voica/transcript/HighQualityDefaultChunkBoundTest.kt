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
import org.junit.Test

class HighQualityDefaultChunkBoundTest {
    @Test
    fun defaultOuterBoundNoLongerHardCutsTwentySixSecondVadSegmentAtTwentyFiveSeconds() = runBlocking {
        val sampleRate = 16_000
        val samples = ShortArray(26 * sampleRate) { 1_000 }
        val segment = SpeechSegment(0, samples.size.toLong())
        val engine = RecordingEngine()
        val pipeline =
            HighQualityTranscriptionPipeline(
                pcmSourceResolver = Resolver(samples),
                vadEngineFactory = VadEngineFactory { FixedVad(segment) },
                secondPassAsrEngineFactory = SecondPassAsrEngineFactory { engine },
                punctuationEngineFactory = null,
            )

        val result = pipeline.transcribe("outer-bound")

        assertEquals(listOf(samples.size), engine.acceptedCounts)
        assertEquals(listOf(segment), result.speechSegments)
        assertEquals(1, result.transcriptSegments.size)
    }

    private class Resolver(
        private val samples: ShortArray,
    ) : PcmSourceResolver {
        override suspend fun resolvePcmSource(recordingId: String): PcmSource = Source(samples)
    }

    private class Source(
        private val samples: ShortArray,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = samples.size.toLong()
        private var cursor = 0

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

        override fun close() = Unit
    }

    private class FixedVad(
        private val segment: SpeechSegment,
    ) : VadEngine {
        override val model = descriptor(ModelKind.VAD, "vad")

        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            val buffer = ShortArray(4_096)
            while (source.read(buffer) != null) Unit
            return listOf(segment)
        }

        override fun close() = Unit
    }

    private class RecordingEngine : SecondPassAsrEngine {
        override val model = descriptor(ModelKind.ASR_SECOND_PASS, "offline")
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = false,
                supportsPartial = false,
                supportsTokenTiming = false,
                supportsLanguageDetection = false,
                supportsConfidence = false,
                supportsInverseTextNormalization = true,
                punctuationCapability = PunctuationCapability.RELIABLE,
                supportsSecondPass = true,
            )
        val acceptedCounts = mutableListOf<Int>()

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ): AsrHypothesis {
            acceptedCounts += samples.size
            return AsrHypothesis(
                text = "完整",
                punctuationCapability = PunctuationCapability.RELIABLE,
                isFinal = true,
            )
        }

        override fun close() = Unit
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
                        supportsSecondPass = kind == ModelKind.ASR_SECOND_PASS,
                        supportsInverseTextNormalization = kind == ModelKind.ASR_SECOND_PASS,
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
