package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16BGateB3HotwordTest {
    @Test
    fun `hotword A B only includes supported candidates`() {
        val plans = stage16BGateB3HotwordPlans()

        assertEquals(4, plans.size)
        assertEquals(
            listOf("HOTWORDS_OFF", "HOTWORDS_ON"),
            plans.filter { it.modelId == STAGE16B_FUNASR_NANO_CANDIDATE_ID }.map { it.label },
        )
        assertEquals(
            listOf("HOTWORDS_OFF", "HOTWORDS_ON"),
            plans.filter { it.modelId == Stage13AOfflineModelIds.QWEN3_ASR }.map { it.label },
        )
        assertFalse(plans.any { it.modelId == Stage8ModelIds.SECOND_PASS_ASR })
    }

    @Test
    fun `hotword on and off variants use exact same fixed context vocabulary`() {
        val plans = stage16BGateB3HotwordPlans()
        val off = plans.filter { it.label == "HOTWORDS_OFF" }
        val on = plans.filter { it.label == "HOTWORDS_ON" }

        assertEquals(2, off.size)
        assertTrue(off.all { it.hotwords.isEmpty() })
        assertEquals(2, on.size)
        assertTrue(on.all { it.hotwords == "Qwen3,OpenAI,GitHub" })
    }

    @Test
    fun `hotword coverage is case and whitespace tolerant but not semantic substitution tolerant`() {
        assertEquals(
            listOf("Qwen3", "OpenAI", "GitHub"),
            stage16BGateB3HotwordHits("今天测试 qwen 3、Open AI 和 github。"),
        )
        assertEquals(
            listOf("OpenAI"),
            stage16BGateB3HotwordHits("今天测试 Queen三、OpenAI 和 Git Hap。"),
        )
    }

    @Test
    fun `hotword reference contains all fixed targets`() {
        val normalized = STAGE16B_GATE_B_B3_HOTWORD_REFERENCE.lowercase()
        STAGE16B_GATE_B_B3_HOTWORDS.split(',').forEach { target ->
            assertTrue(normalized.contains(target.lowercase()))
        }
    }
}
