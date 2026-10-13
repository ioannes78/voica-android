package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRealtimeNavigationTest {
    @Test
    fun `only realtime action is accepted`() {
        assertTrue(isRealtimeOpenAction(ACTION_OPEN_REALTIME))
        assertFalse(isRealtimeOpenAction(null))
        assertFalse(isRealtimeOpenAction("io.github.ioannes78.voica.OTHER"))
    }
}
