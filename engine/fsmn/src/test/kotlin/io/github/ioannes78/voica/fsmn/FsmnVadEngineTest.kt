package io.github.ioannes78.voica.fsmn

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsmnVadEngineTest {
    @Test
    fun preservesCanonicalSampleCoordinatesAndClosesNativeSession() = runBlocking {
        val fakeSession =
            FakeSession(
                acceptBatches =
                    ArrayDeque(
                        listOf(
                            emptyList(),
                            listOf(NativeFsmnVadSegment(1_600L, 4_800L)),
                            emptyList(),
                            emptyList(),
                        ),
                    ),
                finishSegments = listOf(NativeFsmnVadSegment(8_000L, 16_000L)),
            )
        val engine =
            FsmnVadEngine(
                model = descriptor(),
                modelLocation = FsmnVadModelLocation("/tmp/model_quant.onnx", "/tmp/vad.mvn"),
                settings = FsmnVadSettings(),
                sessionFactory = NativeFsmnVadSessionFactory { _, _ -> fakeSession },
                pcmChunkSamples = 4_000,
            )
        val progress = mutableListOf<TranscriptionProgress>()

        val segments =
            engine.analyze(FakePcmSource(totalSampleCount = 16_000L)) { update ->
                progress += update
            }

        assertEquals(
            listOf(
                1_600L to 4_800L,
                8_000L to 16_000L,
            ),
            segments.map { it.startSampleIndex to it.endSampleIndexExclusive },
        )
        assertEquals(16_000L, progress.last().processedUnits)
        assertEquals(16_000L, progress.last().totalUnits)
        assertTrue(fakeSession.finished)
        assertTrue(fakeSession.closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSegmentBeyondProcessedPcm() {
        runBlocking {
            val fakeSession =
                FakeSession(
                    acceptBatches =
                        ArrayDeque(
                            listOf(
                                listOf(NativeFsmnVadSegment(0L, 8_000L)),
                            ),
                        ),
                )
            val engine =
                FsmnVadEngine(
                    model = descriptor(),
                    modelLocation = FsmnVadModelLocation("/tmp/model_quant.onnx", "/tmp/vad.mvn"),
                    settings = FsmnVadSettings(),
                    sessionFactory = NativeFsmnVadSessionFactory { _, _ -> fakeSession },
                    pcmChunkSamples = 4_000,
                )

            engine.analyze(FakePcmSource(totalSampleCount = 4_000L))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSequentialPcmSource() {
        runBlocking {
            val engine =
                FsmnVadEngine(
                    model = descriptor(),
                    modelLocation = FsmnVadModelLocation("/tmp/model_quant.onnx", "/tmp/vad.mvn"),
                    settings = FsmnVadSettings(),
                    sessionFactory = NativeFsmnVadSessionFactory { _, _ -> FakeSession() },
                    pcmChunkSamples = 4_000,
                )

            engine.analyze(
                FakePcmSource(
                    totalSampleCount = 8_000L,
                    secondReadStartOverride = 4_001L,
                ),
            )
        }
    }

    @Test
    fun emptySourceDoesNotOpenNativeSession() = runBlocking {
        var createCount = 0
        val engine =
            FsmnVadEngine(
                model = descriptor(),
                modelLocation = FsmnVadModelLocation("/tmp/model_quant.onnx", "/tmp/vad.mvn"),
                settings = FsmnVadSettings(),
                sessionFactory =
                    NativeFsmnVadSessionFactory { _, _ ->
                        createCount += 1
                        FakeSession()
                    },
                pcmChunkSamples = 4_000,
            )

        val segments = engine.analyze(FakePcmSource(totalSampleCount = 0L))

        assertTrue(segments.isEmpty())
        assertEquals(0, createCount)
    }

    private fun descriptor() =
        ModelDescriptor(
            modelId = "fsmn-vad-onnx-int8-candidate",
            kind = ModelKind.VAD,
            displayName = "FSMN VAD Candidate",
            version = "2026-10-19",
            revision = 1,
            runtimeId = "onnxruntime",
            runtimeVersionMin = "1.28.2",
            runtimeVersionMax = "1.28.2",
            languages = setOf("und"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/fsmn-vad.zip",
            downloadSizeBytes = 1L,
            installedSizeBytes = 2L,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model_quant.onnx",
                        sizeBytes = 1L,
                        sha256 = "b".repeat(64),
                    ),
                    ModelFileDescriptor(
                        relativePath = "vad.mvn",
                        sizeBytes = 1L,
                        sha256 = "c".repeat(64),
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 92,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx",
            homepage = null,
            attribution = "FunASR FSMN-VAD ONNX candidate",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
        )

    private class FakePcmSource(
        override val totalSampleCount: Long,
        private val secondReadStartOverride: Long? = null,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        private var cursor = 0L
        private var readCount = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= totalSampleCount) return null
            val count = minOf(maxSamples.toLong(), totalSampleCount - cursor).toInt()
            repeat(count) { index -> target[targetOffset + index] = 1_000 }
            val normalStart = cursor
            cursor += count
            val start =
                if (readCount == 1 && secondReadStartOverride != null) {
                    secondReadStartOverride
                } else {
                    normalStart
                }
            readCount += 1
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }

    private class FakeSession(
        private val acceptBatches: ArrayDeque<List<NativeFsmnVadSegment>> = ArrayDeque(),
        private val finishSegments: List<NativeFsmnVadSegment> = emptyList(),
    ) : NativeFsmnVadSession {
        var finished = false
        var closed = false

        override fun accept(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ): List<NativeFsmnVadSegment> =
            if (acceptBatches.isEmpty()) emptyList() else acceptBatches.removeFirst()

        override fun finish(): List<NativeFsmnVadSegment> {
            finished = true
            return finishSegments
        }

        override fun close() {
            closed = true
        }
    }
}
