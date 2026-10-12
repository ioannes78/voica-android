package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.AsrExecutionMode
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.TimestampCapability
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAsrSession
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import io.github.ioannes78.voica.transcript.StreamingVadSession
import io.github.ioannes78.voica.transcript.StreamingVadUpdate
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimePipelineBundleTest {
    @Test
    fun `pipeline config only accepts approved chunk benchmark candidates`() {
        assertEquals(80, Stage16ARealtimePipelineConfig().targetChunkDurationMs)
        listOf(640, 1_280, 1_600, 2_560, 3_200).forEach { samples ->
            Stage16ARealtimePipelineConfig(
                targetChunkSamples = samples,
                vadPrerollSamples = samples,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Stage16ARealtimePipelineConfig(targetChunkSamples = 960)
        }
    }

    @Test
    fun `bundle release closes native engines and model leases exactly once`() = runBlocking {
        val root = Files.createTempDirectory("voica-stage16a-bundle").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ModelUseRegistry()
        val resolver =
            Stage16ARealtimeModelResolver(
                activeModel = { id -> activeModels()[id] },
                modelUseRegistry = registry,
            )
        val leases = resolver.acquire(LocalSpeechSettings())
        val provider = FakeProvider()
        val factory = Stage16ARealtimePipelineBundleFactory(scope, provider)
        try {
            val bundle =
                factory.create(
                    source = CanonicalRealtimeEventSource { null },
                    models = leases,
                    localAudioRoot = root,
                    persistDurableOutput = {},
                    onLocalAudioResult = {},
                )

            leases.models.all.forEach { model ->
                assertTrue(
                    registry.isInUse(
                        model.descriptor.modelId,
                        model.descriptor.version,
                        model.descriptor.revision,
                    ),
                )
            }
            bundle.release()
            bundle.release()

            assertEquals(1, provider.asr.closeCalls)
            assertEquals(1, provider.vad.closeCalls)
            assertEquals(1, provider.punctuation.closeCalls)
            assertTrue(bundle.durableOutputSink.snapshot().workerCompleted)
            assertTrue(bundle.localAudioSink.snapshot().closed)
            leases.models.all.forEach { model ->
                assertFalse(
                    registry.isInUse(
                        model.descriptor.modelId,
                        model.descriptor.version,
                        model.descriptor.revision,
                    ),
                )
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class FakeProvider : Stage16ARealtimeEngineProvider {
        val asr = FakeAsrEngine()
        val vad = FakeVadEngine()
        val punctuation = FakePunctuationEngine()

        override fun openAsr(models: Stage16ARealtimeResolvedModels): StreamingAsrEngine = asr

        override fun openVad(models: Stage16ARealtimeResolvedModels): StreamingVadEngine = vad

        override fun openPunctuation(models: Stage16ARealtimeResolvedModels): PunctuationEngine = punctuation
    }

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
        var closeCalls = 0

        override suspend fun openSession(): StreamingAsrSession = error("not used")

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeVadEngine : StreamingVadEngine {
        override val model = vadDescriptor()
        var closeCalls = 0

        override suspend fun openSession(): StreamingVadSession = object : StreamingVadSession {
            override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) =
                StreamingVadUpdate(false)
            override suspend fun finishInput() = StreamingVadUpdate(false)
            override suspend fun reset() = Unit
            override fun close() = Unit
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakePunctuationEngine : PunctuationEngine {
        override val model = punctuationDescriptor()
        var closeCalls = 0

        override suspend fun addPunctuation(text: String): String = text

        override fun close() {
            closeCalls += 1
        }
    }

    private companion object {
        fun activeModels(): Map<String, ActiveModel> {
            val small = active(streamingDescriptor())
            val vad = active(vadDescriptor())
            val punctuation = active(punctuationDescriptor())
            return listOf(small, vad, punctuation).associateBy { it.descriptor.modelId }
        }

        fun active(descriptor: ModelDescriptor) =
            ActiveModel(
                descriptor = descriptor,
                manifestDigest = "d".repeat(64),
                installedDirectory = File("/tmp/${descriptor.modelId}"),
            )

        fun streamingDescriptor() =
            descriptor(
                id = Stage13ARealtimeModelIds.SMALL_BILINGUAL,
                kind = ModelKind.ASR_STREAMING,
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = true,
                        supportsPartial = true,
                        supportsTokenTiming = true,
                        executionMode = AsrExecutionMode.TRUE_STREAMING,
                        timestampCapability = TimestampCapability.TOKEN,
                        supportedParameters = setOf("numThreads", "decodingMethod", "maxActivePaths"),
                    ),
            )

        fun vadDescriptor() =
            descriptor(
                id = Stage8ModelIds.VAD,
                kind = ModelKind.VAD,
                capabilities = ModelCapabilities(),
            )

        fun punctuationDescriptor() =
            descriptor(
                id = Stage8ModelIds.PUNCTUATION,
                kind = ModelKind.PUNCTUATION,
                capabilities = ModelCapabilities(),
            )

        fun descriptor(
            id: String,
            kind: ModelKind,
            capabilities: ModelCapabilities,
        ) = ModelDescriptor(
            modelId = id,
            kind = kind,
            displayName = id,
            version = "v1",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = if (kind == ModelKind.VAD) setOf("und") else setOf("zh", "en"),
            capabilities = capabilities,
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/$id.zip",
            downloadSizeBytes = 1,
            installedSizeBytes = 1,
            packageSha256 = "a".repeat(64),
            files = listOf(ModelFileDescriptor("model.int8.onnx", 1, "b".repeat(64))),
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
