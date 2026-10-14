package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaStreamingZipformerCtcEngineTest {
    @Test
    fun resolvesZipformer2CtcLayoutWithoutAssumingTransducerFiles() {
        val directory = Files.createTempDirectory("voica-ctc-test").toFile()
        try {
            Zipformer2CtcLayout.REQUIRED_FILES.forEach { name ->
                directory.resolve(name).writeBytes(byteArrayOf(1))
            }

            val files = Zipformer2CtcModelFiles.fromDirectory(directory)

            assertEquals("model.int8.onnx", files.model.name)
            assertEquals("tokens.txt", files.tokens.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ctcEngineUsesTrueStreamingPartialFinalResetAndCloseLifecycle() {
        runBlocking {
            val nativeSession =
                FakeNativeSession(
                    partial =
                        NativeStreamingAsrResult(
                            text = "中文",
                            tokens = listOf("中", "文"),
                            timestampsSeconds = listOf(0.1F, 0.2F),
                        ),
                    final =
                        NativeStreamingAsrResult(
                            text = "中文识别",
                            tokens = listOf("中", "文", "识", "别"),
                            timestampsSeconds = listOf(0.1F, 0.2F, 0.3F, 0.4F),
                        ),
                )
            val nativeRecognizer = FakeNativeRecognizer(nativeSession)
            val engine =
                SherpaStreamingZipformerCtcEngine(
                    model = descriptor(SherpaStreamingAsrModelType.ZIPFORMER2_CTC),
                    modelFiles =
                        Zipformer2CtcModelFiles(
                            model = File("/tmp/model.int8.onnx"),
                            tokens = File("/tmp/tokens.txt"),
                        ),
                    settings = StreamingZipformerSettings(nativeModelType = ""),
                    recognizerFactory =
                        NativeStreamingCtcRecognizerFactory { _, _ -> nativeRecognizer },
                )

            val session = engine.openSession()
            session.acceptSamples(ShortArray(16_000) { 16_384 })
            val partial = session.decode()
            assertFalse(partial.isFinal)
            assertEquals("中文", partial.text)
            assertEquals(1_600L, partial.tokens.first().startSampleOffset)

            val final = session.finishInput()
            assertTrue(final.isFinal)
            assertEquals("中文识别", final.text)
            assertEquals(6_400L, final.tokens.last().startSampleOffset)
            assertEquals(0.5F, nativeSession.lastAcceptedSamples.first(), 0.0001F)

            session.reset()
            session.acceptSamples(ShortArray(1_600))
            assertFalse(session.decode().isFinal)
            assertEquals(1, nativeSession.resetCount)

            session.close()
            engine.close()
            assertTrue(nativeSession.closed)
            assertTrue(nativeRecognizer.closed)
        }
    }

    @Test
    fun factoryRejectsUnknownRuntimeModelTypeBeforeNativeLoad() {
        val directory = Files.createTempDirectory("voica-unknown-asr-test").toFile()
        try {
            val descriptor = descriptor("unknown-streaming-family")
            val error =
                runCatching {
                    createSherpaStreamingAsrEngine(
                        model = descriptor,
                        modelDirectory = directory,
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("runtimeModelType") == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descriptor(runtimeModelType: String) =
        ModelDescriptor(
            modelId = "streaming-test",
            kind = ModelKind.ASR_STREAMING,
            displayName = "流式测试模型",
            version = "2025-06-30",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
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
            packageFormat = ModelPackageFormat.TAR_BZ2,
            downloadUrl = "https://example.invalid/model.tar.bz2",
            downloadSizeBytes = 1L,
            installedSizeBytes = 2L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.int8.onnx",
                        sizeBytes = 1,
                        sha256 = "b".repeat(64),
                    ),
                    ModelFileDescriptor(
                        relativePath = "tokens.txt",
                        sizeBytes = 1,
                        sha256 = "c".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 41,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "test",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
            runtimeModelType = runtimeModelType,
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
