package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.AsrExecutionMode
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelPunctuationMode
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.TimestampCapability
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RelativeTimedToken
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrAdaptiveChunkingTest {
    @Test
    fun hotwordsReduceEstimatedSafeTarget() {
        val plain = estimateFunAsrBudget(LargeOfflineAsrSettings())
        val withHotwords =
            estimateFunAsrBudget(
                LargeOfflineAsrSettings(
                    funAsrHotwords = "OpenAI, Qwen3, Voica, 语音转写, 会议纪要",
                ),
            )

        assertEquals(512, plain.maxTotalLen)
        assertTrue(withHotwords.hotwordPromptTokens > 0)
        assertTrue(withHotwords.safeAudioTokens < plain.safeAudioTokens)
        assertTrue(withHotwords.targetSamples < plain.targetSamples)
    }

    @Test
    fun plannerPrefersReliableLowEnergyBoundaryWithoutOverlap() {
        val sampleRate = 16_000
        val samples = ShortArray(22 * sampleRate) { 4_000 }
        val silenceStart = 17 * sampleRate
        val silenceEnd = silenceStart + sampleRate
        for (index in silenceStart until silenceEnd) samples[index] = 0

        val plan = planFunAsrChunks(samples, LargeOfflineAsrSettings(), sampleRate)

        assertTrue(plan.size >= 2)
        assertTrue(plan.first().endExclusive in silenceStart..silenceEnd)
        assertEquals(0, plan[1].overlapFromPrevious)
        assertTrue(plan[1].naturalBoundaryBefore)
    }

    @Test
    fun plannerUsesOverlapForContinuousSpeech() {
        val sampleRate = 16_000
        val samples = ShortArray(25 * sampleRate) { 4_000 }

        val plan = planFunAsrChunks(samples, LargeOfflineAsrSettings(), sampleRate)

        assertTrue(plan.size >= 2)
        assertTrue(plan[1].overlapFromPrevious in (sampleRate * 8 / 10)..(sampleRate * 12 / 10))
        assertFalse(plan[1].naturalBoundaryBefore)
    }

    @Test
    fun overlapMergeRemovesExactAndFuzzyDuplicatePrefix() {
        assertEquals(
            "今天讨论 OpenAI 的方案，明天继续。",
            mergeFunAsrOverlapText(
                "今天讨论 OpenAI 的方案，",
                "OpenAI 的方案。明天继续。",
            ),
        )
        assertEquals(
            "今天我们讨论语音转写方案下一步安排。",
            mergeFunAsrOverlapText(
                "今天我们讨论语音转写方案",
                "语音转写方按，下一步安排。",
            ),
        )
    }

    @Test
    fun blankSpeechChunkIsDiscardedAndRetriedShorter() = runBlocking {
        FunAsrRuntimeDiagnostics.clear()
        val delegate = RetryFakeEngine(blankFirst = true)
        val engine =
            FunAsrAdaptiveOfflineAsrEngine(
                delegate = delegate,
                settings = LargeOfflineAsrSettings(),
            )
        val samples = ShortArray(10 * 16_000) { 2_000 }

        val result = engine.transcribe(samples, 16_000)
        val diagnostics = FunAsrRuntimeDiagnostics.snapshot()

        assertEquals("第一段测试第二段", result.text)
        assertTrue(delegate.acceptedCounts.first() == samples.size)
        assertTrue(delegate.acceptedCounts.drop(1).all { it < samples.size })
        assertTrue(diagnostics.first().retryReason == "blank_with_vad_speech")
        assertFalse(diagnostics.first().accepted)
        assertTrue(diagnostics.drop(1).all { it.accepted })
        assertTrue(result.tokens.isEmpty())
    }

    @Test
    fun inferredGenerationCapacityTriggersRetry() = runBlocking {
        FunAsrRuntimeDiagnostics.clear()
        val delegate = RetryFakeEngine(capacityFirst = true)
        val engine =
            FunAsrAdaptiveOfflineAsrEngine(
                delegate = delegate,
                settings = LargeOfflineAsrSettings(),
            )
        val samples = ShortArray(10 * 16_000) { 2_000 }

        val result = engine.transcribe(samples, 16_000)
        val diagnostics = FunAsrRuntimeDiagnostics.snapshot()

        assertTrue(result.text.isNotBlank())
        assertTrue(diagnostics.first().generationHitCapacity)
        assertEquals("generation_capacity", diagnostics.first().retryReason)
        assertFalse(diagnostics.first().accepted)
        assertTrue(diagnostics.drop(1).all { it.accepted })
    }

    private class RetryFakeEngine(
        private val blankFirst: Boolean = false,
        private val capacityFirst: Boolean = false,
    ) : SecondPassAsrEngine {
        override val model = descriptor()
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = false,
                supportsPartial = false,
                supportsTokenTiming = true,
                supportsLanguageDetection = true,
                supportsConfidence = false,
                supportsInverseTextNormalization = true,
                punctuationCapability = PunctuationCapability.RELIABLE,
                supportsSecondPass = true,
                executionMode = AsrExecutionMode.OFFLINE,
                timestampCapability = TimestampCapability.TOKEN,
                supportsHotwords = true,
            )
        val acceptedCounts = mutableListOf<Int>()
        private var calls = 0

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ): AsrHypothesis {
            calls += 1
            acceptedCounts += samples.size
            if (calls == 1 && blankFirst) {
                return hypothesis(text = "", sampleCount = samples.size)
            }
            if (calls == 1 && capacityFirst) {
                val tokens =
                    List(320) { index ->
                        RelativeTimedToken(
                            text = "字",
                            startSampleOffset =
                                index.toLong() * (samples.size - 1L) / 319L,
                        )
                    }
                return AsrHypothesis(
                    text = "容量测试".repeat(80),
                    tokens = tokens,
                    detectedLanguage = "zh",
                    punctuationCapability = PunctuationCapability.RELIABLE,
                    isFinal = true,
                )
            }
            val text = if (calls % 2 == 0) "第一段测试" else "测试第二段"
            return hypothesis(text = text, sampleCount = samples.size)
        }

        override fun close() = Unit

        private fun hypothesis(
            text: String,
            sampleCount: Int,
        ): AsrHypothesis =
            AsrHypothesis(
                text = text,
                tokens =
                    if (text.isBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            RelativeTimedToken("首", 0L),
                            RelativeTimedToken("尾", sampleCount - 1L),
                        )
                    },
                detectedLanguage = "zh",
                punctuationCapability = PunctuationCapability.RELIABLE,
                isFinal = true,
            )
    }

    companion object {
        private fun descriptor() =
            ModelDescriptor(
                modelId = "funasr-nano-int8",
                kind = ModelKind.ASR_LARGE,
                displayName = "FunASR Nano",
                version = "2025-12-30",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = setOf("zh", "en"),
                capabilities =
                    ModelCapabilities(
                        supportsTokenTiming = false,
                        supportsLanguageDetection = true,
                        supportsInverseTextNormalization = true,
                        supportsSecondPass = true,
                        supportsHotwords = true,
                        executionMode = AsrExecutionMode.OFFLINE,
                        timestampCapability = TimestampCapability.NONE,
                        punctuationMode = ModelPunctuationMode.NATIVE,
                    ),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.TAR_BZ2,
                downloadUrl = "https://example.invalid/funasr.tar.bz2",
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                packageSha256 = "a".repeat(64),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "encoder_adaptor.int8.onnx",
                            sizeBytes = 1L,
                            sha256 = "b".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 41,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "test",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "candidate",
                autoUpdateEligible = false,
                runtimeModelType = SherpaOfflineAsrModelType.FUNASR_NANO,
            )
    }
}
