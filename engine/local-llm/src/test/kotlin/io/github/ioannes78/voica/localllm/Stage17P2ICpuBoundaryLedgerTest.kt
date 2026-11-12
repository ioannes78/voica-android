package io.github.ioannes78.voica.localllm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only P2-I test: no Android, Engine, native LiteRT-LM, GPU, or real JNI. */
class Stage17P2ICpuBoundaryLedgerTest {
    @Test
    fun submissionEntryDoesNotImplyReturn() {
        var now = 1_000_000_000L
        val ledger = LocalLlmCpuBoundaryLedger(nanoClock = { now })
        ledger.arm()
        now += 5_000_000L
        ledger.record(LocalLlmCpuBoundaryPhase.SEND_ENTERED)
        assertEquals(listOf(LocalLlmCpuBoundaryPhase.SEND_ENTERED), ledger.snapshot().map { it.phase })
        assertEquals(5L, ledger.snapshot().single().elapsedMs)
        // Simulated blocked submit: no fabricated SEND_RETURNED or terminal.
    }

    @Test
    fun callbackMayRunBeforeSynchronousSendReturns() {
        val ledger = LocalLlmCpuBoundaryLedger()
        ledger.arm()
        ledger.record(LocalLlmCpuBoundaryPhase.SEND_ENTERED)
        ledger.record(LocalLlmCpuBoundaryPhase.TERMINAL_DONE)
        ledger.record(LocalLlmCpuBoundaryPhase.SEND_RETURNED)
        ledger.record(LocalLlmCpuBoundaryPhase.TERMINAL_AWAIT_ENTERED)
        assertEquals(
            listOf(
                LocalLlmCpuBoundaryPhase.SEND_ENTERED,
                LocalLlmCpuBoundaryPhase.TERMINAL_DONE,
                LocalLlmCpuBoundaryPhase.SEND_RETURNED,
                LocalLlmCpuBoundaryPhase.TERMINAL_AWAIT_ENTERED,
            ),
            ledger.snapshot().map { it.phase },
        )
    }

    @Test
    fun firstEmissionDeduplicatedEvenWithCallbackRace() {
        val ledger = LocalLlmCpuBoundaryLedger()
        ledger.arm()
        ledger.record(LocalLlmCpuBoundaryPhase.SEND_ENTERED)
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val completed = CountDownLatch(8)
        try {
            repeat(8) {
                pool.execute {
                    try {
                        gate.await()
                        ledger.record(LocalLlmCpuBoundaryPhase.FIRST_EMISSION)
                    } finally {
                        completed.countDown()
                    }
                }
            }
            gate.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(
                1,
                ledger.snapshot().count { it.phase == LocalLlmCpuBoundaryPhase.FIRST_EMISSION },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun zeroEmissionErrorAndNoCallbackRemainDistinct() {
        val noCallback = LocalLlmCpuBoundaryLedger()
        noCallback.arm()
        noCallback.record(LocalLlmCpuBoundaryPhase.SEND_ENTERED)
        noCallback.record(LocalLlmCpuBoundaryPhase.SEND_RETURNED)
        noCallback.record(LocalLlmCpuBoundaryPhase.TERMINAL_AWAIT_ENTERED)
        assertFalse(noCallback.snapshot().any { it.phase == LocalLlmCpuBoundaryPhase.TERMINAL_ERROR })
        val failed = LocalLlmCpuBoundaryLedger()
        failed.arm()
        failed.record(LocalLlmCpuBoundaryPhase.TERMINAL_ERROR)
        assertEquals(LocalLlmCpuBoundaryPhase.TERMINAL_ERROR, failed.snapshot().last().phase)
        assertFalse(failed.snapshot().any { it.phase == LocalLlmCpuBoundaryPhase.FIRST_EMISSION })
    }

    @Test
    fun cancelWithoutTerminalMustRetainReleaseBlockedEvidence() {
        val ledger = LocalLlmCpuBoundaryLedger()
        ledger.arm()
        listOf(
            LocalLlmCpuBoundaryPhase.SEND_ENTERED,
            LocalLlmCpuBoundaryPhase.SEND_RETURNED,
            LocalLlmCpuBoundaryPhase.TERMINAL_AWAIT_ENTERED,
            LocalLlmCpuBoundaryPhase.CANCEL_REQUESTED,
            LocalLlmCpuBoundaryPhase.CANCEL_SIGNAL_SENT,
            LocalLlmCpuBoundaryPhase.CANCEL_ACK_TIMEOUT,
            LocalLlmCpuBoundaryPhase.RELEASE_BLOCKED,
        ).forEach(ledger::record)
        assertEquals(LocalLlmCpuBoundaryPhase.RELEASE_BLOCKED, ledger.snapshot().last().phase)
        assertFalse(ledger.snapshot().any { it.phase == LocalLlmCpuBoundaryPhase.TERMINAL_DONE })
    }

    @Test
    fun boundedDisarmedAndReset() {
        val ledger = LocalLlmCpuBoundaryLedger(capacity = 3)
        ledger.arm()
        repeat(3) { ledger.record(LocalLlmCpuBoundaryPhase.SEND_ENTERED) }
        assertNull(ledger.record(LocalLlmCpuBoundaryPhase.SEND_RETURNED))
        assertEquals(3, ledger.snapshot().size)
        ledger.disarm()
        assertNull(ledger.record(LocalLlmCpuBoundaryPhase.TERMINAL_DONE))
        assertEquals(3, ledger.snapshot().size)
        ledger.arm()
        assertTrue(ledger.snapshot().isEmpty())
        ledger.record(LocalLlmCpuBoundaryPhase.SEND_RETURNED)
        assertEquals(1, ledger.snapshot().size)
    }

    @Test
    fun observerIsOptionalAndFailureIsIsolated() {
        LocalLlmCpuBoundaryObserverRegistry.install(null)
        LocalLlmCpuBoundaryObserverRegistry.emit(LocalLlmCpuBoundaryPhase.SEND_ENTERED)
        val seen = mutableListOf<LocalLlmCpuBoundaryPhase>()
        try {
            LocalLlmCpuBoundaryObserverRegistry.install { phase -> seen.add(phase) }
            LocalLlmCpuBoundaryObserverRegistry.emit(LocalLlmCpuBoundaryPhase.SEND_RETURNED)
            assertEquals(listOf(LocalLlmCpuBoundaryPhase.SEND_RETURNED), seen)
            LocalLlmCpuBoundaryObserverRegistry.install { throw IllegalStateException("fake diagnostic failure") }
            LocalLlmCpuBoundaryObserverRegistry.emit(LocalLlmCpuBoundaryPhase.TERMINAL_ERROR)
        } finally {
            LocalLlmCpuBoundaryObserverRegistry.install(null)
        }
    }
}
