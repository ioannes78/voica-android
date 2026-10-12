package io.github.ioannes78.voica

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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeModelResolverTest {
    @Test
    fun `small bilingual is resolved exactly and leases stay held until close`() = runBlocking {
        val registry = ModelUseRegistry()
        val small = active(streamingDescriptor(Stage13ARealtimeModelIds.SMALL_BILINGUAL, null))
        val ctc = active(streamingDescriptor(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC, "zipformer2-ctc"))
        val vad = active(vadDescriptor())
        val punctuation = active(punctuationDescriptor())
        val lookup = mapOf(
            small.descriptor.modelId to small,
            ctc.descriptor.modelId to ctc,
            vad.descriptor.modelId to vad,
            punctuation.descriptor.modelId to punctuation,
        )
        val resolver = Stage16ARealtimeModelResolver({ lookup[it] }, registry)

        val bundle =
            resolver.acquire(
                LocalSpeechSettings(
                    realtimeAsrModel = RealtimeAsrModelChoice.SMALL_BILINGUAL,
                    requestedThreads = 3,
                    realtime =
                        LocalRealtimeAsrSettings(
                            decodingMethod = RealtimeDecodingMethod.MODIFIED_BEAM_SEARCH,
                            maxActivePaths = 8,
                        ),
                ),
            )

        assertEquals(Stage13ARealtimeModelIds.SMALL_BILINGUAL, bundle.models.asr.descriptor.modelId)
        assertEquals(3, bundle.models.performance.effectiveThreads)
        assertEquals(RealtimeDecodingMethod.MODIFIED_BEAM_SEARCH, bundle.models.realtimeSettings.decodingMethod)
        assertEquals(8, bundle.models.realtimeSettings.maxActivePaths)
        assertEquals(Stage8ModelIds.PUNCTUATION, bundle.models.punctuation?.descriptor?.modelId)
        bundle.models.all.forEach { active ->
            assertTrue(
                registry.isInUse(
                    active.descriptor.modelId,
                    active.descriptor.version,
                    active.descriptor.revision,
                ),
            )
        }

        bundle.close()
        bundle.close()
        bundle.models.all.forEach { active ->
            assertFalse(
                registry.isInUse(
                    active.descriptor.modelId,
                    active.descriptor.version,
                    active.descriptor.revision,
                ),
            )
        }
    }

    @Test
    fun `large CTC selection never silently falls back to small`() = runBlocking {
        val registry = ModelUseRegistry()
        val small = active(streamingDescriptor(Stage13ARealtimeModelIds.SMALL_BILINGUAL, null))
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

        val error =
            assertThrows(MissingRealtimeTranscriptionModelsException::class.java) {
                runBlocking {
                    resolver.acquire(
                        LocalSpeechSettings(
                            realtimeAsrModel = RealtimeAsrModelChoice.CHINESE_LARGE_CTC,
                        ),
                    )
                }
            }

        assertEquals(listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC), error.modelIds)
        assertFalse(
            registry.isInUse(
                small.descriptor.modelId,
                small.descriptor.version,
                small.descriptor.revision,
            ),
        )
    }

    @Test
    fun `missing punctuation remains a usable realtime model set`() = runBlocking {
        val registry = ModelUseRegistry()
        val ctc = active(streamingDescriptor(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC, "zipformer2-ctc"))
        val vad = active(vadDescriptor())
        val resolver =
            Stage16ARealtimeModelResolver(
                activeModel = { id ->
                    when (id) {
                        Stage13ARealtimeModelIds.CHINESE_LARGE_CTC -> ctc
                        Stage8ModelIds.VAD -> vad
                        else -> null
                    }
                },
                modelUseRegistry = registry,
            )

        val bundle =
            resolver.acquire(
                LocalSpeechSettings(
                    realtimeAsrModel = RealtimeAsrModelChoice.CHINESE_LARGE_CTC,
                ),
            )

        assertEquals(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC, bundle.models.asr.descriptor.modelId)
        assertNull(bundle.models.punctuation)
        assertEquals(2, bundle.models.all.size)
        bundle.close()
    }

    @Test
    fun `legacy auto normalizes to frozen small bilingual route`() = runBlocking {
        val registry = ModelUseRegistry()
        val small = active(streamingDescriptor(Stage13ARealtimeModelIds.SMALL_BILINGUAL, null))
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

        val bundle = resolver.acquire(LocalSpeechSettings(realtimeAsrModel = RealtimeAsrModelChoice.AUTO))

        assertEquals(Stage13ARealtimeModelIds.SMALL_BILINGUAL, bundle.models.asr.descriptor.modelId)
        bundle.close()
    }

    private fun active(descriptor: ModelDescriptor) =
        ActiveModel(
            descriptor = descriptor,
            manifestDigest = "d".repeat(64),
            installedDirectory = File("/tmp/${descriptor.modelId}"),
        )

    private fun streamingDescriptor(modelId: String, runtimeModelType: String?) =
        descriptor(
            modelId = modelId,
            kind = ModelKind.ASR_STREAMING,
            capabilities =
                ModelCapabilities(
                    supportsStreaming = true,
                    supportsPartial = true,
                    supportsTokenTiming = true,
                    executionMode = AsrExecutionMode.TRUE_STREAMING,
                    timestampCapability = TimestampCapability.TOKEN,
                    supportedParameters = setOf("decodingMethod", "maxActivePaths", "numThreads"),
                ),
            runtimeModelType = runtimeModelType,
        )

    private fun vadDescriptor() =
        descriptor(
            modelId = Stage8ModelIds.VAD,
            kind = ModelKind.VAD,
            capabilities = ModelCapabilities(),
        )

    private fun punctuationDescriptor() =
        descriptor(
            modelId = Stage8ModelIds.PUNCTUATION,
            kind = ModelKind.PUNCTUATION,
            capabilities = ModelCapabilities(),
        )

    private fun descriptor(
        modelId: String,
        kind: ModelKind,
        capabilities: ModelCapabilities,
        runtimeModelType: String? = null,
    ) = ModelDescriptor(
        modelId = modelId,
        kind = kind,
        displayName = modelId,
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
        downloadUrl = "https://example.invalid/$modelId.zip",
        downloadSizeBytes = 1,
        installedSizeBytes = 1,
        packageSha256 = "a".repeat(64),
        files =
            listOf(
                ModelFileDescriptor(
                    relativePath = "model.int8.onnx",
                    sizeBytes = 1,
                    sha256 = "b".repeat(64),
                ),
            ),
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
        runtimeModelType = runtimeModelType,
    )
}
