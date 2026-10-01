package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSileroVadEngineTest {
    @Test
    fun mapsNativeVadSegmentsOntoAbsoluteCanonicalSamples() = runBlocking {
        val fakeVad =
            FakeVadSession(
                readyAfterAccept =
                    mutableListOf(
                        NativeVadSegment(1_600L, 3_200),
                        NativeVadSegment(8_000L, 1_600),
                    ),
            )
        val engine =
            SherpaSileroVadEngine(
                model = vadDescriptor(),
                modelLocation = SherpaVadModelLocation.File("/tmp/silero.onnx"),
                settings = SileroVadSettings(),
                sessionFactory = NativeVadSessionFactory { _, _ -> fakeVad },
                pcmChunkSamples = 4_000,
            )
        val progress = mutableListOf<TranscriptionProgress>()
        val source = FakePcmSource(totalSampleCount = 16_000L)

        val segments =
            engine.analyze(source) { update ->
                progress += update
            }

        assertEquals(2, segments.size)
        assertEquals(1_600L, segments[0].startSampleIndex)
        assertEquals(4_800L, segments[0].endSampleIndexExclusive)
        assertEquals(8_000L, segments[1].startSampleIndex)
        assertEquals(9_600L, segments[1].endSampleIndexExclusive)
        assertEquals(16_000L, progress.last().processedUnits)
        assertEquals(16_000L, progress.last().totalUnits)
        assertTrue(fakeVad.flushed)
        assertTrue(fakeVad.closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPcmSourceWhoseDeclaredLengthDoesNotMatchReads() = runBlocking {
        val engine =
            SherpaSileroVadEngine(
                model = vadDescriptor(),
                modelLocation = SherpaVadModelLocation.File("/tmp/silero.onnx"),
                settings = SileroVadSettings(),
                sessionFactory = NativeVadSessionFactory { _, _ -> FakeVadSession() },
                pcmChunkSamples = 4_000,
            )

        engine.analyze(
            FakePcmSource(
                totalSampleCount = 16_001L,
                actualSamples = 16_000L,
            ),
        )
    }

    private fun vadDescriptor() =
        ModelDescriptor(
            modelId = "silero-vad-int8",
            kind = ModelKind.VAD,
            displayName = "Silero VAD",
            version = "builtin",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("und"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
            builtinAssetPath = "models/silero/silero_vad.int8.onnx",
            packageFormat = null,
            downloadUrl = null,
            downloadSizeBytes = null,
            installedSizeBytes = 208_000L,
            packageSha256 = null,
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "silero_vad.int8.onnx",
                        sizeBytes = 208_000L,
                        sha256 = "a".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "MIT",
            licenseUrl = null,
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
            homepage = null,
            attribution = "Silero VAD / k2-fsa sherpa-onnx export",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = true,
        )

    private class FakePcmSource(
        override val totalSampleCount: Long,
        private val actualSamples: Long = totalSampleCount,
    ) : PcmSource {
        override val sampleRateHz: Int = 16_000
        override val channelCount: Int = 1
        private var cursor = 0L

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= actualSamples) return null
            val count = minOf(maxSamples.toLong(), actualSamples - cursor).toInt()
            repeat(count) { index ->
                target[targetOffset + index] = 1_000
            }
            val start = cursor
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }

    private class FakeVadSession(
        readyAfterAccept: MutableList<NativeVadSegment> = mutableListOf(),
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
