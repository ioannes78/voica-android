package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaStreamingZipformerEngineTest {
    @Test
    fun mapsSherpaTokenSecondsToCanonicalSampleOffsets() {
        val mapped =
            validatedTimedTokens(
                tokens = listOf("今", "天", " AI"),
                timestampsSeconds = listOf(0.10F, 0.20F, 0.50F),
                acceptedSampleCount = 16_000L,
            )

        assertEquals(3, mapped.size)
        assertEquals(1_600L, mapped[0].startSampleOffset)
        assertEquals(3_200L, mapped[1].startSampleOffset)
        assertEquals(8_000L, mapped[2].startSampleOffset)
        assertEquals(null, mapped[0].endSampleOffsetExclusive)
    }

    @Test
    fun rejectsNonMonotonicOrOutOfRangeTokenTimingInsteadOfFabricating() {
        assertTrue(
            validatedTimedTokens(
                tokens = listOf("A", "B"),
                timestampsSeconds = listOf(0.3F, 0.2F),
                acceptedSampleCount = 16_000L,
            ).isEmpty(),
        )
        assertTrue(
            validatedTimedTokens(
                tokens = listOf("A"),
                timestampsSeconds = listOf(2.0F),
                acceptedSampleCount = 16_000L,
            ).isEmpty(),
        )
        assertTrue(
            validatedTimedTokens(
                tokens = listOf("A", "B"),
                timestampsSeconds = listOf(0.1F),
                acceptedSampleCount = 16_000L,
            ).isEmpty(),
        )
    }

    @Test
    fun streamingSessionUsesSameContractForPartialAndFinalResults() {
        runBlocking {
            val nativeSession =
                FakeNativeSession(
                    partial =
                        NativeStreamingAsrResult(
                            text = "今天",
                            tokens = listOf("今", "天"),
                            timestampsSeconds = listOf(0.1F, 0.2F),
                        ),
                    final =
                        NativeStreamingAsrResult(
                            text = "今天 AI",
                            tokens = listOf("今", "天", " AI"),
                            timestampsSeconds = listOf(0.1F, 0.2F, 0.5F),
                        ),
                )
            val nativeRecognizer = FakeNativeRecognizer(nativeSession)
            val engine =
                SherpaStreamingZipformerEngine(
                    model = modelDescriptor(),
                    modelFiles = fakeFiles(),
                    settings = StreamingZipformerSettings(),
                    recognizerFactory = NativeStreamingRecognizerFactory { _, _ ->
                        nativeRecognizer
                    },
                )

            val session = engine.openSession()
            session.acceptSamples(ShortArray(16_000) { 16_384 })

            val partial = session.decode()
            assertFalse(partial.isFinal)
            assertEquals("今天", partial.text)
            assertEquals(1_600L, partial.tokens.first().startSampleOffset)

            val final = session.finishInput()
            assertTrue(final.isFinal)
            assertEquals("今天 AI", final.text)
            assertEquals(8_000L, final.tokens.last().startSampleOffset)
            assertEquals(16_000, nativeSession.lastAcceptedSamples.size)
            assertEquals(0.5F, nativeSession.lastAcceptedSamples.first(), 0.0001F)

            session.close()
            engine.close()
            assertTrue(nativeSession.closed)
            assertTrue(nativeRecognizer.closed)
        }
    }

    @Test
    fun resetStartsANewRelativeSampleTimeline() {
        runBlocking {
            val nativeSession =
                FakeNativeSession(
                    partial =
                        NativeStreamingAsrResult(
                            text = "A",
                            tokens = listOf("A"),
                            timestampsSeconds = listOf(0.05F),
                        ),
                    final =
                        NativeStreamingAsrResult(
                            text = "A",
                            tokens = listOf("A"),
                            timestampsSeconds = listOf(0.05F),
                        ),
                )
            val engine =
                SherpaStreamingZipformerEngine(
                    model = modelDescriptor(),
                    modelFiles = fakeFiles(),
                    settings = StreamingZipformerSettings(),
                    recognizerFactory =
                        NativeStreamingRecognizerFactory { _, _ ->
                            FakeNativeRecognizer(nativeSession)
                        },
                )
            val session = engine.openSession()
            session.acceptSamples(ShortArray(1_600))
            session.reset()
            session.acceptSamples(ShortArray(1_600))

            val result = session.decode()
            assertEquals(800L, result.tokens.single().startSampleOffset)
            assertEquals(1, nativeSession.resetCount)

            session.close()
            engine.close()
        }
    }

    private fun modelDescriptor() =
        ModelDescriptor(
            modelId = "small-bilingual-zipformer-int8",
            kind = ModelKind.ASR_STREAMING,
            displayName = "基础中英识别",
            version = "2023-02-16",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities =
                ModelCapabilities(
                    supportsStreaming = true,
                    supportsPartial = true,
                    supportsTokenTiming = true,
                ),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/zipformer.zip",
            downloadSizeBytes = 4L,
            installedSizeBytes = 4L,
            packageSha256 = "a".repeat(64),
            files =
                SmallBilingualZipformerLayout.REQUIRED_FILES.map { name ->
                    ModelFileDescriptor(
                        relativePath = name,
                        sizeBytes = 1L,
                        sha256 = "b".repeat(64),
                    )
                },
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t",
            homepage = null,
            attribution = "k2-fsa / community Zipformer model",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )

    private fun fakeFiles() =
        ZipformerModelFiles(
            encoder = File("/tmp/encoder.int8.onnx"),
            decoder = File("/tmp/decoder.onnx"),
            joiner = File("/tmp/joiner.int8.onnx"),
            tokens = File("/tmp/tokens.txt"),
        )

    private class FakeNativeRecognizer(
        private val session: FakeNativeSession,
    ) : NativeStreamingRecognizer {
        var closed = false

        override fun openSession(): NativeStreamingAsrSession = session

        override fun close() {
            closed = true
        }
    }

    private class FakeNativeSession(
        private val partial: NativeStreamingAsrResult,
        private val final: NativeStreamingAsrResult,
    ) : NativeStreamingAsrSession {
        var lastAcceptedSamples = FloatArray(0)
        var resetCount = 0
        var closed = false

        override fun acceptWaveform(samples: FloatArray) {
            lastAcceptedSamples = samples.copyOf()
        }

        override fun decodeReady(): NativeStreamingAsrResult = partial

        override fun finishInput(): NativeStreamingAsrResult = final

        override fun reset() {
            resetCount += 1
        }

        override fun close() {
            closed = true
        }
    }
}
