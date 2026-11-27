package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17P2M4BChildAckPolicyTest {
    private val app = Stage17P2M4BContract.LAB_PACKAGE
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"

    private fun verify(
        packageName: String = app,
        armed: Boolean = true,
        admissionSent: Boolean = true,
        alreadyAccepted: Boolean = false,
        actualUid: Int = 10001,
        senderUid: Int = 10001,
        generation: Long = 7L,
        suppliedGeneration: Long = 7L,
        suppliedNonce: String? = nonce,
        suppliedModel: String? = Stage17P2M4BContract.FAKE_MODEL,
        suppliedProcess: String? = Stage17P2M4BContract.childProcess(app),
        suppliedUid: Int = 10001,
    ): Boolean = Stage17P2M4BChildAckPolicy.accepts(
        packageName, armed, admissionSent, alreadyAccepted, actualUid,
        senderUid, generation, nonce, suppliedGeneration, suppliedNonce,
        suppliedModel, suppliedProcess, suppliedUid,
    )

    @Test fun exactOneShotFakeChildReadyPasses() {
        assertTrue(verify())
    }

    @Test fun staleDuplicateAndUnarmedAreDenied() {
        assertFalse(verify(armed = false))
        assertFalse(verify(admissionSent = false))
        assertFalse(verify(alreadyAccepted = true))
        assertFalse(verify(suppliedGeneration = 6L))
        assertFalse(verify(suppliedNonce = "123e4567-e89b-12d3-a456-426614174001"))
        assertFalse(verify(suppliedNonce = null))
    }

    @Test fun spoofedUidPackageProcessOrModelIsDenied() {
        assertFalse(verify(senderUid = 10002))
        assertFalse(verify(suppliedUid = 10002))
        assertFalse(verify(actualUid = 0, senderUid = 0, suppliedUid = 0))
        assertFalse(verify(packageName = "io.github.ioannes78.voica"))
        assertFalse(verify(suppliedProcess = Stage17P2M4BContract.childProcess(
            "io.github.ioannes78.voica.qa")))
        assertFalse(verify(suppliedModel = "REAL_LLM"))
        assertFalse(verify(suppliedModel = null))
    }
}
