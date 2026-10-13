package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeNotificationNavigationPolicyTest {
    @Test
    fun `logical realtime phases open live reader`() {
        listOf(
            Stage16ARealtimeUiPhase.PREPARING,
            Stage16ARealtimeUiPhase.STARTING,
            Stage16ARealtimeUiPhase.ACTIVE,
            Stage16ARealtimeUiPhase.PAUSED,
            Stage16ARealtimeUiPhase.RESUMING,
            Stage16ARealtimeUiPhase.FINALIZING,
            Stage16ARealtimeUiPhase.INTERRUPTED,
        ).forEach { phase ->
            assertTrue(
                Stage16ARealtimeNotificationPolicy.opensRealtime(
                    Stage16ARealtimeNotificationProjection(phase, "Small"),
                ),
            )
        }
    }

    @Test
    fun `terminal phases restore normal app target`() {
        listOf(
            Stage16ARealtimeUiPhase.IDLE,
            Stage16ARealtimeUiPhase.COMPLETED,
            Stage16ARealtimeUiPhase.FAILED,
        ).forEach { phase ->
            assertFalse(
                Stage16ARealtimeNotificationPolicy.opensRealtime(
                    Stage16ARealtimeNotificationProjection(phase, "Small"),
                ),
            )
        }
        assertFalse(Stage16ARealtimeNotificationPolicy.opensRealtime(null))
    }
}
