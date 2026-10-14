package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeExecutionLeaseTest {
    @Test
    fun `acquire and release own one non reference counted run lease`() {
        Stage16ARealtimeExecutionLeaseDiagnostics.clearForTest()
        val handle = FakeWakeLockHandle()
        val lease = Stage16ARealtimeExecutionLease(handle)

        assertTrue(lease.acquire())
        assertTrue(handle.held)
        assertEquals(1, handle.acquireCalls)

        assertTrue(lease.acquire())
        assertEquals(1, handle.acquireCalls)

        lease.release()
        assertFalse(handle.held)
        assertEquals(1, handle.releaseCalls)

        val snapshot = lease.snapshot()
        assertEquals(1, snapshot.acquireAttempts)
        assertEquals(1, snapshot.acquireSuccesses)
        assertEquals(1, snapshot.releaseAttempts)
        assertEquals(1, snapshot.releaseSuccesses)
        assertFalse(snapshot.wakeLockHeld)
        assertNull(snapshot.lastFailure)
        assertEquals(snapshot, Stage16ARealtimeExecutionLeaseDiagnostics.snapshot())
    }

    @Test
    fun `wake lock acquisition failure degrades diagnostics without throwing`() {
        Stage16ARealtimeExecutionLeaseDiagnostics.clearForTest()
        val handle = FakeWakeLockHandle(failAcquire = true)
        val lease = Stage16ARealtimeExecutionLease(handle)

        assertFalse(lease.acquire())
        val snapshot = lease.snapshot()
        assertEquals(1, snapshot.acquireAttempts)
        assertEquals(0, snapshot.acquireSuccesses)
        assertFalse(snapshot.wakeLockHeld)
        assertTrue(snapshot.lastFailure?.startsWith("IllegalStateException:") == true)
    }

    private class FakeWakeLockHandle(
        private val failAcquire: Boolean = false,
    ) : Stage16APartialWakeLockHandle {
        var held = false
        var acquireCalls = 0
        var releaseCalls = 0

        override val isHeld: Boolean
            get() = held

        override fun acquire() {
            acquireCalls += 1
            if (failAcquire) error("blocked")
            held = true
        }

        override fun release() {
            releaseCalls += 1
            held = false
        }
    }
}
