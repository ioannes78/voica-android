package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeHypothesisStabilizerTest {
    @Test
    fun `promotes only prefix surviving three consecutive chinese partials`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 3)
        stabilizer.accept(continuityStarted())

        val first = stabilizer.accept(partial("今天"))
        val second = stabilizer.accept(partial("今天天"))
        val third = stabilizer.accept(partial("今天天气"))

        assertEquals(listOf(AsrHypothesisStability.PARTIAL), first.stabilities())
        assertEquals(listOf(AsrHypothesisStability.PARTIAL), second.stabilities())
        assertEquals(
            listOf(AsrHypothesisStability.STABLE, AsrHypothesisStability.PARTIAL),
            third.stabilities(),
        )
        assertEquals("今天", third.hypotheses().first().hypothesis.text)
    }

    @Test
    fun `duplicate partial contributes stability evidence without duplicate mutable emission`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 3)
        stabilizer.accept(continuityStarted())

        assertEquals(1, stabilizer.accept(partial("hello")).hypotheses().size)
        assertTrue(stabilizer.accept(partial("hello")).isEmpty())
        val third = stabilizer.accept(partial("hello"))

        assertEquals(1, third.hypotheses().size)
        assertEquals(AsrHypothesisStability.STABLE, third.hypotheses().single().hypothesis.stability)
        assertEquals("hello", third.hypotheses().single().hypothesis.text)
    }

    @Test
    fun `stable token prefix preserves english leading whitespace token timing`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 3)
        stabilizer.accept(continuityStarted())
        val tokens =
            listOf(
                RelativeTimedToken("hello", 0),
                RelativeTimedToken(" world", 1_600),
            )

        stabilizer.accept(partial("hello", tokens.take(1)))
        stabilizer.accept(partial("hello world", tokens))
        val output = stabilizer.accept(partial("hello world!", tokens))
        val stable = output.hypotheses().single { it.hypothesis.stability == AsrHypothesisStability.STABLE }

        assertEquals("hello", stable.hypothesis.text)
        assertEquals(listOf("hello"), stable.hypothesis.tokens.map { it.text })
    }

    @Test
    fun `final is authoritative and duplicate final is suppressed`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 2)
        stabilizer.accept(continuityStarted())
        stabilizer.accept(partial("旧文本"))
        stabilizer.accept(partial("旧文本"))

        val final = final("最终文本")
        val first = stabilizer.accept(final)
        val duplicate = stabilizer.accept(final)

        assertEquals(1, first.hypotheses().size)
        assertEquals("最终文本", first.hypotheses().single().hypothesis.text)
        assertEquals(AsrHypothesisStability.FINAL, first.hypotheses().single().hypothesis.stability)
        assertTrue(duplicate.isEmpty())
    }

    @Test
    fun `new continuity resets stability evidence`() {
        val stabilizer = RealtimeHypothesisStabilizer(confirmationWindow = 2)
        stabilizer.accept(continuityStarted(continuityIndex = 1))
        stabilizer.accept(partial("A", continuityIndex = 1))
        stabilizer.accept(partial("A", continuityIndex = 1))

        stabilizer.accept(continuityStarted(continuityIndex = 2))
        val output = stabilizer.accept(partial("A", continuityIndex = 2))

        assertEquals(listOf(AsrHypothesisStability.PARTIAL), output.stabilities())
    }

    @Test
    fun `maps relative token timing to canonical samples without fabrication`() {
        val event =
            partial(
                text = "今天",
                tokens =
                    listOf(
                        RelativeTimedToken("今", 800, 1_000),
                        RelativeTimedToken("天", 1_600, null),
                    ),
                recognizerBaseSampleIndex = 32_000,
                acceptedEndSampleIndexExclusive = 48_000,
            )

        val mapped = event.canonicalTokens()
        assertEquals(32_800L, mapped[0].startSampleIndex)
        assertEquals(33_000L, mapped[0].endSampleIndexExclusive)
        assertEquals(33_600L, mapped[1].startSampleIndex)

        val invalid =
            partial(
                text = "坏",
                tokens = listOf(RelativeTimedToken("坏", 20_000)),
                recognizerBaseSampleIndex = 32_000,
                acceptedEndSampleIndexExclusive = 48_000,
            )
        assertTrue(invalid.canonicalTokens().isEmpty())
    }

    private fun continuityStarted(continuityIndex: Long = 1) =
        RealtimeAsrPipelineEvent.ContinuityStarted(
            sessionId = 1,
            epochId = 1,
            continuityIndex = continuityIndex,
            startSampleIndex = 0,
            epochReason = null,
        )

    private fun partial(
        text: String,
        tokens: List<RelativeTimedToken> = emptyList(),
        continuityIndex: Long = 1,
        recognizerBaseSampleIndex: Long = 0,
        acceptedEndSampleIndexExclusive: Long = 16_000,
    ) = RealtimeAsrPipelineEvent.Hypothesis(
        sessionId = 1,
        epochId = 1,
        continuityIndex = continuityIndex,
        recognizerBaseSampleIndex = recognizerBaseSampleIndex,
        acceptedEndSampleIndexExclusive = acceptedEndSampleIndexExclusive,
        hypothesis =
            AsrHypothesis(
                text = text,
                tokens = tokens,
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = false,
            ),
    )

    private fun final(text: String) =
        RealtimeAsrPipelineEvent.Hypothesis(
            sessionId = 1,
            epochId = 1,
            continuityIndex = 1,
            recognizerBaseSampleIndex = 0,
            acceptedEndSampleIndexExclusive = 16_000,
            hypothesis =
                AsrHypothesis(
                    text = text,
                    punctuationCapability = PunctuationCapability.NONE,
                    isFinal = true,
                ),
            finalizationReason = RealtimeAsrFinalizationReason.SESSION_END,
        )

    private fun List<RealtimeAsrPipelineEvent>.hypotheses() =
        filterIsInstance<RealtimeAsrPipelineEvent.Hypothesis>()

    private fun List<RealtimeAsrPipelineEvent>.stabilities() =
        hypotheses().map { it.hypothesis.stability }
}
