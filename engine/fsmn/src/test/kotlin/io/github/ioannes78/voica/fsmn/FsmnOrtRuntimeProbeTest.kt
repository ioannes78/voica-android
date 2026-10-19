package io.github.ioannes78.voica.fsmn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FsmnOrtRuntimeProbeTest {
    @Test
    fun exactSherpaRuntimeIsCompatible() {
        val result = FsmnOrtRuntimeProbe.evaluate("1.28.2")

        assertEquals("1.28.2", result.actualVersion)
        assertTrue(result.compatible)
    }

    @Test
    fun otherRuntimeVersionIsNotSilentlyAccepted() {
        val result = FsmnOrtRuntimeProbe.evaluate("1.27.0")

        assertFalse(result.compatible)
    }

    @Test
    fun versionWhitespaceIsNormalizedForDiagnostics() {
        val result = FsmnOrtRuntimeProbe.evaluate("  1.28.2\n")

        assertEquals("1.28.2", result.actualVersion)
        assertTrue(result.compatible)
    }
}
