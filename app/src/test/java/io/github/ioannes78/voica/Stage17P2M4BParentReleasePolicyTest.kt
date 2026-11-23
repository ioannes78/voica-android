package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17P2M4BParentReleasePolicyTest {
    private val lab = Stage17P2M4BContract.LAB_PACKAGE
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"

    private fun childAck(
        app: String = lab,
        armed: Boolean = true,
        childReady: Boolean = true,
        terminal: Boolean = true,
        childReleaseSent: Boolean = true,
        accepted: Boolean = false,
        actualUid: Int = 12345,
        senderUid: Int = actualUid,
        generation: Long = 3L,
        requestGeneration: Long = generation,
        requestNonce: String? = nonce,
        model: String? = Stage17P2M4BContract.FAKE_MODEL,
        childProcess: String? = Stage17P2M4BContract.childProcess(app),
        claimedUid: Int = actualUid,
    ) = Stage17P2M4BParentReleasePolicy.acceptsChildReleaseAck(
        app, armed, childReady, terminal, childReleaseSent, accepted,
        actualUid, senderUid, generation, nonce, requestGeneration, requestNonce,
        model, childProcess, claimedUid,
    )

    @Test fun validFakeReleaseNeedsEveryOrderedPrecondition() {
        assertTrue(Stage17P2M4BParentReleasePolicy.mayObserveTerminal(true, true, false, false))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayObserveTerminal(true, false, false, false))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayObserveTerminal(true, true, true, false))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayObserveTerminal(false, true, false, false))

        assertTrue(Stage17P2M4BParentReleasePolicy.mayRequestChildRelease(true, true, true, false, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayRequestChildRelease(true, false, true, false, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayRequestChildRelease(true, true, true, true, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayRequestChildRelease(true, true, true, false, false))

        assertTrue(childAck())
        assertTrue(Stage17P2M4BParentReleasePolicy.mayReleaseParent(true, true, false, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayReleaseParent(true, false, false, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayReleaseParent(true, true, true, true))
        assertFalse(Stage17P2M4BParentReleasePolicy.mayReleaseParent(true, true, false, false))
    }

    @Test fun forgedOrReplayedChildReleaseAckIsDenied() {
        assertFalse(childAck(app = "io.github.ioannes78.voica.qa"))
        assertFalse(childAck(app = "io.github.ioannes78.voica"))
        assertFalse(childAck(armed = false))
        assertFalse(childAck(childReady = false))
        assertFalse(childAck(terminal = false))
        assertFalse(childAck(childReleaseSent = false))
        assertFalse(childAck(accepted = true))
        assertFalse(childAck(actualUid = 0, senderUid = 0, claimedUid = 0))
        assertFalse(childAck(senderUid = 55))
        assertFalse(childAck(claimedUid = 55))
        assertFalse(childAck(generation = 0L, requestGeneration = 0L))
        assertFalse(childAck(requestGeneration = 999L))
        assertFalse(childAck(requestNonce = null))
        assertFalse(childAck(requestNonce = "123e4567-e89b-12d3-a456-426614174001"))
        assertFalse(childAck(model = "REAL_MODEL"))
        assertFalse(childAck(childProcess = Stage17P2M4BContract.parentProcess(lab)))
    }
}
