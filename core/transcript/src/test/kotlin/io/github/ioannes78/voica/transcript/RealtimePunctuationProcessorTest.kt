package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePunctuationProcessorTest {
    @Test
    fun `partial never runs external punctuation`() = runBlocking {
        val engine = FakePunctuationEngine { "$it。" }
        val processor = RealtimePunctuationProcessor(engine)

        val result = processor.process(event("今天开会", AsrHypothesisStability.PARTIAL))

        assertFalse(result.attempted)
        assertEquals("今天开会", result.displayText)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `final uses external punctuation and preserves raw text`() = runBlocking {
        val engine = FakePunctuationEngine { "$it。" }
        val processor = RealtimePunctuationProcessor(engine)

        val result = processor.process(event("今天开会", AsrHypothesisStability.FINAL))

        assertTrue(result.attempted)
        assertTrue(result.externalPunctuationApplied)
        assertEquals("今天开会", result.rawText)
        assertEquals("今天开会。", result.displayText)
        assertEquals(PunctuationCapability.RELIABLE, result.displayHypothesis.punctuationCapability)
        assertEquals(1L, processor.snapshot().finalAttempts)
        assertEquals(1L, processor.snapshot().successes)
    }

    @Test
    fun `stable punctuation is disabled by default and can be benchmark-enabled`() = runBlocking {
        val engine = FakePunctuationEngine { "$it，" }
        val finalOnly = RealtimePunctuationProcessor(engine)
        val stableEvent = event("项目进展", AsrHypothesisStability.STABLE)

        val skipped = finalOnly.process(stableEvent)
        assertFalse(skipped.attempted)
        assertEquals(0, engine.calls)

        val enabled =
            RealtimePunctuationProcessor(
                punctuationEngine = engine,
                stableMode = RealtimeStablePunctuationMode.STABLE_AND_FINAL,
            )
        val applied = enabled.process(stableEvent)
        assertTrue(applied.attempted)
        assertEquals("项目进展，", applied.displayText)
        assertEquals(1L, enabled.snapshot().stableAttempts)
    }

    @Test
    fun `punctuation failure falls back to raw final without failing ASR`() = runBlocking {
        val engine = FakePunctuationEngine { error("native punctuation failed") }
        val processor = RealtimePunctuationProcessor(engine)

        val result = processor.process(event("保持原文", AsrHypothesisStability.FINAL))

        assertTrue(result.attempted)
        assertTrue(result.externalPunctuationFailed)
        assertFalse(result.externalPunctuationApplied)
        assertEquals("保持原文", result.displayText)
        assertEquals(1L, processor.snapshot().failures)
    }

    @Test
    fun `reliable native punctuation is never double punctuated`() = runBlocking {
        val engine = FakePunctuationEngine { "$it。" }
        val processor = RealtimePunctuationProcessor(engine)
        val reliable =
            event("已经完成。", AsrHypothesisStability.FINAL).copy(
                hypothesis =
                    event("已经完成。", AsrHypothesisStability.FINAL).hypothesis.copy(
                        punctuationCapability = PunctuationCapability.RELIABLE,
                    ),
            )

        val result = processor.process(reliable)

        assertFalse(result.attempted)
        assertEquals("已经完成。", result.displayText)
        assertEquals(0, engine.calls)
        assertEquals(1L, processor.snapshot().nativeReliableSkips)
    }

    @Test
    fun `missing punctuation engine leaves final usable`() = runBlocking {
        val processor = RealtimePunctuationProcessor(punctuationEngine = null)

        val result = processor.process(event("模型未安装", AsrHypothesisStability.FINAL))

        assertFalse(result.attempted)
        assertEquals("模型未安装", result.displayText)
        assertFalse(result.externalPunctuationFailed)
    }

    private fun event(
        text: String,
        stability: AsrHypothesisStability,
    ): RealtimeAsrPipelineEvent.Hypothesis {
        val isFinal = stability == AsrHypothesisStability.FINAL
        return RealtimeAsrPipelineEvent.Hypothesis(
            sessionId = 1,
            epochId = 2,
            continuityIndex = 3,
            recognizerBaseSampleIndex = 0,
            acceptedEndSampleIndexExclusive = 16_000,
            hypothesis =
                AsrHypothesis(
                    text = text,
                    punctuationCapability = PunctuationCapability.NONE,
                    isFinal = isFinal,
                    stability = stability,
                ),
            finalizationReason =
                if (isFinal) RealtimeAsrFinalizationReason.SESSION_END else null,
        )
    }

    private class FakePunctuationEngine(
        private val transform: (String) -> String,
    ) : PunctuationEngine {
        override val model: ModelDescriptor = punctuationDescriptor()
        var calls = 0

        override suspend fun addPunctuation(text: String): String {
            calls += 1
            return transform(text)
        }

        override fun close() = Unit
    }

    private companion object {
        fun punctuationDescriptor() =
            ModelDescriptor(
                modelId = "ct-transformer-zh-en-int8",
                kind = ModelKind.PUNCTUATION,
                displayName = "标点",
                version = "v1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = setOf("zh", "en"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.ZIP,
                downloadUrl = "https://example.invalid/punctuation.zip",
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
            )
    }
}
