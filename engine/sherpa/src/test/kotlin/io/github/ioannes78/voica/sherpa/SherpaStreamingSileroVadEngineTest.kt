package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaStreamingSileroVadEngineTest {
    @Test
    fun `streaming session reports speech state and completed relative segment`() {
        runBlocking {
            val native = FakeNativeStreamingVadSession()
            val engine =
                SherpaStreamingSileroVadEngine(
                    model = descriptor(),
                    modelLocation = SherpaVadModelLocation.File("/tmp/silero.onnx"),
                    settings = SileroVadSettings(),
                    sessionFactory = NativeStreamingVadSessionFactory { _, _ -> native },
                )
            val session = engine.openSession()

            native.speechDetected = true
            var update = session.acceptSamples(ShortArray(320) { 16_384 })
            assertTrue(update.speechDetected)
            assertTrue(update.completedSegments.isEmpty())

            native.speechDetected = false
            native.pending += NativeVadSegment(startSampleIndex = 40, sampleCount = 200)
            update = session.acceptSamples(ShortArray(320) { -16_384 })
            assertFalse(update.speechDetected)
            assertEquals(1, update.completedSegments.size)
            assertEquals(40L, update.completedSegments.single().startSampleOffset)
            assertEquals(240L, update.completedSegments.single().endSampleOffsetExclusive)

            session.close()
            assertTrue(native.closed)
        }
    }

    @Test
    fun `finish and reset preserve deterministic lifecycle`() {
        runBlocking {
            val native = FakeNativeStreamingVadSession()
            val engine =
                SherpaStreamingSileroVadEngine(
                    model = descriptor(),
                    modelLocation = SherpaVadModelLocation.File("/tmp/silero.onnx"),
                    settings = SileroVadSettings(),
                    sessionFactory = NativeStreamingVadSessionFactory { _, _ -> native },
                )
            val session = engine.openSession()
            session.acceptSamples(ShortArray(320))
            native.pending += NativeVadSegment(startSampleIndex = 0, sampleCount = 320)

            val finalUpdate = session.finishInput()
            assertTrue(native.flushed)
            assertEquals(1, finalUpdate.completedSegments.size)

            session.reset()
            assertTrue(native.resetCalled)
            val afterReset = session.acceptSamples(ShortArray(160))
            assertTrue(afterReset.completedSegments.isEmpty())
        }
    }

    private fun descriptor() =
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
            attribution = "Silero VAD",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = true,
        )

    private class FakeNativeStreamingVadSession : NativeStreamingVadSession {
        val pending = ArrayDeque<NativeVadSegment>()
        var speechDetected = false
        var flushed = false
        var resetCalled = false
        var closed = false

        override fun acceptWaveform(samples: FloatArray) = Unit
        override fun empty(): Boolean = pending.isEmpty()
        override fun front(): NativeVadSegment = pending.first()
        override fun pop() {
            pending.removeFirst()
        }
        override fun isSpeechDetected(): Boolean = speechDetected
        override fun reset() {
            pending.clear()
            speechDetected = false
            resetCalled = true
        }
        override fun flush() {
            flushed = true
        }
        override fun close() {
            closed = true
        }
    }
}
