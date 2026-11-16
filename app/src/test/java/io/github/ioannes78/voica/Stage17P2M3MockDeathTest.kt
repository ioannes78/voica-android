package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests: never call Process.killProcess, Binder or any native code. */
class Stage17P2M3MockDeathTest {
    private val qa = "io.github.ioannes78.voica.qa"
    private val child = Stage17P2LMockContract.expectedProcess(qa)

    @Test fun onlyExactQaChildAndOneExplicitScenarioCanSelfTerminate() {
        val guard = Stage17P2M3MockDeathGuard
        assertTrue(guard.canSelfTerminate(
            qa, child, Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND, true,
        ))
        assertFalse(guard.canSelfTerminate(
            "io.github.ioannes78.voica",
            "io.github.ioannes78.voica:stage17_mock_probe",
            Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND, true,
        ))
        assertFalse(guard.canSelfTerminate(
            qa, qa, Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND, true,
        ))
        assertFalse(guard.canSelfTerminate(
            qa, "$qa:model_validator",
            Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND, true,
        ))
        assertFalse(guard.canSelfTerminate(
            qa, child, Stage17P2LMockScenario.NORMAL, true,
        ))
        assertFalse(guard.canSelfTerminate(
            qa, child, Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND, false,
        ))
    }

    @Test fun unexpectedBinderDeathAfterFakeSendMustPoisonAndNeverRelease() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(21))
        assertTrue(ledger.mark(21, Stage17P2LMockPhase.BIND_REQUESTED))
        assertTrue(ledger.mark(21, Stage17P2LMockPhase.CHILD_BOUND))
        assertTrue(ledger.mark(21, Stage17P2LMockPhase.SEND_ENTERED))
        assertTrue(ledger.mark(21, Stage17P2LMockPhase.BINDER_DIED))
        assertTrue(ledger.isBlocked())
        assertTrue(ledger.isPoisoned())
        assertFalse(ledger.arm(22))
        assertFalse(ledger.snapshot().any { it.phase == Stage17P2LMockPhase.RELEASE_CONFIRMED })
        assertFalse(Stage17P2M1MockCompletionPolicy.canCommit(ledger.snapshot()))
    }

    @Test fun timedOutChildIsNotAutomaticallyRetriedOrCalledReleased() {
        val ledger = Stage17P2LMockLedger()
        assertTrue(ledger.arm(4))
        assertTrue(ledger.mark(4, Stage17P2LMockPhase.SEND_ENTERED))
        assertTrue(ledger.mark(4, Stage17P2LMockPhase.REPORT_TIMEOUT))
        assertTrue(ledger.isPoisoned())
        assertFalse(ledger.arm(5))
    }

    @Test fun persistedPoisonRestoresAsStickyAfterParentReentry() {
        val record = Stage17P2M1MockOwnerRecord(
            generation = 7L,
            token = 321,
            nonce = "a20422ab-f32b-486c-bd3b-a73a8ec3cae2",
            armEpochMs = 1_789_000_000_000L,
            processName = child,
            state = Stage17P2M1MockOwnerState.UNRESOLVED_POISONED,
        )
        val restored = Stage17P2M1MockJournal.restore(
            Stage17P2M1MockJournal.encode(record), qa,
        )
        assertTrue(restored.blocked)
        assertEquals("STICKY_MOCK_POISON", restored.reason)
        val report = Stage17P2M2MockReport.recovered(
            restored, "+20ms BINDER_DIED",
        )
        assertTrue(report.contains("poisoned=true"))
        assertTrue(report.contains("p2m1OwnerBlocked=true"))
        assertTrue(report.contains("p2m1OwnerRecovery=STICKY_MOCK_POISON"))
        assertTrue(report.contains("historicalEvent=+20ms BINDER_DIED"))
        assertTrue(report.contains("eventCount=0"))
    }

    @Test fun oldConnectionCannotResendAfterBinderDeath() {
        val allowed = Stage17P2M2MockConnectionPolicy.canSend(
            bindingIsCurrent = false,
            tokenActive = false,
            ownerBlocked = true,
            releaseCommitPending = false,
            serviceClassMatches = true,
            binderPresent = true,
        )
        assertFalse(allowed)
    }

    @Test fun invalidExitRecordCannotBeMisclassifiedAsMatchingChildDeath() {
        val exit = Stage17P2M1MockExitRecord(
            processName = child,
            pid = 12345,
            uid = 10100,
            exitEpochMs = 1_789_000_001_000L,
            reason = 9,
        )
        val valid = Stage17P2M1MockExitCorrelator.classify(
            candidates = listOf(exit),
            expectedProcess = child,
            childPid = 12345,
            appUid = 10100,
            armEpochMs = 1_789_000_000_000L,
            observedAtEpochMs = 1_789_000_003_000L,
        )
        assertEquals(Stage17P2M1MockExitCorrelator.Verdict.MATCHED, valid)
        val badPid = Stage17P2M1MockExitCorrelator.classify(
            listOf(exit), child, 12346, 10100,
            1_789_000_000_000L, 1_789_000_003_000L,
        )
        assertEquals(Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN, badPid)
        val reusedPid = Stage17P2M1MockExitCorrelator.classify(
            listOf(exit.copy(exitEpochMs = 1_788_000_000_000L)), child, 12345, 10100,
            1_789_000_000_000L, 1_789_000_003_000L,
        )
        assertEquals(Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN, reusedPid)
    }
}


/** Pure JVM testing of generation-scoped crash-exit evidence. */
class Stage17P2M3MockExitEvidenceTest {
    @Test fun matchingGenerationMayRecoverOnlyNormalizedExitVerdict() {
        val x = Stage17P2M3MockExitEvidence.encode(7, "MATCHED")
        assertEquals("MATCHED", Stage17P2M3MockExitEvidence.recovered(x, 7L))
        assertEquals("DIFFERENT_ATTEMPT", Stage17P2M3MockExitEvidence.recovered(x, 8L))
    }

    @Test fun missingOrMalformedExitEvidenceNeverClaimsProcessAttribution() {
        assertEquals("NOT_RECORDED", Stage17P2M3MockExitEvidence.recovered(null, 7L))
        assertEquals("INVALID_RECORD",
            Stage17P2M3MockExitEvidence.recovered("P2M3V1|0|MATCHED", 7L))
        assertEquals("INVALID_RECORD",
            Stage17P2M3MockExitEvidence.recovered("P2M3V1|7|GPU_ANR", 7L))
        assertEquals("INVALID_RECORD",
            Stage17P2M3MockExitEvidence.recovered("random", 7L))
        assertEquals("UNKNOWN", Stage17P2M3MockExitEvidence.recovered(
            Stage17P2M3MockExitEvidence.encode(7, "UNKNOWN"), 7L))
    }
}
