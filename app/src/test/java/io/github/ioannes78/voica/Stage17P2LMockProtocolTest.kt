package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only, no Android service invocation or model/native/GPU library loading. */
class Stage17P2LMockProtocolTest {
    @Test fun onlyQaPackageCanEnterAndNoNativeModeExists() {
        assertTrue(Stage17P2LMockContract.allowPackage("io.github.ioannes78.voica.qa"))
        assertFalse(Stage17P2LMockContract.allowPackage("io.github.ioannes78.voica"))
        assertFalse(Stage17P2LMockContract.NATIVE_EXECUTION_ENABLED)
        assertEquals(32, Stage17P2LMockContract.MAX_EVENTS)
    }

    @Test fun childProcessAttestationMustMatchExactProcessAndPid() {
        val pkg = "io.github.ioannes78.voica.qa"
        val expected = Stage17P2LMockContract.expectedProcess(pkg)
        assertTrue(Stage17P2LMockContract.matchesChild(pkg, expected, 1234, expected))
        assertFalse(Stage17P2LMockContract.matchesChild(pkg, expected, 1234, null))
        assertFalse(Stage17P2LMockContract.matchesChild(pkg, expected, 1234, "$pkg:model_validator"))
        assertFalse(Stage17P2LMockContract.matchesChild(pkg, "$pkg:model_validator", 1234, expected))
        assertFalse(Stage17P2LMockContract.matchesChild(pkg, expected, 0, expected))
        assertFalse(Stage17P2LMockContract.matchesChild("io.github.ioannes78.voica", expected, 1234, expected))
    }

    @Test fun callbackBeforeSendReturnIsNotReorderedOrInferred() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(7))
        listOf(
            Stage17P2LMockPhase.BIND_REQUESTED,
            Stage17P2LMockPhase.CHILD_BOUND,
            Stage17P2LMockPhase.SEND_ENTERED,
            Stage17P2LMockPhase.FIRST_EMISSION,
            Stage17P2LMockPhase.SEND_RETURNED,
            Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED,
            Stage17P2LMockPhase.TERMINAL_DONE,
            Stage17P2LMockPhase.RELEASE_CONFIRMED,
        ).forEach { assertTrue(ledger.mark(7, it)) }
        val phases = ledger.snapshot().map { it.phase }
        assertTrue(phases.indexOf(Stage17P2LMockPhase.FIRST_EMISSION) <
            phases.indexOf(Stage17P2LMockPhase.SEND_RETURNED))
        assertFalse(ledger.isBlocked())
    }

    @Test fun submissionNotReturnedCannotManufactureTheMissingReturn() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(11))
        assertTrue(ledger.mark(11, Stage17P2LMockPhase.SEND_ENTERED))
        assertFalse(ledger.snapshot().any { it.phase == Stage17P2LMockPhase.SEND_RETURNED })
        assertTrue(ledger.mark(11, Stage17P2LMockPhase.REPORT_TIMEOUT))
        assertTrue(ledger.isPoisoned())
        assertFalse(ledger.arm(12))
    }

    @Test fun absentTerminalAndUnacknowledgedCancelAreDistinct() {
        val noTerminal = Stage17P2LMockLedger()
        assertTrue(noTerminal.arm(1))
        assertTrue(noTerminal.mark(1, Stage17P2LMockPhase.SEND_RETURNED))
        assertTrue(noTerminal.mark(1, Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED))
        assertTrue(noTerminal.mark(1, Stage17P2LMockPhase.REPORT_TIMEOUT))
        assertFalse(noTerminal.snapshot().any { it.phase == Stage17P2LMockPhase.TERMINAL_DONE })
        val cancelled = Stage17P2LMockLedger()
        assertTrue(cancelled.arm(2))
        assertTrue(cancelled.mark(2, Stage17P2LMockPhase.CANCEL_REQUESTED))
        assertTrue(cancelled.mark(2, Stage17P2LMockPhase.CANCEL_SIGNAL_SENT))
        assertTrue(cancelled.mark(2, Stage17P2LMockPhase.RELEASE_UNCERTAIN))
        assertTrue(cancelled.isPoisoned())
        assertFalse(cancelled.snapshot().any { it.phase == Stage17P2LMockPhase.CANCEL_ACKNOWLEDGED })
    }

    @Test fun acknowledgedCancelCanCompleteFakeRelease() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(6))
        assertTrue(ledger.mark(6, Stage17P2LMockPhase.CANCEL_REQUESTED))
        assertTrue(ledger.mark(6, Stage17P2LMockPhase.CANCEL_SIGNAL_SENT))
        assertTrue(ledger.mark(6, Stage17P2LMockPhase.TERMINAL_ERROR))
        assertTrue(ledger.mark(6, Stage17P2LMockPhase.CANCEL_ACKNOWLEDGED))
        assertTrue(ledger.mark(6, Stage17P2LMockPhase.RELEASE_CONFIRMED))
        assertFalse(ledger.isBlocked())
        assertFalse(ledger.isPoisoned())
    }

    @Test fun serviceDeathOrFailedBindPoisonsAttempt() {
        val death = Stage17P2LMockLedger()
        assertTrue(death.arm(1))
        assertTrue(death.mark(1, Stage17P2LMockPhase.BINDER_DIED))
        assertTrue(death.isPoisoned())
        assertFalse(death.arm(2))
        val failedBind = Stage17P2LMockLedger()
        assertTrue(failedBind.arm(5))
        assertTrue(failedBind.mark(5, Stage17P2LMockPhase.BIND_FAILED))
        assertTrue(failedBind.isPoisoned())
    }

    @Test fun rejectsStaleAttemptsDuplicateTerminalAndConcurrentAdmission() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(101))
        assertFalse(ledger.arm(102))
        assertFalse(ledger.canAccept(102))
        assertFalse(ledger.mark(102, Stage17P2LMockPhase.TERMINAL_DONE))
        assertTrue(ledger.mark(101, Stage17P2LMockPhase.TERMINAL_DONE))
        assertFalse(ledger.mark(101, Stage17P2LMockPhase.TERMINAL_DONE))
        assertTrue(ledger.mark(101, Stage17P2LMockPhase.RELEASE_CONFIRMED))
        assertFalse(ledger.mark(101, Stage17P2LMockPhase.FIRST_EMISSION))
    }

    @Test fun ledgerIsBoundedAndContainsOnlyPhaseAndRelativeTime() {
        var clock = 10_000_000_000L
        val ledger = Stage17P2LMockLedger(clockNanos = { clock })
        assertTrue(ledger.arm(8))
        repeat(31) {
            clock += 1_000_000L
            assertTrue(ledger.mark(8, Stage17P2LMockPhase.SEND_ENTERED))
        }
        assertEquals(32, ledger.snapshot().size)
        assertFalse(ledger.mark(8, Stage17P2LMockPhase.SEND_RETURNED))
        assertEquals(31L, ledger.snapshot().last().elapsedMs)
        assertTrue(ledger.snapshot().all { it.elapsedMs >= 0L })
    }

    @Test fun invalidReleaseCannotBePresentedAsConfirmed() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(9))
        assertTrue(ledger.mark(9, Stage17P2LMockPhase.RELEASE_CONFIRMED))
        assertTrue(ledger.isPoisoned())
        assertFalse(ledger.arm(10))
    }
}
