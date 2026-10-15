package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppPhoneMicrophoneNavigationTest {
    @Test
    fun `notification actions route to the correct phone product`() {
        assertEquals(
            PhoneMicrophoneProductTarget.RECORDING,
            phoneMicrophoneTargetForAction(ACTION_OPEN_PHONE_RECORDING),
        )
        assertEquals(
            PhoneMicrophoneProductTarget.REALTIME,
            phoneMicrophoneTargetForAction(ACTION_OPEN_PHONE_REALTIME),
        )
        assertNull(phoneMicrophoneTargetForAction("io.github.ioannes78.voica.UNKNOWN"))
        assertNull(phoneMicrophoneTargetForAction(null))
    }

    @Test
    fun `foreground notification target follows realtime ownership`() {
        assertEquals(
            PhoneMicrophoneProductTarget.RECORDING,
            phoneMicrophoneProductTarget(realtimeActiveRunId = null),
        )
        assertEquals(
            PhoneMicrophoneProductTarget.RECORDING,
            phoneMicrophoneProductTarget(realtimeActiveRunId = ""),
        )
        assertEquals(
            PhoneMicrophoneProductTarget.REALTIME,
            phoneMicrophoneProductTarget(realtimeActiveRunId = "run-phone-realtime"),
        )
    }
}
