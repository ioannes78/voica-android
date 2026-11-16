package io.github.ioannes78.voica

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-M4A JVM-ONLY failure matrix. These are fake logical "processes":
 * no physical OS interprocess lock, Binder, Engine, model file, JNI or GPU.
 */
class Stage17P2M4AFakeOwnershipPolicyTest {
    private val model = Stage17P2M4AFakeModelPin(
        modelId = "gemma-test-mock",
        version = "v1",
        revision = 8,
        sha256 = "a".repeat(64),
    )
    private val alternate = model.copy(revision = 9, sha256 = "b".repeat(64))
    private fun nonce(index: Int): String = UUID.nameUUIDFromBytes(
        "p2m4a-attempt-$index".toByteArray(StandardCharsets.UTF_8),
    ).toString()
    private fun fresh() = Stage17P2M4AFakeJournal.verifiedNeverArmedTestFixture()
    private fun arbiter(journal: Stage17P2M4AFakeJournal) =
        Stage17P2M4AFakeArbiter(journal)

    @Test fun parentDeathBeforeDurableArmNeverAdmits() {
        val store = fresh()
        store.failNextCommit()
        val a = arbiter(store)
        val attempted = a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1))
        assertFalse(attempted.allowed)
        assertEquals("DURABLE_ARM_FAILED", attempted.reason)
        assertTrue(a.nativeAdmissionBlocked())
        assertFalse(arbiter(store).claim(
            Stage17P2M4AFakeOwner.PRODUCT_CPU, model, nonce(2),
        ).allowed)
    }

    @Test fun parentDeathAfterArmBeforeBindStillBlocksNewOwner() {
        val store = fresh()
        val parent = arbiter(store)
        assertTrue(parent.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        val restartedParent = arbiter(store)
        assertTrue(restartedParent.nativeAdmissionBlocked())
        assertEquals(Stage17P2M4AFakePhase.INTENT_DURABLE, restartedParent.snapshot()?.phase)
        assertFalse(restartedParent.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(2)).allowed)
    }

    @Test fun parentDeathAfterChildAdmissionStillBlocksDespiteSimulatedFreeOsLock() {
        val store = fresh()
        val first = arbiter(store)
        assertTrue(first.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        assertTrue(first.childAdmission(1, nonce(1), model).allowed)
        val osLifetimeLockBecameFreeOnDeath = true // Conceptual: NOT an OS FileLock.
        assertTrue(osLifetimeLockBecameFreeOnDeath)
        val restarted = arbiter(store)
        assertEquals(Stage17P2M4AFakePhase.CHILD_ADMITTED, restarted.snapshot()?.phase)
        assertTrue(restarted.nativeAdmissionBlocked())
        assertFalse(restarted.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(2)).allowed)
    }

    @Test fun parentDiesAfterTerminalOrChildAckBeforeDurableRelease() {
        for (phase in listOf("TERMINAL", "RELEASE_ACK")) {
            val store = fresh()
            val a = arbiter(store)
            assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
            assertTrue(a.childAdmission(1, nonce(1), model).allowed)
            assertTrue(a.childNativeEntry(1, nonce(1)).allowed)
            assertTrue(a.terminal(1, nonce(1)).allowed)
            if (phase == "RELEASE_ACK") {
                assertTrue(a.childReleaseAck(1, nonce(1)).allowed)
            }
            val revived = arbiter(store)
            assertTrue(revived.nativeAdmissionBlocked())
            assertFalse(revived.claim(Stage17P2M4AFakeOwner.PRODUCT_CPU, model, nonce(2)).allowed)
            assertFalse(revived.modelMutation(
                model.modelId, Stage17P2M4AFakeMutation.REMOVE_VERSION,
            ).allowed)
        }
    }

    @Test fun twoCompetingFakeClientsAdmitOnlyOneOwner() {
        val store = fresh()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val responses = (1..40).map { i ->
                pool.submit<Boolean> {
                    start.await()
                    arbiter(store).claim(
                        if (i % 2 == 0) Stage17P2M4AFakeOwner.PRODUCT_CPU
                        else Stage17P2M4AFakeOwner.QA_CHILD,
                        model, nonce(i),
                    ).allowed
                }
            }
            start.countDown()
            assertEquals(1, responses.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1L, arbiter(store).snapshot()?.generation)
            assertTrue(arbiter(store).nativeAdmissionBlocked())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun fakeProductCpuConflictsWithFakeChildAndViceVersa() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.PRODUCT_CPU, model, nonce(1)).allowed)
        assertTrue(a.productCpuEntry(1, nonce(1)).allowed)
        assertFalse(arbiter(store).claim(
            Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(2),
        ).allowed)
        assertFalse(a.childAdmission(1, nonce(1), model).allowed)
        assertTrue(a.terminal(1, nonce(1)).allowed)
        assertTrue(a.childReleaseAck(1, nonce(1)).allowed)
        assertTrue(a.parentCommitRelease(1, nonce(1)).allowed)
        assertTrue(arbiter(store).claim(
            Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(2),
        ).allowed)
        assertEquals(2L, arbiter(store).snapshot()?.generation)
    }

    @Test fun activeAndPoisonedFakeOwnerBlockAllReferencedModelMutations() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        val blocked = listOf(
            Stage17P2M4AFakeMutation.REMOVE_VERSION,
            Stage17P2M4AFakeMutation.ROLLBACK,
            Stage17P2M4AFakeMutation.ACTIVATE,
            Stage17P2M4AFakeMutation.REPLACE,
            Stage17P2M4AFakeMutation.CLEAN_REFERENCED,
        )
        for (mutation in blocked) {
            assertFalse(mutation.name, a.modelMutation(model.modelId, mutation).allowed)
        }
        assertTrue(a.modelMutation(
            model.modelId, Stage17P2M4AFakeMutation.IMMUTABLE_STAGING,
        ).allowed)
        assertTrue(a.modelMutation(
            "unrelated-model", Stage17P2M4AFakeMutation.REMOVE_VERSION,
        ).allowed)

        assertTrue(a.releaseUnconfirmed(1, nonce(1)).allowed)
        assertEquals(Stage17P2M4AFakePhase.UNRESOLVED_POISONED, a.snapshot()?.phase)
        for (mutation in blocked) {
            assertFalse(mutation.name, arbiter(store).modelMutation(model.modelId, mutation).allowed)
        }
    }

    @Test fun legacyModelLeaseCloseCannotReleasePoisonedModelBytes() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.PRODUCT_CPU, model, nonce(1)).allowed)
        assertTrue(a.productCpuEntry(1, nonce(1)).allowed)
        assertTrue(a.releaseUnconfirmed(1, nonce(1)).allowed)
        assertTrue(a.legacyLocalLeaseClosed().allowed)
        assertFalse(arbiter(store).modelMutation(
            model.modelId, Stage17P2M4AFakeMutation.REMOVE_VERSION,
        ).allowed)
        assertTrue(arbiter(store).nativeAdmissionBlocked())
    }

    @Test fun modelPinChangeWhileChildBindsRejectsHandoffWithoutStateChange() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        assertEquals("MODEL_PIN_MISMATCH", a.childAdmission(
            1, nonce(1), alternate,
        ).reason)
        assertFalse(a.childAdmission(1, nonce(1), alternate).allowed)
        assertEquals(Stage17P2M4AFakePhase.INTENT_DURABLE, a.snapshot()?.phase)
        assertTrue(a.nativeAdmissionBlocked())
    }

    @Test fun staleNonceAndGenerationCanNeverReplayChildOrTerminalOrRelease() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        val before = store.readRaw()
        assertFalse(a.childAdmission(1, nonce(2), model).allowed)
        assertFalse(a.childAdmission(2, nonce(1), model).allowed)
        assertFalse(a.childNativeEntry(1, nonce(1)).allowed) // not admitted
        assertFalse(a.terminal(1, nonce(1)).allowed) // not native-active
        assertFalse(a.childReleaseAck(1, nonce(1)).allowed)
        assertFalse(a.parentCommitRelease(1, nonce(1)).allowed)
        assertEquals(before, store.readRaw())
        assertTrue(a.childAdmission(1, nonce(1), model).allowed)
        assertFalse(a.childAdmission(1, nonce(1), model).allowed) // replay
    }

    @Test fun missingCorruptTruncatedAndWrongSchemaJournalFailClosed() {
        val corruptions = listOf<String?>(
            null, "", "P2M4AV1|0", "UNKNOWN|0|VERIFIED_NEVER_ARMED",
            "P2M4AV1|1|CHILD_ADMITTED|QA_CHILD|nonce|model|v1|1|bad",
            Stage17P2M4AFakeCodec.encode(
                Stage17P2M4AFakeCodec.cleanRecord(),
            ) + "|extra",
        )
        for (raw in corruptions) {
            val store = Stage17P2M4AFakeJournal.untrustedTestFixture(raw)
            val a = arbiter(store)
            assertNull(a.snapshot())
            assertTrue(a.nativeAdmissionBlocked())
            assertFalse(a.claim(
                Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1),
            ).allowed)
            assertFalse(a.modelMutation(
                model.modelId, Stage17P2M4AFakeMutation.REMOVE_VERSION,
            ).allowed)
        }
        val seeded = fresh()
        seeded.simulateCorruptedStorage(null)
        assertTrue(arbiter(seeded).nativeAdmissionBlocked())
    }

    @Test fun finalDurableCommitFailureCannotBeMistakenForNativeRelease() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        assertTrue(a.childAdmission(1, nonce(1), model).allowed)
        assertTrue(a.childNativeEntry(1, nonce(1)).allowed)
        assertTrue(a.terminal(1, nonce(1)).allowed)
        assertTrue(a.childReleaseAck(1, nonce(1)).allowed)
        store.failNextCommit()
        assertFalse(a.parentCommitRelease(1, nonce(1)).allowed)
        assertTrue(arbiter(store).nativeAdmissionBlocked())
        assertFalse(arbiter(store).modelMutation(
            model.modelId, Stage17P2M4AFakeMutation.REMOVE_VERSION,
        ).allowed)
    }

    @Test fun completeFakeReleaseOnlyUnlocksFutureFakeArbitrationNotGpu() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        assertFalse(a.parentCommitRelease(1, nonce(1)).allowed)
        assertTrue(a.childAdmission(1, nonce(1), model).allowed)
        assertTrue(a.childNativeEntry(1, nonce(1)).allowed)
        assertTrue(a.terminal(1, nonce(1)).allowed)
        assertTrue(a.childReleaseAck(1, nonce(1)).allowed)
        assertTrue(a.parentCommitRelease(1, nonce(1)).allowed)
        assertEquals(
            Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE,
            arbiter(store).snapshot()?.phase,
        )
        assertFalse(a.nativeAdmissionBlocked())
        assertTrue(a.modelMutation(
            model.modelId, Stage17P2M4AFakeMutation.REMOVE_VERSION,
        ).allowed)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(2)).allowed)
        assertEquals(2L, a.snapshot()?.generation)
    }

    @Test fun journalRemainsContentFreeAndHistoricalExitCannotClearIt() {
        val store = fresh()
        val a = arbiter(store)
        assertTrue(a.claim(Stage17P2M4AFakeOwner.QA_CHILD, model, nonce(1)).allowed)
        val raw = store.readRaw().orEmpty()
        assertFalse(raw.contains("prompt"))
        assertFalse(raw.contains("transcript"))
        assertFalse(raw.contains("/storage/"))
        assertFalse(raw.contains("generated-text"))
        val expectedProcess = "io.github.ioannes78.voica.qa:stage17_mock_probe"
        val exit = Stage17P2M1MockExitRecord(
            expectedProcess, 2020, 10001, 1_789_000_005_000L, 9,
        )
        val wrongPid = Stage17P2M1MockExitCorrelator.classify(
            listOf(exit), expectedProcess, 3030, 10001,
            1_789_000_000_000L, 1_789_000_010_000L,
        )
        assertEquals(Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN, wrongPid)
        assertTrue(a.nativeAdmissionBlocked())
        assertEquals(raw, store.readRaw()) // OS metadata never mutates journal.
        assertNotNull(a.snapshot())
    }
}
