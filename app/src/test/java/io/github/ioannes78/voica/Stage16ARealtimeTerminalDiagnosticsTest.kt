package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalStreamEvent
import io.github.ioannes78.voica.audio.StreamingAudioEvent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeTerminalDiagnosticsTest {
    @Test
    fun `bundle release retains local audio snapshot after queued session finalization drains`() = runBlocking {
        Stage16ARealtimePerformanceDiagnostics.clearForTest()
        val root = Files.createTempDirectory("voica-stage16a-terminal-diagnostics").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ModelUseRegistry()
        val small = active(streamingDescriptor())
        val vad = active(vadDescriptor())
        val resolver =
            Stage16ARealtimeModelResolver(
                activeModel = { id ->
                    when (id) {
                        Stage13ARealtimeModelIds.SMALL_BILINGUAL -> small
                        Stage8ModelIds.VAD -> vad
                        else -> null
                    }
                },
                modelUseRegistry = registry,
            )
        val leases = resolver.acquire(LocalSpeechSettings())
        val factory = Stage16ARealtimePipelineBundleFactory(scope, FakeProvider())

        try {
            val bundle =
                factory.create(
                    source =
                        object : CanonicalRealtimeEventSource {
                            override suspend fun receive(): RealtimeCanonicalStreamEvent? = null
                        },
                    models = leases,
                    localAudioRoot = root,
                    persistDurableOutput = {},
                    onLocalAudioResult = {},
                )

            bundle.localAudioSink.offer(StreamingAudioEvent.SessionStarted(3))
            bundle.localAudioSink.offer(
                StreamingAudioEvent.EpochStarted(
                    sessionId = 3,
                    epochId = 1,
                    reason = RealtimeCanonicalEpochStartReason.SESSION_START,
                ),
            )
            bundle.localAudioSink.offer(
                StreamingAudioEvent.PcmFrame(
                    RealtimeCanonicalPcmFrame(
                        sessionId = 3,
                        epochId = 1,
                        startSampleIndex = 0,
                        samples = shortArrayOf(1, 2, 3, 4),
                        epochStartReason = RealtimeCanonicalEpochStartReason.SESSION_START,
                    ),
                ),
            )
            bundle.localAudioSink.offer(
                StreamingAudioEvent.SessionEnded(
                    sessionId = 3,
                    finalEpochId = 1,
                    finalSampleIndexExclusive = 4,
                    reason = RealtimeCanonicalSessionEndReason.STOP,
                    drainTimedOut = false,
                ),
            )

            bundle.release()

            val retained = Stage16ARealtimePerformanceDiagnostics.snapshot()
            assertNotNull(retained)
            val localAudio = retained?.localAudioSink
            assertNotNull(localAudio)
            assertTrue(localAudio?.closed == true)
            assertNull(localAudio?.activeSessionId)
            assertEquals(1L, localAudio?.finalizedSegments)
            assertEquals(0L, localAudio?.workerFailures)
            assertEquals(0L, localAudio?.resultHandlerFailures)
        } finally {
            Stage16ARealtimePerformanceDiagnostics.clearForTest()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class FakeProvider : Stage16ARealtimeEngineProvider {
        override fun openAsr(models: Stage16ARealtimeResolvedModels): StreamingAsrEngine = FakeAsrEngine()

        override fun openVad(models: Stage16ARealtimeResolvedModels): StreamingVadEngine = FakeVadEngine()

        override fun openPunctuation(models: Stage16ARealtimeResolvedModels): PunctuationEngine? = null
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

        override suspend fun openSession(): StreamingAsrSession = error("not used")

        override fun close() = Unit
    }

    private class FakeVadEngine : StreamingVadEngine {
        override val model = vadDescriptor()

        override suspend fun openSession(): StreamingVadSession = object : StreamingVadSession {
            override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) =
                StreamingVadUpdate(false)

            override suspend fun finishInput() = StreamingVadUpdate(false)

            override suspend fun reset() = Unit

            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private companion object {
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
