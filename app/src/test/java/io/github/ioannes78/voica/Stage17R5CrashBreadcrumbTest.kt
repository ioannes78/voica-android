package io.github.ioannes78.voica

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R5CrashBreadcrumbTest {
    @Test
    fun `generation progress persistence is throttled without losing first emission`() {
        assertTrue(
            shouldPersistStage17R5GenerationProgress(
                emissionCount = 1L,
                nowMs = 100L,
                lastPersistedAtMs = 0L,
            ),
        )
        assertFalse(
            shouldPersistStage17R5GenerationProgress(
                emissionCount = 2L,
                nowMs = 500L,
                lastPersistedAtMs = 100L,
            ),
        )
        assertTrue(
            shouldPersistStage17R5GenerationProgress(
                emissionCount = 16L,
                nowMs = 600L,
                lastPersistedAtMs = 100L,
            ),
        )
        assertTrue(
            shouldPersistStage17R5GenerationProgress(
                emissionCount = 3L,
                nowMs = 1_100L,
                lastPersistedAtMs = 100L,
            ),
        )
    }

    @Test
    fun `qa breadcrumb formatting contains only scalar forensic metadata`() {
        val text =
            Stage17R5CrashBreadcrumb(
                pid = 123,
                processSessionId = "process-1",
                attemptSessionId = "attempt-1",
                requestedBackend = "GPU",
                resolvedBackend = "GPU",
                schemaEnabled = true,
                schemaSizeChars = 456,
                estimatedPromptTokens = 789,
                runtimePhase = "GENERATING",
                emissionCount = 3L,
                pssKb = 654_321L,
                interactive = true,
                keyguardShowing = false,
                deviceLocked = false,
                timestampEpochMs = 1_234_567L,
                exitReason = ApplicationExitInfo.REASON_CRASH_NATIVE,
                exitReasonLabel = "CRASH_NATIVE",
                exitStatus = 11,
                exitImportance = 100,
                exitPssKb = 700_000L,
                exitRssKb = 1_100_000L,
                exitTimestampEpochMs = 1_234_890L,
            ).toQaDisplayText(previousProcess = true)

        assertTrue(text.contains("source=PREVIOUS_PROCESS"))
        assertTrue(text.contains("runtimePhase=GENERATING"))
        assertTrue(text.contains("schemaSizeChars=456"))
        assertTrue(text.contains("estimatedPromptTokens=789"))
        assertTrue(text.contains("emissionCount=3"))
        assertTrue(text.contains("exitReason=CRASH_NATIVE"))
        assertTrue(text.contains("exitStatus=11"))
        assertTrue(text.contains("exitPssKb=700000"))
        assertFalse(text.contains("prompt="))
        assertFalse(text.contains("transcript="))
        assertFalse(text.contains("generatedText="))
        assertFalse(text.contains("response="))
    }

    @Test
    fun `exit reason labels preserve the decisive Android crash classes`() {
        assertEquals(
            "CRASH_NATIVE",
            stage17R5ExitReasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE),
        )
        assertEquals(
            "LOW_MEMORY",
            stage17R5ExitReasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY),
        )
        assertEquals(
            "SIGNALED",
            stage17R5ExitReasonLabel(ApplicationExitInfo.REASON_SIGNALED),
        )
    }
}
