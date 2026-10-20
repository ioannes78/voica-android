package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB3ChallengeTest {
    @Test
    fun `ITN profile only runs toggles exposed by the candidate runtimes`() {
        val plans = stage16BGateB3Plans(Stage16BGateB3Profile.ITN_COMPLEX)

        assertEquals(5, plans.size)
        assertEquals(
            listOf("ITN_ON", "ITN_OFF"),
            plans.filter { it.modelId == STAGE16B_FUNASR_NANO_CANDIDATE_ID }.map { it.label },
        )
        assertEquals(
            listOf("ITN_ON", "ITN_OFF"),
            plans.filter { it.modelId == Stage8ModelIds.SECOND_PASS_ASR }.map { it.label },
        )
        assertEquals(
            listOf("NATIVE_NO_ITN_TOGGLE"),
            plans.filter { it.modelId == Stage13AOfflineModelIds.QWEN3_ASR }.map { it.label },
        )
    }

    @Test
    fun `language profile exposes auto zh en only where forcing exists`() {
        val plans = stage16BGateB3Plans(Stage16BGateB3Profile.LANG_MIXED)

        assertEquals(7, plans.size)
        assertEquals(
            listOf("LANG_AUTO", "LANG_FORCE_ZH", "LANG_FORCE_EN"),
            plans.filter { it.modelId == STAGE16B_FUNASR_NANO_CANDIDATE_ID }.map { it.label },
        )
        assertEquals(
            listOf("LANG_AUTO", "LANG_FORCE_ZH", "LANG_FORCE_EN"),
            plans.filter { it.modelId == Stage8ModelIds.SECOND_PASS_ASR }.map { it.label },
        )
        assertEquals(
            listOf("DEFAULT_NO_LANGUAGE_FORCE"),
            plans.filter { it.modelId == Stage13AOfflineModelIds.QWEN3_ASR }.map { it.label },
        )
    }

    @Test
    fun `punctuation profile uses one native pass per model`() {
        val plans = stage16BGateB3Plans(Stage16BGateB3Profile.PUNCTUATION_ZH)

        assertEquals(Stage16BGateBBenchmarkContract.MODEL_IDS, plans.map { it.modelId })
        assertTrue(plans.all { it.label == "DEFAULT_NATIVE" })
    }

    @Test
    fun `ITN semantic coverage treats spoken and digit forms as equivalent evidence`() {
        val spoken =
            "今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五，联系电话是一三八零零一三八零零零。"
        val digit =
            "今天是2026年10月20日，会议下午3点25分开始，预算123456元，完成率87.5%，联系电话13800138000。"

        assertEquals(1.0, stage16BGateB3SemanticCoverage(spoken), 0.0)
        assertEquals(1.0, stage16BGateB3SemanticCoverage(digit), 0.0)
    }

    @Test
    fun `punctuation metrics preserve native question and sentence evidence`() {
        val metrics = stage16BGateB3PunctuationMetrics("第一，预算；第二，进度。你觉得呢？")

        assertEquals(2, metrics.commaCount)
        assertEquals(1, metrics.sentenceEndCount)
        assertEquals(1, metrics.questionMarkCount)
        assertEquals(0, metrics.exclamationMarkCount)
        assertEquals(1, metrics.colonSemicolonCount)
    }

    @Test
    fun `only silence and noise profiles expect no speech`() {
        assertTrue(Stage16BGateB3Profile.SILENCE.expectsNoSpeech)
        assertTrue(Stage16BGateB3Profile.NOISE.expectsNoSpeech)
        assertFalse(Stage16BGateB3Profile.SHORT_UTTERANCE.expectsNoSpeech)
        assertEquals(25, STAGE16B_GATE_B_B3_MAX_ASR_INPUT_SECONDS)
    }
}
