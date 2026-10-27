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
        assertEquals(
            "ANR",
            stage17R5ExitReasonLabel(ApplicationExitInfo.REASON_ANR),
        )
    }

    @Test
    fun `ANR trace classifier keeps only sanitized main and render thread frames`() {
        val trace =
            """
            ----- pid 123 at 2026-10-27 19:58:00 -----
            Cmd line: io.github.ioannes78.voica.qa
            \"main\" prio=5 tid=1 Native
              | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x123 self=0x456
              native: #00 pc 0000000000090d88 /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)
              at android.os.MessageQueue.nativePollOnce(Native method)
              at android.os.MessageQueue.next(MessageQueue.java:335)
              at android.os.Looper.loopOnce(Looper.java:186)
              at android.os.Looper.loop(Looper.java:313)
            \"RenderThread\" prio=5 tid=17 Native
              native: #00 pc 0000000000012345 /vendor/lib64/egl/libGLES_mali.so (mali_wait+16)
              at android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native method)
            \"FinalizerDaemon\" prio=5 tid=10 Waiting
              at java.lang.Object.wait(Native method)
            """.trimIndent()

        val summary = parseStage17R5AnrTraceSummary(trace)

        assertTrue(summary.traceAvailable)
        assertEquals("Native", summary.mainState)
        assertEquals("Native", summary.renderThreadState)
        assertTrue(summary.mainTopFrames.first().startsWith("native:libc.so"))
        assertTrue(summary.mainTopFrames.any { it.contains("MessageQueue.nativePollOnce") })
        assertTrue(summary.renderThreadTopFrames.first().contains("libGLES_mali.so"))
        assertTrue(summary.renderThreadTopFrames.any { it.contains("HardwareRenderer") })
        val joined = (summary.mainTopFrames + summary.renderThreadTopFrames).joinToString(" ")
        assertFalse(joined.contains("0000000000"))
        assertFalse(joined.contains("Cmd line"))
    }

    @Test
    fun `ANR breadcrumb display includes trace classification without raw trace`() {
        val text =
            Stage17R5CrashBreadcrumb(
                pid = 321,
                processSessionId = "process-anr",
                attemptSessionId = "attempt-anr",
                requestedBackend = "GPU",
                resolvedBackend = "GPU",
                schemaEnabled = true,
                schemaSizeChars = 2112,
                estimatedPromptTokens = 2008,
                runtimePhase = "GENERATING",
                emissionCount = 0L,
                pssKb = 2_100_000L,
                interactive = true,
                keyguardShowing = false,
                deviceLocked = false,
                timestampEpochMs = 9_000L,
                exitReason = ApplicationExitInfo.REASON_ANR,
                exitReasonLabel = "ANR",
                exitStatus = 0,
                exitImportance = 100,
                exitPssKb = 100_000L,
                exitRssKb = 180_000L,
                exitTimestampEpochMs = 12_000L,
                anrTraceAvailable = true,
                anrMainState = "Blocked",
                anrMainTopFrames = listOf("at android.view.ViewRootImpl.doTraversal(ViewRootImpl.java:1)"),
                anrRenderThreadState = "Native",
                anrRenderThreadTopFrames = listOf("native:libGLES_mali.so:mali_wait"),
            ).toQaDisplayText(previousProcess = true)

        assertTrue(text.contains("exitReason=ANR(6)"))
        assertTrue(text.contains("anrTraceAvailable=true"))
        assertTrue(text.contains("anrMainState=Blocked"))
        assertTrue(text.contains("anrRenderThreadState=Native"))
        assertTrue(text.contains("libGLES_mali.so:mali_wait"))
        assertFalse(text.contains("Cmd line"))
    }
}
