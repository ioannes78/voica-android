package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DiarizationAnchorEmbeddingCacheTest {
    @Test
    fun reusesExactGlobalRangeAcrossOverlappingWindows() = runBlocking {
        val engine = CountingEmbeddingEngine(embeddingDescriptor())
        val config = testConfig()
        val first =
            buildSpeakerAnchorEmbeddings(
                windowStartSampleIndex = 0L,
                windowSamples = ShortArray(16) { it.toShort() },
                turns = listOf(turn(start = 4L, end = 10L)),
                embeddingEngine = engine,
                config = config,
            )
        val second =
            buildSpeakerAnchorEmbeddings(
                windowStartSampleIndex = 2L,
                windowSamples = ShortArray(16) { (it + 2).toShort() },
                turns = listOf(turn(start = 4L, end = 10L)),
                embeddingEngine = engine,
                config = config,
            )

        assertEquals(1, engine.callCount)
        assertEquals(first.single().embedding.toList(), second.single().embedding.toList())
        assertEquals(6L, first.single().anchorSampleCount)
        assertEquals(6L, second.single().anchorSampleCount)
    }

    @Test
    fun doesNotReuseDifferentGlobalRange() = runBlocking {
        val engine = CountingEmbeddingEngine(embeddingDescriptor())
        val config = testConfig()

        buildSpeakerAnchorEmbeddings(
            windowStartSampleIndex = 0L,
            windowSamples = ShortArray(16) { it.toShort() },
            turns = listOf(turn(start = 4L, end = 10L)),
            embeddingEngine = engine,
            config = config,
        )
        buildSpeakerAnchorEmbeddings(
            windowStartSampleIndex = 0L,
            windowSamples = ShortArray(16) { it.toShort() },
            turns = listOf(turn(start = 5L, end = 11L)),
            embeddingEngine = engine,
            config = config,
        )

        assertEquals(2, engine.callCount)
    }

    @Test
    fun cacheIsRunScopedByEmbeddingEngineInstance() = runBlocking {
        val config = testConfig()
        val firstEngine = CountingEmbeddingEngine(embeddingDescriptor())
        val secondEngine = CountingEmbeddingEngine(embeddingDescriptor())
        val samples = ShortArray(16) { it.toShort() }
        val turns = listOf(turn(start = 4L, end = 10L))

        buildSpeakerAnchorEmbeddings(
            windowStartSampleIndex = 0L,
            windowSamples = samples,
            turns = turns,
            embeddingEngine = firstEngine,
            config = config,
        )
        buildSpeakerAnchorEmbeddings(
            windowStartSampleIndex = 0L,
            windowSamples = samples,
            turns = turns,
            embeddingEngine = secondEngine,
            config = config,
        )

        assertEquals(1, firstEngine.callCount)
        assertEquals(1, secondEngine.callCount)
    }

    @Test
    fun cacheEvictsOldestEntryAtBound() = runBlocking {
        val engine = CountingEmbeddingEngine(embeddingDescriptor())
        val config = testConfig()

        repeat(STAGE13C_ANCHOR_CACHE_MAX_ENTRIES + 1) { index ->
            val start = index.toLong() * 16L
            buildSpeakerAnchorEmbeddings(
                windowStartSampleIndex = start,
                windowSamples = ShortArray(16) { it.toShort() },
                turns = listOf(turn(start = start, end = start + 6L)),
                embeddingEngine = engine,
                config = config,
            )
        }
        assertEquals(STAGE13C_ANCHOR_CACHE_MAX_ENTRIES + 1, engine.callCount)

        buildSpeakerAnchorEmbeddings(
            windowStartSampleIndex = 0L,
            windowSamples = ShortArray(16) { it.toShort() },
            turns = listOf(turn(start = 0L, end = 6L)),
            embeddingEngine = engine,
            config = config,
        )

        assertEquals(STAGE13C_ANCHOR_CACHE_MAX_ENTRIES + 2, engine.callCount)
    }

    private class CountingEmbeddingEngine(
        override val model: ModelDescriptor,
    ) : SpeakerEmbeddingEngine {
        var callCount = 0

        override suspend fun embed(
            samples: ShortArray,
            sampleRateHz: Int,
        ): FloatArray {
            require(sampleRateHz == 16_000)
            require(samples.isNotEmpty())
            callCount += 1
            return floatArrayOf(3F, 4F)
        }

        override fun close() = Unit
    }

    private companion object {
        fun testConfig() =
            DiarizationConfig(
                stitchingMinimumAnchorSamples = 4L,
                stitchingMaxAnchorsPerSpeaker = 1,
            )

        fun turn(
            start: Long,
            end: Long,
        ) =
            DiarizationSpeakerTurn(
                speakerIndex = 0,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                confidence = 0.9F,
                overlap = false,
            )

        fun embeddingDescriptor() =
            ModelDescriptor(
                modelId = "campplus-stage13c-test",
                kind = ModelKind.SPEAKER,
                displayName = "CAM++ Stage 13C Test",
                version = "1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/campplus.onnx",
                mirrors = emptyList(),
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                packageSha256 =
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "campplus.onnx",
                            sizeBytes = 1L,
                            sha256 =
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
                releaseChannel = "candidate",
                autoUpdateEligible = false,
                speakerRole = SpeakerModelRole.EMBEDDING,
            )
    }
}
