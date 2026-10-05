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

class TimelineAlignmentProgressTest {
    @Test
    fun timelineReferenceReportsDedicatedActivityFromZeroToComplete() = runBlocking {
        val progress = mutableListOf<TranscriptionProgress>()
        val runner =
            TimelineReferenceRunner(
                pcmSourceResolver = FakeResolver(ShortArray(8) { 1 }),
                asrEngineFactory = StreamingAsrEngineFactory { FakeStreamingEngine() },
                readChunkSamples = 4,
            )

        val result =
            runner.run(
                recordingId = "recording",
                expectedTotalSampleCount = 8L,
                speechSegments =
                    listOf(
                        SpeechSegment(0L, 4L),
                        SpeechSegment(4L, 8L),
                    ),
                progressListener = ProgressListener { item -> progress += item },
            )

        assertEquals(2, result.size)
        assertEquals(3, progress.size)
        assertTrue(progress.all { it.phase == TranscriptionPhase.SECOND_PASS })
        assertTrue(progress.all { it.activity == TranscriptionProgressActivity.TIMELINE_ALIGNMENT })
        assertEquals(0L, progress.first().processedUnits)
        assertEquals(2L, progress.first().totalUnits)
        assertEquals(2L, progress.last().processedUnits)
        assertEquals(1.0, progress.last().fraction ?: 0.0, 0.0)
    }

    private class FakeResolver(
        private val samples: ShortArray,
    ) : PcmSourceResolver {
        override suspend fun resolvePcmSource(recordingId: String): PcmSource =
            FakePcmSource(samples.copyOf())
    }

    private class FakePcmSource(
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

    private class FakeStreamingEngine : StreamingAsrEngine {
        override val model = descriptor()
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

        override suspend fun openSession(): StreamingAsrSession = FakeStreamingSession()

        override fun close() = Unit
    }

    private class FakeStreamingSession : StreamingAsrSession {
        override suspend fun acceptSamples(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ) = Unit

        override suspend fun decode(): AsrHypothesis =
            AsrHypothesis(
                text = "",
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            )

        override suspend fun finishInput(): AsrHypothesis =
            AsrHypothesis(
                text = "你好",
                tokens =
                    listOf(
                        RelativeTimedToken("你", 0L),
                        RelativeTimedToken("好", 1L),
                    ),
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = true,
            )

        override suspend fun reset() = Unit

        override fun close() = Unit
    }

    companion object {
        private fun descriptor() =
            ModelDescriptor(
                modelId = "timeline-test",
                kind = ModelKind.ASR_STREAMING,
                displayName = "timeline-test",
                version = "v1",
                revision = 1,
                runtimeId = "test",
                runtimeVersionMin = null,
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = true,
                        supportsPartial = true,
                        supportsTokenTiming = true,
                    ),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/timeline.bin",
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
