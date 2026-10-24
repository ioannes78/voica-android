package io.github.ioannes78.voica

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage17P0EntryPolicyTest {
    @Test
    fun qaApplicationCanOpenP0Harness() {
        assertTrue(stage17P0LocalLlmEntryVisible("io.github.ioannes78.voica.qa"))
    }

    @Test
    fun productionApplicationCannotOpenP0Harness() {
        assertFalse(stage17P0LocalLlmEntryVisible("io.github.ioannes78.voica"))
    }
}
