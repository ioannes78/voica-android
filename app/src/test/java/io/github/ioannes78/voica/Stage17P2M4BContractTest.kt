package io.github.ioannes78.voica

import org.junit.Assert.*
import org.junit.Test

class Stage17P2M4BContractTest {
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"
    private val appId = "io.github.ioannes78.voica.qa"

    @Test fun isolatedLabPackageHasSeparateProcessIdentityWithoutAdmittingProduction() {
        val lab = "io.github.ioannes78.voica.m4blab"
        assertTrue(Stage17P2M4BContract.allowPackage(lab))
        assertTrue(Stage17P2M4BContract.allowPackage(appId))
        assertFalse(Stage17P2M4BContract.allowPackage("io.github.ioannes78.voica"))
        assertFalse(Stage17P2M4BContract.allowPackage("io.github.ioannes78.voica.m4blab.fake"))
        assertTrue(Stage17P2M4BContract.validIdentity(
            lab, "$lab:stage17_m4b_parent", Stage17P2M4BContract.PARENT_SUFFIX,
            1L, nonce, Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertFalse(Stage17P2M4BContract.validIdentity(
            lab, "$appId:stage17_m4b_parent", Stage17P2M4BContract.PARENT_SUFFIX,
            1L, nonce, Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertTrue(stage17P2LShouldSkipHeavyStartup(
            lab, "$lab:stage17_m4b_parent",
        ))
        assertTrue(stage17P2LShouldSkipHeavyStartup(
            lab, "$lab:stage17_m4b_child",
        ))
        assertTrue(stage17P2LShouldSkipHeavyStartup(lab, lab))
        assertFalse(stage17P2LShouldSkipHeavyStartup(appId, appId))
        assertFalse(stage17P2LShouldSkipHeavyStartup("io.github.ioannes78.voica", "io.github.ioannes78.voica"))
        assertFalse(Stage17P2M4BContract.IPC_ADMISSION_ENABLED)
        assertFalse(Stage17P2M4BContract.FAULT_INJECTION_ENABLED)
    }

    @Test fun exactPackageAndProcessesOnly() {
        assertTrue(Stage17P2M4BContract.validIdentity(
            appId, "$appId:stage17_m4b_parent",
            Stage17P2M4BContract.PARENT_SUFFIX, 1L, nonce,
            Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertFalse(Stage17P2M4BContract.validIdentity(
            "io.github.ioannes78.voica", "io.github.ioannes78.voica:stage17_m4b_parent",
            Stage17P2M4BContract.PARENT_SUFFIX, 1L, nonce,
            Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertFalse(Stage17P2M4BContract.validIdentity(
            appId, appId, Stage17P2M4BContract.PARENT_SUFFIX,
            1L, nonce, Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertFalse(Stage17P2M4BContract.validIdentity(
            appId, "$appId:stage17_m4b_parent", Stage17P2M4BContract.PARENT_SUFFIX,
            1L, nonce, Stage17P2M4BContract.FAKE_MODEL, 10001, 10000,
        ))
    }

    @Test fun noExitPermissionUnderAnyInput() {
        assertFalse(Stage17P2M4BContract.allowParentSelfExit(
            appId, "$appId:stage17_m4b_parent", true,
        ))
        assertFalse(Stage17P2M4BContract.allowParentSelfExit(
            appId, appId, true,
        ))
    }

    @Test fun nonFakeModelAndInvalidGenerationAlwaysDenied() {
        assertFalse(Stage17P2M4BContract.validIdentity(
            appId, "$appId:stage17_m4b_child",
            Stage17P2M4BContract.CHILD_SUFFIX, 0L, nonce,
            Stage17P2M4BContract.FAKE_MODEL, 10000, 10000,
        ))
        assertFalse(Stage17P2M4BContract.validIdentity(
            appId, "$appId:stage17_m4b_child",
            Stage17P2M4BContract.CHILD_SUFFIX, 1L, nonce,
            "REAL_GEMMA_MODEL", 10000, 10000,
        ))
    }

    @Test fun missingOrCorruptJournalNeverMeansClean() {
        assertNull(Stage17P2M4BCodec.decode(null))
        assertNull(Stage17P2M4BCodec.decode("bad"))
        assertTrue(Stage17P2M4BCodec.blocked(null))
        assertNull(Stage17P2M4BCodec.claim(null, nonce))
    }

    @Test fun durableIntentStickyAfterRestartAndCompetingClaims() {
        val seed = Stage17P2M4BRecord(0, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED)
        val first = requireNotNull(Stage17P2M4BCodec.claim(seed, nonce))
        assertEquals(1L, first.generation)
        assertEquals(first, Stage17P2M4BCodec.decode(Stage17P2M4BCodec.encode(first)))
        assertTrue(Stage17P2M4BCodec.blocked(first))
        assertNull(Stage17P2M4BCodec.claim(first, "123e4567-e89b-12d3-a456-426614174001"))
    }

    @Test fun staleAndOutOfOrderCallbacksDenied() {
        val intent = requireNotNull(Stage17P2M4BCodec.claim(
            Stage17P2M4BRecord(0, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED), nonce,
        ))
        assertNull(Stage17P2M4BCodec.next(intent, 2L, nonce, Stage17P2M4BPhase.PARENT_BOUND))
        assertNull(Stage17P2M4BCodec.next(intent, 1L, "wrong", Stage17P2M4BPhase.PARENT_BOUND))
        assertNull(Stage17P2M4BCodec.next(intent, 1L, nonce, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE))
        val bound = requireNotNull(Stage17P2M4BCodec.next(intent, 1L, nonce, Stage17P2M4BPhase.PARENT_BOUND))
        assertNotNull(Stage17P2M4BCodec.next(bound, 1L, nonce, Stage17P2M4BPhase.CHILD_ADMITTED))
    }

    @Test fun deathPoisonsAndNeverReleases() {
        val intent = requireNotNull(Stage17P2M4BCodec.claim(
            Stage17P2M4BRecord(0, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED), nonce,
        ))
        val poisoned = requireNotNull(Stage17P2M4BCodec.next(
            intent, 1L, nonce, Stage17P2M4BPhase.UNRESOLVED_POISONED,
        ))
        assertTrue(Stage17P2M4BCodec.blocked(Stage17P2M4BCodec.decode(
            Stage17P2M4BCodec.encode(poisoned),
        )))
        assertNull(Stage17P2M4BCodec.next(poisoned, 1L, nonce,
            Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE))
        assertNull(Stage17P2M4BCodec.claim(poisoned, "123e4567-e89b-12d3-a456-426614174001"))
    }

    @Test fun completeReleaseNeedsBothAcksInOrder() {
        var rec = requireNotNull(Stage17P2M4BCodec.claim(
            Stage17P2M4BRecord(0, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED), nonce,
        ))
        val steps = listOf(
            Stage17P2M4BPhase.PARENT_BOUND,
            Stage17P2M4BPhase.CHILD_ADMITTED,
            Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
            Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
        )
        for (next in steps) {
            rec = requireNotNull(Stage17P2M4BCodec.next(rec, 1L, nonce, next))
        }
        assertTrue(Stage17P2M4BCodec.blocked(rec))
        assertNull(Stage17P2M4BCodec.claim(
            rec, "123e4567-e89b-12d3-a456-426614174001",
        ))
    }

    @Test fun reopenNeverTreatsInterruptedOwnerAsClean() {
        assertEquals("DENY_UNTRUSTED_JOURNAL", Stage17P2M4BCodec.recoveryVerdict(null))
        val clean = Stage17P2M4BRecord(0, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED)
        assertEquals("VERIFIED_NEVER_ARMED", Stage17P2M4BCodec.recoveryVerdict(clean))
        val intent = requireNotNull(Stage17P2M4BCodec.claim(clean, nonce))
        for (phase in listOf(
            Stage17P2M4BPhase.INTENT_DURABLE, Stage17P2M4BPhase.PARENT_BOUND,
            Stage17P2M4BPhase.CHILD_ADMITTED, Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
        )) {
            assertEquals("DENY_INTERRUPTED_FAKE_OWNER",
                Stage17P2M4BCodec.recoveryVerdict(intent.copy(phase = phase)))
        }
        assertEquals("STICKY_M4B_POISON", Stage17P2M4BCodec.recoveryVerdict(
            intent.copy(phase = Stage17P2M4BPhase.UNRESOLVED_POISONED)))
        assertEquals("LAST_FAKE_RELEASE_DURABLE", Stage17P2M4BCodec.recoveryVerdict(
            intent.copy(phase = Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE)))
    }

    @Test fun terminalWithoutChildAndParentAckCannotRelease() {
        val terminal = Stage17P2M4BRecord(7L, nonce, Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED)
        assertNull(Stage17P2M4BCodec.next(
            terminal, 7L, nonce, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE))
        assertNull(Stage17P2M4BCodec.next(
            terminal, 7L, nonce, Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED))
        assertTrue(Stage17P2M4BCodec.blocked(terminal))
    }

    @Test fun journalSnapshotRequiresExactTypedEvidence() {
        val clean = Stage17P2M4BRecord(
            0L, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED,
        )
        val correct = mapOf(
            Stage17P2M4BContract.KEY_SEEDED to true,
            Stage17P2M4BContract.KEY_RECORD to Stage17P2M4BCodec.encode(clean),
        )
        assertEquals(clean, Stage17P2M4BCodec.decodePersistedSnapshot(correct))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(null))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(emptyMap<String, Any?>()))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(
            mapOf(Stage17P2M4BContract.KEY_RECORD to Stage17P2M4BCodec.encode(clean))))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(
            correct + (Stage17P2M4BContract.KEY_SEEDED to "true")))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(
            correct + ("unexpected" to "forged")))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(
            correct + (Stage17P2M4BContract.KEY_RECORD to 42)))
        assertNull(Stage17P2M4BCodec.decodePersistedSnapshot(
            correct + (Stage17P2M4BContract.KEY_RECORD to "corrupt")))
    }


}
