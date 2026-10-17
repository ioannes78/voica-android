package io.github.ioannes78.voica.ui.device

import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivitySource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActivityPanelInputSelectorTest {
    @Test
    fun `paused phone recording exposes input selector only in recoverable recording state`() {
        val pausedPhoneRecording =
            fact(
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                actions = setOf(AppActivityAction.RESUME, AppActivityAction.STOP),
            )
        val activePhoneRecording =
            fact(
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                actions = setOf(AppActivityAction.PAUSE, AppActivityAction.STOP),
            )
        val pausedPhoneRealtime =
            fact(
                kind = AppActivityKind.REALTIME_TRANSCRIPTION,
                source = AppActivitySource.PHONE_MICROPHONE,
                actions = setOf(AppActivityAction.RESUME, AppActivityAction.STOP),
            )
        val pausedRecorderCard =
            fact(
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.RECORDER_CARD,
                actions = setOf(AppActivityAction.RESUME, AppActivityAction.STOP),
            )

        assertTrue(shouldShowPausedPhoneInputSelector(pausedPhoneRecording))
        assertFalse(shouldShowPausedPhoneInputSelector(activePhoneRecording))
        assertFalse(shouldShowPausedPhoneInputSelector(pausedPhoneRealtime))
        assertFalse(shouldShowPausedPhoneInputSelector(pausedRecorderCard))
    }

    private fun fact(
        kind: AppActivityKind,
        source: AppActivitySource,
        actions: Set<AppActivityAction>,
    ) =
        AppActivityFact(
            logicalKey = "test",
            kind = kind,
            source = source,
            lifecycleClass = io.github.ioannes78.voica.ui.status.AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
            title = "test",
            availableActions = actions,
        )
}
