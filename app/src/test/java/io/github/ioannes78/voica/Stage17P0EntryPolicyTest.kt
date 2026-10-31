package io.github.ioannes78.voica

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage17P0EntryPolicyTest {
    @Test
    fun qaApplicationCanOpenStage17Harnesses() {
        assertTrue(stage17P0LocalLlmEntryVisible("io.github.ioannes78.voica.qa"))
        assertTrue(stage17P1LocalLlmEntryVisible("io.github.ioannes78.voica.qa"))
    }

    @Test
    fun productionApplicationCannotOpenStage17Harnesses() {
        assertFalse(stage17P0LocalLlmEntryVisible("io.github.ioannes78.voica"))
        assertFalse(stage17P1LocalLlmEntryVisible("io.github.ioannes78.voica"))
    }
}
