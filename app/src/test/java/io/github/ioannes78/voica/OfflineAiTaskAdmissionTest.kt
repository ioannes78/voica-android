package io.github.ioannes78.voica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OfflineAiTaskAdmissionTest {
    @Test fun oldLeaseCannotReleaseNewOwner() {
        val gate = OfflineAiTaskAdmission()
        val first = checkNotNull(gate.tryAcquire(OfflineAiTaskAdmission.Kind.SUMMARY, "r1"))
        assertNull(gate.tryAcquire(OfflineAiTaskAdmission.Kind.QA, "r2"))
        assertTrue(gate.release(first))
        val second = checkNotNull(gate.tryAcquire(OfflineAiTaskAdmission.Kind.QA, "r2"))
        assertFalse(gate.release(first))
        assertSame(second, gate.current())
        assertTrue(gate.release(second))
        assertNull(gate.current())
    }

    @Test fun concurrentAdmissionHasExactlyOneWinner() {
        val gate = OfflineAiTaskAdmission()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { index ->
                pool.submit<OfflineAiTaskAdmission.Lease?> {
                    start.await()
                    gate.tryAcquire(
                        if (index % 2 == 0) OfflineAiTaskAdmission.Kind.SUMMARY else OfflineAiTaskAdmission.Kind.QA,
                        "r$index",
                    )
                }
            }
            start.countDown()
            val winners = futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            assertTrue(gate.release(winners.single()))
        } finally {
            pool.shutdownNow()
        }
    }
}
