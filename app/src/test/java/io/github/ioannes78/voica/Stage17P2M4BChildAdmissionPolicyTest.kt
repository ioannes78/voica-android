package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17P2M4BChildAdmissionPolicyTest {
    private val lab = Stage17P2M4BContract.LAB_PACKAGE
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    private fun accepts(
        app: String = lab,
        process: String? = Stage17P2M4BContract.childProcess(app),
        actualUid: Int = 10345,
        senderUid: Int = actualUid,
        enabled: Boolean = true,
        alreadyAdmitted: Boolean = false,
        generation: Long = 1L,
        nonce: String? = uuid,
        model: String? = Stage17P2M4BContract.FAKE_MODEL,
        claimedUid: Int = actualUid,
        claimedParent: String? = Stage17P2M4BContract.parentProcess(app),
        hasReply: Boolean = true,
    ): Boolean = Stage17P2M4BChildAdmissionPolicy.accepts(
        app, process, actualUid, senderUid, enabled, alreadyAdmitted,
        generation, nonce, model, claimedUid, claimedParent, hasReply,
    )

    @Test fun hypotheticalAllGatesPassButRealB1IsDisabled() {
        assertTrue(accepts())
        assertFalse(accepts(enabled = Stage17P2M4BContract.IPC_ADMISSION_ENABLED))
    }

    @Test fun wrongAppOrProcessCannotAdmit() {
        assertFalse(accepts(app = "io.github.ioannes78.voica.qa"))
        assertFalse(accepts(app = "io.github.ioannes78.voica"))
        assertFalse(accepts(app = "$lab.forged"))
        assertFalse(accepts(process = lab))
        assertFalse(accepts(process = Stage17P2M4BContract.parentProcess(lab)))
        assertFalse(accepts(process = null))
    }

    @Test fun invalidOrReplayedMessageCannotAdmit() {
        assertFalse(accepts(enabled = false))
        assertFalse(accepts(alreadyAdmitted = true))
        assertFalse(accepts(generation = 0L))
        assertFalse(accepts(nonce = "invalid"))
        assertFalse(accepts(nonce = null))
        assertFalse(accepts(model = "REAL_MODEL"))
        assertFalse(accepts(actualUid = 0, senderUid = 0, claimedUid = 0))
        assertFalse(accepts(senderUid = 11))
        assertFalse(accepts(claimedUid = 11))
        assertFalse(accepts(claimedParent = Stage17P2M4BContract.childProcess(lab)))
        assertFalse(accepts(hasReply = false))
    }
}
