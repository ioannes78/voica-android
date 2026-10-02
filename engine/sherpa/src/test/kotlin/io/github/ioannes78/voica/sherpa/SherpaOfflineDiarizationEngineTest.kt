package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgress
import io.github.ioannes78.voica.transcript.DiarizationWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOfflineDiarizationEngineTest {
    @Test
    fun mapsNativeSecondsBackToAbsoluteSamplesAndMarksOverlap() = runBlocking {
        val native =
            FakeNativeSession(
                listOf(
                    NativeDiarizationSegment(
                        startSeconds = 1.0F,
                        endSeconds = 3.0F,
                        speakerIndex = 0,
                        confidence = -0.25F,
                    ),
                    NativeDiarizationSegment(
                        startSeconds = 2.5F,
                        endSeconds = 4.0F,
                        speakerIndex = 1,
                        confidence = -2.0F,
                    ),
                ),
            )
        val engine =
            SherpaOfflineDiarizationEngine(
                segmentationModel = speakerDescriptor(SpeakerModelRole.DIARIZATION_SEGMENTATION),
                embeddingModel = speakerDescriptor(SpeakerModelRole.EMBEDDING),
                native = native,
            )
        val progress = mutableListOf<DiarizationProgress>()

        val result =
            engine.diarize(
                window =
                    DiarizationWindow(
                        startSampleIndex = 1_600_000L,
                        samples = ShortArray(160_000),
                    ),
                config = DiarizationConfig(),
                progressListener = { progress += it },
            )

        assertEquals(2, result.speakerCount)
        assertEquals(2, result.turns.size)

        val first = result.turns[0]
        assertEquals(1_616_000L, first.startSampleIndex)
        assertEquals(1_648_000L, first.endSampleIndexExclusive)
        assertEquals(-0.25F, first.confidence!!, 0.0001F)
        assertTrue(first.overlap)

        val second = result.turns[1]
        assertEquals(1_640_000L, second.startSampleIndex)
        assertEquals(1_664_000L, second.endSampleIndexExclusive)
        assertNull(second.confidence)
        assertTrue(second.overlap)

        assertEquals(DiarizationPhase.DIARIZATION, progress.first().phase)
        assertEquals(0L, progress.first().processedSamples)
        assertEquals(160_000L, progress.last().processedSamples)

        engine.close()
        assertTrue(native.closed)
    }

    @Test
    fun clampsNativeSegmentToCurrentWindow() = runBlocking {
        val native =
            FakeNativeSession(
                listOf(
                    NativeDiarizationSegment(
                        startSeconds = -1.0F,
                        endSeconds = 99.0F,
                        speakerIndex = 0,
                        confidence = 0.8F,
                    ),
                ),
            )
        val engine =
            SherpaOfflineDiarizationEngine(
                segmentationModel = speakerDescriptor(SpeakerModelRole.DIARIZATION_SEGMENTATION),
                embeddingModel = speakerDescriptor(SpeakerModelRole.EMBEDDING),
                native = native,
            )

        val result =
            engine.diarize(
                window =
                    DiarizationWindow(
                        startSampleIndex = 32_000L,
                        samples = ShortArray(16_000),
                    ),
                config = DiarizationConfig(),
            )

        assertEquals(32_000L, result.turns.single().startSampleIndex)
        assertEquals(48_000L, result.turns.single().endSampleIndexExclusive)
        engine.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmbeddingDescriptorWithSegmentationRole() {
        SherpaOfflineDiarizationEngine(
            segmentationModel = speakerDescriptor(SpeakerModelRole.DIARIZATION_SEGMENTATION),
            embeddingModel = speakerDescriptor(SpeakerModelRole.DIARIZATION_SEGMENTATION),
            native = FakeNativeSession(emptyList()),
        )
    }

    private class FakeNativeSession(
        private val segments: List<NativeDiarizationSegment>,
    ) : NativeOfflineDiarizationSession {
        var closed = false

        override fun process(
            samples: FloatArray,
            sampleRateHz: Int,
            expectedSpeakerCount: Int?,
            clusteringThreshold: Float?,
        ): List<NativeDiarizationSegment> {
            check(!closed)
            assertEquals(16_000, sampleRateHz)
            assertTrue(samples.isNotEmpty())
            return segments
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        fun speakerDescriptor(role: SpeakerModelRole): ModelDescriptor =
            ModelDescriptor(
                modelId = "speaker-" + role.name.lowercase(),
                kind = ModelKind.SPEAKER,
                displayName = role.name,
                version = "stage9",
                revision = 1,
                runtimeId = SherpaRuntime.RUNTIME_ID,
                runtimeVersionMin = SherpaRuntime.RUNTIME_VERSION,
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/model.onnx",
                mirrors = emptyList(),
                downloadSizeBytes = 10,
                installedSizeBytes = 10,
                packageSha256 =
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.onnx",
                            sizeBytes = 10,
                            sha256 =
                                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 24,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "upstream",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "candidate",
                autoUpdateEligible = false,
                speakerRole = role,
            )
    }
}
