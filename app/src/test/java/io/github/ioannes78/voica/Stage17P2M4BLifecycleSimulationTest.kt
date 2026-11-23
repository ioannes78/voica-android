package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FAKE, in-memory JVM verification of durable snapshot ordering.
 * The hypothetical seed is test data, NOT a device enrollment mechanism.
 * Never exercises Android Binder, an actual SharedPreferences write, or a model.
 */
class Stage17P2M4BLifecycleSimulationTest {
    private val nonce1 = "123e4567-e89b-12d3-a456-426614174000"
    private val nonce2 = "123e4567-e89b-12d3-a456-426614174001"
    private val clean = Stage17P2M4BRecord(0L, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED)

    /** Simulates exact-persisted snapshot plus restart by decoding stored bytes. */
    private class FakeStore(initial: Stage17P2M4BRecord?) {
        var snapshot: Map<String, Any?> = if (initial == null) emptyMap() else encode(initial)
        var rejectNextCommit = false
        fun load(): Stage17P2M4BRecord? = Stage17P2M4BCodec.decodePersistedSnapshot(snapshot)
        fun commit(next: Stage17P2M4BRecord): Boolean {
            if (rejectNextCommit) {
                rejectNextCommit = false
                return false
            }
            snapshot = encode(next)
            return load() == next
        }
        companion object {
            fun encode(next: Stage17P2M4BRecord) = mapOf(
                Stage17P2M4BContract.KEY_SEEDED to true,
                Stage17P2M4BContract.KEY_RECORD to Stage17P2M4BCodec.encode(next),
            )
        }
    }

    private fun step(store: FakeStore, next: Stage17P2M4BPhase): Boolean {
        val current = store.load() ?: return false
        val transitioned = Stage17P2M4BCodec.next(
            current, current.generation, current.nonce, next,
        ) ?: return false
        return store.commit(transitioned)
    }

    @Test fun missingEvidenceDoesNotCreateAnOwnerOrPermitAdmission() {
        val store = FakeStore(null)
        assertTrue(Stage17P2M4BCodec.blocked(store.load()))
        assertNull(Stage17P2M4BCodec.claim(store.load(), nonce1))
        assertFalse(step(store, Stage17P2M4BPhase.PARENT_BOUND))
    }

    @Test fun simulatedNormalReleaseRequiresEveryDurableOrderedAck() {
        val store = FakeStore(clean)
        val intent = requireNotNull(Stage17P2M4BCodec.claim(store.load(), nonce1))
        assertTrue(store.commit(intent))
        assertTrue(Stage17P2M4BCodec.blocked(store.load()))
        val phases = listOf(
            Stage17P2M4BPhase.PARENT_BOUND,
            Stage17P2M4BPhase.CHILD_ADMITTED,
            Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
            Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
        )
        for (phase in phases) {
            assertTrue("missing $phase", step(store, phase))
            assertEquals(phase, store.load()?.phase)
            if (phase != Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE) {
                assertTrue(Stage17P2M4BCodec.blocked(store.load()))
            }
        }
        assertFalse(Stage17P2M4BCodec.blocked(store.load()))
        val historical = requireNotNull(store.load())
        assertEquals(1L, historical.generation)
        assertEquals(Stage17P2M4BPhase.INTENT_DURABLE,
            Stage17P2M4BCodec.claim(historical, nonce2)?.phase)
    }

    @Test fun simulatedCrashAtAnyUnreleasedPhaseDoesNotBecomeClean() {
        val phases = listOf(
            Stage17P2M4BPhase.INTENT_DURABLE,
            Stage17P2M4BPhase.PARENT_BOUND,
            Stage17P2M4BPhase.CHILD_ADMITTED,
            Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
            Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.UNRESOLVED_POISONED,
        )
        for (phase in phases) {
            val store = FakeStore(Stage17P2M4BRecord(1L, nonce1, phase))
            val reopened = store.load()
            assertTrue("$phase after restart", Stage17P2M4BCodec.blocked(reopened))
            assertNull("$phase must deny second claim",
                Stage17P2M4BCodec.claim(reopened, nonce2))
            assertFalse(Stage17P2M4BCodec.recoveryVerdict(reopened) == "VERIFIED_NEVER_ARMED")
        }
    }

    @Test fun missingAckAndAmbiguousCommitNeverConfirmRelease() {
        val store = FakeStore(Stage17P2M4BRecord(
            1L, nonce1, Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
        ))
        assertFalse(step(store, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE))
        assertTrue(step(store, Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED))
        store.rejectNextCommit = true
        assertFalse(step(store, Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED))
        assertTrue(Stage17P2M4BCodec.blocked(store.load()))
        assertFalse(step(store, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE))
    }

    @Test fun corruptedSnapshotAndStaleAttemptCannotClaimOrAdvance() {
        val store = FakeStore(clean)
        store.snapshot = store.snapshot + ("unexpected" to true)
        assertNull(store.load())
        assertNull(Stage17P2M4BCodec.claim(store.load(), nonce1))
        store.snapshot = FakeStore.encode(
            Stage17P2M4BRecord(1L, nonce1, Stage17P2M4BPhase.INTENT_DURABLE),
        )
        val active = requireNotNull(store.load())
        assertNull(Stage17P2M4BCodec.next(active, 2L, nonce1, Stage17P2M4BPhase.PARENT_BOUND))
        assertNull(Stage17P2M4BCodec.next(active, 1L, nonce2, Stage17P2M4BPhase.PARENT_BOUND))
        assertTrue(Stage17P2M4BCodec.blocked(store.load()))
    }
}
