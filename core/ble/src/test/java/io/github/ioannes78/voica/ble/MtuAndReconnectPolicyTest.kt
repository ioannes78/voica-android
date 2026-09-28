package io.github.ioannes78.voica.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtuAndReconnectPolicyTest {
    @Test
    fun mtuCapabilityUses39And171Boundaries() {
        assertFalse(MtuPolicy.evaluate(23).isReady)
        assertFalse(MtuPolicy.evaluate(38).isReady)

        val mtu39 = MtuPolicy.evaluate(39)
        assertTrue(mtu39.atomic36Supported)
        assertFalse(mtu39.data168Supported)

        assertFalse(MtuPolicy.evaluate(170).data168Supported)
        assertTrue(MtuPolicy.evaluate(171).data168Supported)
        assertTrue(MtuPolicy.evaluate(247).data168Supported)
        assertTrue(MtuPolicy.evaluate(517).data168Supported)
        assertEquals(517, MtuPolicy.evaluate(517).requestedMtu)
    }

    @Test
    fun reconnectStopsAfterThreeAttempts() {
        assertEquals(1_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(4_000L, ReconnectPolicy.delayForAttempt(3))
        assertNull(ReconnectPolicy.delayForAttempt(4))
        assertNull(ReconnectPolicy.delayForAttempt(0))
    }
}
