package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-M1 tests: pure fake ownership, no Android/JNI/model/GPU. */
class Stage17P2M1MockRecoveryTest {
    private val pkg = "io.github.ioannes78.voica.qa"
    private val process = Stage17P2LMockContract.expectedProcess(pkg)
    private val nonce = "ce3bcabc-1782-4ec6-bac2-02546f7b6b0a"

    private fun record(state: Stage17P2M1MockOwnerState) =
        Stage17P2M1MockOwnerRecord(10, 321, nonce, 1_789_000_000_000L, process, state)

    @Test fun absentJournalFromLegacyMockIsNotNativeEvidence() {
        val x = Stage17P2M1MockJournal.restore(null, pkg)
        assertFalse(x.blocked)
        assertEquals("NO_MOCK_JOURNAL", x.reason)
    }

    @Test fun durableArmInterruptedAtRestartFailsClosed() {
        val x = Stage17P2M1MockJournal.restore(
            Stage17P2M1MockJournal.encode(record(Stage17P2M1MockOwnerState.ARMED)), pkg,
        )
        assertTrue(x.blocked)
        assertEquals("INTERRUPTED_MOCK_ATTEMPT", x.reason)
    }

    @Test fun durableReleaseMustBePresentBeforeAllowingAnotherMock() {
        val x = Stage17P2M1MockJournal.restore(
            Stage17P2M1MockJournal.encode(record(Stage17P2M1MockOwnerState.RELEASE_CONFIRMED)),
            pkg,
        )
        assertFalse(x.blocked)
        assertEquals(10L, x.record?.generation)
    }

    @Test fun stickyPoisonRemainsAfterRestart() {
        val x = Stage17P2M1MockJournal.restore(
            Stage17P2M1MockJournal.encode(record(Stage17P2M1MockOwnerState.UNRESOLVED_POISONED)),
            pkg,
        )
        assertTrue(x.blocked)
        assertEquals("STICKY_MOCK_POISON", x.reason)
    }

    @Test fun malformedTruncatedOrWrongProcessJournalAlwaysBlocks() {
        listOf(
            "",
            "old-version",
            "P2M1V1|bad|321|$nonce|1789000000000|$process|ARMED",
            "P2M1V1|1|1|not-a-nonce|1789000000000|$process|RELEASE_CONFIRMED",
            Stage17P2M1MockJournal.encode(record(Stage17P2M1MockOwnerState.RELEASE_CONFIRMED))
                .replace(process, "$pkg:model_validator"),
            Stage17P2M1MockJournal.encode(record(Stage17P2M1MockOwnerState.RELEASE_CONFIRMED))
                .replace("RELEASE_CONFIRMED", "UNKNOWN"),
        ).forEach { raw ->
            assertTrue(raw, Stage17P2M1MockJournal.restore(raw, pkg).blocked)
        }
    }

    @Test fun exitAttributionRequiresExactPidUidProcessAndTime() {
        val source = Stage17P2M1MockExitRecord(process, 123, 10101, 1_789_000_002_000L, 6)
        val verdict = Stage17P2M1MockExitCorrelator.classify(
            listOf(source), process, 123, 10101,
            1_789_000_000_000L, 1_789_000_005_000L,
        )
        assertEquals(Stage17P2M1MockExitCorrelator.Verdict.MATCHED, verdict)
        for (wrong in listOf(
            source.copy(pid = 234),
            source.copy(uid = 10102),
            source.copy(processName = "$pkg:model_validator"),
            source.copy(exitEpochMs = 1_788_000_000_000L),
        )) {
            assertEquals(
                Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN,
                Stage17P2M1MockExitCorrelator.classify(
                    listOf(wrong), process, 123, 10101,
                    1_789_000_000_000L, 1_789_000_005_000L,
                ),
            )
        }
    }

    @Test fun noPidOrDuplicateCandidateNeverClaimsExit() {
        val event = Stage17P2M1MockExitRecord(process, 123, 10101, 1_789_000_002_000L, 6)
        assertEquals(
            Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN,
            Stage17P2M1MockExitCorrelator.classify(
                listOf(event), process, 0, 10101, 1_789_000_000_000L, 1_789_000_005_000L,
            ),
        )
        assertEquals(
            Stage17P2M1MockExitCorrelator.Verdict.AMBIGUOUS,
            Stage17P2M1MockExitCorrelator.classify(
                listOf(event, event), process, 123, 10101,
                1_789_000_000_000L, 1_789_000_005_000L,
            ),
        )
    }

    @Test fun fakeAdmittingOwnerExcludesCompetingOwnerAndPoisonSticks() {
        val gate = Stage17P2M1MockAdmission()
        assertTrue(gate.acquire("fake-main"))
        assertFalse(gate.acquire("fake-child"))
        assertFalse(gate.release("fake-child", true))
        assertTrue(gate.release("fake-main", true))
        assertTrue(gate.acquire("fake-child"))
        assertFalse(gate.release("fake-child", false))
        assertTrue(gate.blocked())
        assertFalse(gate.acquire("fake-main"))
    }
}
