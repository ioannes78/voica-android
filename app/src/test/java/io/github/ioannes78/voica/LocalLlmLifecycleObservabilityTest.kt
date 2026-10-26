package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmLifecycleObservabilityTest {
    @Test
    fun signalReducerTracksProcessAndResumedActivityIndependently() {
        var state =
            LocalLlmLifecycleSignalState(
                processForeground = false,
                resumedActivityCount = 0,
            )

        state = state.reduce(LocalLlmLifecycleTransition.PROCESS_FOREGROUND)
        assertTrue(state.processForeground)
        assertFalse(state.activityResumed)

        state = state.reduce(LocalLlmLifecycleTransition.ACTIVITY_RESUMED)
        assertTrue(state.processForeground)
        assertTrue(state.activityResumed)
        assertEquals(1, state.resumedActivityCount)

        state = state.reduce(LocalLlmLifecycleTransition.PROCESS_BACKGROUND)
        assertFalse(state.processForeground)
        assertTrue(state.activityResumed)

        state = state.reduce(LocalLlmLifecycleTransition.ACTIVITY_PAUSED)
        assertFalse(state.processForeground)
        assertFalse(state.activityResumed)
        assertEquals(0, state.resumedActivityCount)
    }

    @Test
    fun signalReducerDoesNotUnderflowAndDoesNotCollapseScreenSignalsIntoBackground() {
        val initial =
            LocalLlmLifecycleSignalState(
                processForeground = true,
                resumedActivityCount = 0,
            )

        val paused = initial.reduce(LocalLlmLifecycleTransition.ACTIVITY_PAUSED)
        assertEquals(0, paused.resumedActivityCount)
        assertTrue(paused.processForeground)

        val screenOff = paused.reduce(LocalLlmLifecycleTransition.SCREEN_OFF)
        assertEquals(paused, screenOff)
        assertTrue(screenOff.processForeground)
    }

    @Test
    fun formatterCorrelatesRuntimeWithIndependentLifecycleSignals() {
        val snapshot =
            LocalLlmLifecycleSnapshot(
                processForeground = false,
                activityResumed = false,
                interactive = false,
                keyguardShowing = true,
                deviceLocked = true,
                lastTransition = LocalLlmLifecycleTransition.SCREEN_OFF,
                timestampNanos = 2_000_000_000L,
            )
        val text =
            formatLocalLlmLifecycleDiagnostics(
                listOf(
                    LocalLlmLifecycleDiagnosticEvent(
                        source = LocalLlmLifecycleDiagnosticSource.LIFECYCLE,
                        detail = "SCREEN_OFF",
                        timestampNanos = 2_000_000_000L,
                        lifecycle = snapshot,
                        runtimeState = LocalLlmRuntimeState.GENERATING,
                        generationActive = true,
                        emissionCount = 7L,
                    ),
                    LocalLlmLifecycleDiagnosticEvent(
                        source = LocalLlmLifecycleDiagnosticSource.RUNTIME,
                        detail = "RELEASING",
                        timestampNanos = 2_250_000_000L,
                        lifecycle = snapshot,
                        runtimeState = LocalLlmRuntimeState.RELEASING,
                        generationActive = false,
                        emissionCount = 7L,
                    ),
                ),
            )

        assertTrue(text.contains("lifecycle=SCREEN_OFF"))
        assertTrue(text.contains("runtime=GENERATING"))
        assertTrue(text.contains("runtime=RELEASING"))
        assertTrue(text.contains("interactive=false"))
        assertTrue(text.contains("keyguard=true"))
        assertTrue(text.contains("deviceLocked=true"))
        assertTrue(text.contains("emissions=7"))
        assertTrue(text.contains("+250ms"))
    }

    @Test
    fun formatterAppliesRequestedTailLimit() {
        val snapshot =
            LocalLlmLifecycleSnapshot(
                processForeground = true,
                activityResumed = true,
                interactive = true,
                keyguardShowing = false,
                deviceLocked = false,
                lastTransition = LocalLlmLifecycleTransition.ACTIVITY_RESUMED,
                timestampNanos = 1L,
            )
        val events =
            (1L..5L).map { index ->
                LocalLlmLifecycleDiagnosticEvent(
                    source = LocalLlmLifecycleDiagnosticSource.RUNTIME,
                    detail = "state-$index",
                    timestampNanos = index * 1_000_000L,
                    lifecycle = snapshot,
                    runtimeState = LocalLlmRuntimeState.IDLE,
                    generationActive = false,
                    emissionCount = 0L,
                )
            }

        val text = formatLocalLlmLifecycleDiagnostics(events, limit = 2)

        assertTrue(text.contains("lifecycleDiagnostics=2"))
        assertFalse(text.contains("state-3"))
        assertTrue(text.contains("state-4"))
        assertTrue(text.contains("state-5"))
    }
}
