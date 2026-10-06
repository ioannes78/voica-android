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
    fun reconnectUsesFastThenLowFrequencyRecovery() {
        assertEquals(1_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(4_000L, ReconnectPolicy.delayForAttempt(3))
        assertEquals(8_000L, ReconnectPolicy.delayForAttempt(4))
        assertEquals(15_000L, ReconnectPolicy.delayForAttempt(5))
        assertEquals(30_000L, ReconnectPolicy.delayForAttempt(6))
        assertEquals(30_000L, ReconnectPolicy.delayForAttempt(50))
        assertNull(ReconnectPolicy.delayForAttempt(0))
    }

    @Test
    fun reconnectOnlyRetriesTransientConnectionErrors() {
        assertTrue(
            ReconnectPolicy.shouldRetry(
                BleError(BleErrorCode.CONNECT_FAILED),
            ),
        )
        assertTrue(
            ReconnectPolicy.shouldRetry(
                BleError(BleErrorCode.CONNECT_TIMEOUT),
            ),
        )
        assertTrue(
            ReconnectPolicy.shouldRetry(
                BleError(BleErrorCode.REMOTE_DISCONNECTED),
            ),
        )
        assertFalse(
            ReconnectPolicy.shouldRetry(
                BleError(BleErrorCode.SERVICE_MISSING, recoverable = false),
            ),
        )
        assertFalse(
            ReconnectPolicy.shouldRetry(
                BleError(BleErrorCode.MTU_TOO_SMALL, recoverable = false),
            ),
        )
    }
}
