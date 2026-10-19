package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTenVadEngineTest {
    @Test
    fun officialBaselineUsesSherpaV1138TenDefaults() {
        val settings = TenVadSettings()

        assertEquals(0.5F, settings.threshold)
        assertEquals(0.25F, settings.minSilenceDurationSeconds)
        assertEquals(0.25F, settings.minSpeechDurationSeconds)
        assertEquals(256, settings.windowSizeSamples)
        assertEquals(5.0F, settings.maxSpeechDurationSeconds)
    }

    @Test
    fun gateAControlChangesOnlyMaximumSpeechDuration() {
        val baseline = TenVadSettings()
        val control = TenVadSettings.gateAThirtySecondControl()

        assertEquals(baseline.threshold, control.threshold)
        assertEquals(baseline.minSilenceDurationSeconds, control.minSilenceDurationSeconds)
        assertEquals(baseline.minSpeechDurationSeconds, control.minSpeechDurationSeconds)
        assertEquals(baseline.windowSizeSamples, control.windowSizeSamples)
        assertEquals(30.0F, control.maxSpeechDurationSeconds)
    }

    @Test
    fun mapsNativeSegmentsOntoCanonicalSampleTimeline() = runBlocking {
        val fakeVad =
            FakeVadSession(
                mutableListOf(
                    NativeVadSegment(800L, 1_600),
                    NativeVadSegment(4_800L, 3_200),
                ),
            )
        val engine =
            SherpaTenVadEngine(
                model = vadDescriptor(),
                modelLocation = SherpaVadModelLocation.File("/tmp/ten-vad.onnx"),
                settings = TenVadSettings(),
                sessionFactory = TenNativeVadSessionFactory { _, _ -> fakeVad },
                pcmChunkSamples = 4_000,
            )

        val segments = engine.analyze(FakePcmSource(16_000L))

        assertEquals(2, segments.size)
        assertEquals(800L, segments[0].startSampleIndex)
        assertEquals(2_400L, segments[0].endSampleIndexExclusive)
        assertEquals(4_800L, segments[1].startSampleIndex)
        assertEquals(8_000L, segments[1].endSampleIndexExclusive)
        assertTrue(fakeVad.flushed)
        assertTrue(fakeVad.closed)
    }

    private fun vadDescriptor() =
        ModelDescriptor(
            modelId = SHERPA_TEN_VAD_CANDIDATE_MODEL_ID,
            kind = ModelKind.VAD,
            displayName = "TEN VAD FP32",
            version = "asr-models-2025-07-11",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = "1.13.8",
            languages = setOf("und"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = null,
            downloadUrl = null,
            downloadSizeBytes = null,
            installedSizeBytes = 332_211L,
            packageSha256 = null,
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "ten-vad.onnx",
                        sizeBytes = 332_211L,
                        sha256 = "718cb7eef47e3cf5ddbe7e967a7503f46b8b469c0706872f494dfa921b486206",
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 92,
            appVersionMax = null,
            licenseId = "Apache-2.0-WITH-TEN-VAD-ADDITIONAL-TERMS",
            licenseUrl = null,
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
            homepage = null,
            attribution = "TEN VAD via sherpa-onnx",
            redistributionPolicy = RedistributionPolicy.DOWNLOAD_FROM_ORIGIN_ONLY,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
        )

    private class FakePcmSource(
        override val totalSampleCount: Long,
    ) : PcmSource {
        override val sampleRateHz: Int = 16_000
        override val channelCount: Int = 1
        private var cursor = 0L

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= totalSampleCount) return null
            val count = minOf(maxSamples.toLong(), totalSampleCount - cursor).toInt()
            repeat(count) { index -> target[targetOffset + index] = 1_000 }
            val start = cursor
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }

    private class FakeVadSession(
        readyAfterAccept: MutableList<NativeVadSegment>,
    ) : NativeVadSession {
        private val pending = ArrayDeque(readyAfterAccept)
        var flushed = false
        var closed = false

        override fun acceptWaveform(samples: FloatArray) = Unit
        override fun empty(): Boolean = pending.isEmpty()
        override fun front(): NativeVadSegment = pending.first()
        override fun pop() {
            pending.removeFirst()
        }
        override fun flush() {
            flushed = true
        }
        override fun close() {
            closed = true
        }
    }
}
