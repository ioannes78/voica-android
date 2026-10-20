package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.TokenSource
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB4TimelineTest {
    @Test
    fun `B4 plan separates native current-product and candidate-probe routes`() {
        val plans = stage16BGateB4Plans()

        assertEquals(5, plans.size)
        assertEquals(
            listOf(
                Stage16BGateB4Route.NATIVE_ONLY,
                Stage16BGateB4Route.FUNASR_ALIGNMENT_PROBE,
            ),
            plans.filter { it.modelId == STAGE16B_FUNASR_NANO_CANDIDATE_ID }.map { it.route },
        )
        assertEquals(
            listOf(Stage16BGateB4Route.NATIVE_ONLY),
            plans.filter { it.modelId == Stage8ModelIds.SECOND_PASS_ASR }.map { it.route },
        )
        assertEquals(
            listOf(
                Stage16BGateB4Route.NATIVE_ONLY,
                Stage16BGateB4Route.QWEN_CURRENT_PRODUCT_ALIGNMENT,
            ),
            plans.filter { it.modelId == Stage13AOfflineModelIds.QWEN3_ASR }.map { it.route },
        )
        assertTrue(Stage16BGateB4Route.QWEN_CURRENT_PRODUCT_ALIGNMENT.currentProductPath)
        assertFalse(Stage16BGateB4Route.FUNASR_ALIGNMENT_PROBE.currentProductPath)
        assertEquals(25, STAGE16B_GATE_B_B4_MAX_ASR_INPUT_SECONDS)
    }

    @Test
    fun `timeline metrics accept monotonic canonical sample-index tokens`() {
        val metrics =
            stage16BGateB4TimelineMetrics(
                listOf(
                    segment(
                        index = 0,
                        start = 100,
                        end = 1_000,
                        text = "第一段",
                        tokenStarts = listOf(120, 400, 800),
                    ),
                    segment(
                        index = 1,
                        start = 2_000,
                        end = 3_000,
                        text = "第二段",
                        tokenStarts = listOf(2_050, 2_500),
                    ),
                ),
            )

        assertEquals(2, metrics.transcriptSegmentCount)
        assertEquals(2, metrics.nonBlankSegmentCount)
        assertEquals(5, metrics.tokenCount)
        assertEquals(5, metrics.timedTokenCount)
        assertEquals(2, metrics.timedSegmentCount)
        assertEquals(0, metrics.monotonicViolationCount)
        assertEquals(0, metrics.outOfBoundsTokenCount)
        assertTrue(metrics.structurallyValid)
    }

    @Test
    fun `timeline metrics reject non-monotonic or out-of-segment tokens`() {
        val metrics =
            stage16BGateB4TimelineMetrics(
                listOf(
                    segment(
                        index = 0,
                        start = 100,
                        end = 1_000,
                        text = "测试",
                        tokenStarts = listOf(500, 400, 1_000),
                    ),
                ),
            )

        assertEquals(1, metrics.monotonicViolationCount)
        assertEquals(1, metrics.outOfBoundsTokenCount)
        assertFalse(metrics.structurallyValid)
    }

    @Test
    fun `native absence is evidence for FunASR while alignment requires timed output`() {
        val noNativeTiming =
            Stage16BGateB4Observation(
                modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                route = Stage16BGateB4Route.NATIVE_ONLY,
                pipelineWallMillis = 1,
                alignmentActivitySeen = false,
                alignmentWallMillis = null,
                timelineFallbackError = null,
                metrics = zeroTimingMetrics(),
                rawText = "测试",
                errorMessage = null,
            )
        assertTrue(noNativeTiming.routePass)
        assertFalse(noNativeTiming.nativeTimingAvailable)

        val aligned =
            noNativeTiming.copy(
                route = Stage16BGateB4Route.FUNASR_ALIGNMENT_PROBE,
                alignmentActivitySeen = true,
                alignmentWallMillis = 12,
                metrics =
                    Stage16BGateB4TimelineMetrics(
                        transcriptSegmentCount = 1,
                        nonBlankSegmentCount = 1,
                        tokenCount = 2,
                        timedTokenCount = 2,
                        timedSegmentCount = 1,
                        monotonicViolationCount = 0,
                        outOfBoundsTokenCount = 0,
                    ),
            )
        assertTrue(aligned.routePass)
    }

    @Test
    fun `SenseVoice native route cannot pass without native timed tokens`() {
        val missingNativeTiming =
            Stage16BGateB4Observation(
                modelId = Stage8ModelIds.SECOND_PASS_ASR,
                route = Stage16BGateB4Route.NATIVE_ONLY,
                pipelineWallMillis = 1,
                alignmentActivitySeen = false,
                alignmentWallMillis = null,
                timelineFallbackError = null,
                metrics = zeroTimingMetrics(),
                rawText = "测试",
                errorMessage = null,
            )

        assertFalse(missingNativeTiming.routePass)
        assertFalse(missingNativeTiming.nativeTimingAvailable)
    }

    private fun zeroTimingMetrics() =
        Stage16BGateB4TimelineMetrics(
            transcriptSegmentCount = 1,
            nonBlankSegmentCount = 1,
            tokenCount = 0,
            timedTokenCount = 0,
            timedSegmentCount = 0,
            monotonicViolationCount = 0,
            outOfBoundsTokenCount = 0,
        )

    private fun segment(
        index: Int,
        start: Long,
        end: Long,
        text: String,
        tokenStarts: List<Long>,
    ): TranscriptSegment =
        TranscriptSegment(
            segmentIndex = index,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            firstPassRawText = "",
            secondPassRawText = text,
            finalText = text,
            tokens =
                tokenStarts.mapIndexed { tokenIndex, tokenStart ->
                    TranscriptToken(
                        text = "t$tokenIndex",
                        startSampleIndex = tokenStart,
                        endSampleIndexExclusive = null,
                        source = TokenSource.SECOND_PASS,
                    )
                },
        )
}
