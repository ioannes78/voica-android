package io.github.ioannes78.voica

import org.junit.Assert.*
import org.junit.Test

class Stage17P2M4BContractTest {
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"
    private val appId = "io.github.ioannes78.voica.qa"

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
        assertFalse(Stage17P2M4BCodec.blocked(rec))
    }
}
