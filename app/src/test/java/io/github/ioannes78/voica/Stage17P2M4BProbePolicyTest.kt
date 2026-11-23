package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17P2M4BProbePolicyTest {
    private val lab = Stage17P2M4BContract.LAB_PACKAGE
    private val token = "123e4567-e89b-12d3-a456-426614174000"

    private fun ping(
        app: String = lab, process: String? = Stage17P2M4BContract.parentProcess(app),
        actualUid: Int = 10033, sendingUid: Int = 10033,
        caller: String? = app, nonce: String? = token,
        model: String? = Stage17P2M4BContract.FAKE_MODEL, armed: Boolean = false,
    ) = Stage17P2M4BProbePolicy.acceptsPing(
        app, process, actualUid, sendingUid, caller, nonce, model, armed,
    )

    private fun pong(
        app: String = lab, process: String? = app, actualUid: Int = 10033,
        sendingUid: Int = 10033, reportedUid: Int = 10033,
        reportedProcess: String? = Stage17P2M4BContract.parentProcess(app),
        nonce: String? = token, expectedToken: String = token,
        model: String? = Stage17P2M4BContract.FAKE_MODEL,
    ) = Stage17P2M4BProbePolicy.acceptsPong(
        app, process, actualUid, sendingUid, reportedUid, reportedProcess,
        nonce, expectedToken, model,
    )

    @Test fun isolatedLabFakePingPongIsPossibleWithoutOwnerAdmission() {
        assertTrue(ping())
        assertTrue(pong())
        assertFalse(Stage17P2M4BContract.IPC_ADMISSION_ENABLED)
        assertFalse(Stage17P2M4BContract.FAULT_INJECTION_ENABLED)
    }

    @Test fun pingDeniedOnWrongIdentityOrParentAlreadyArmed() {
        assertFalse(ping(app = "io.github.ioannes78.voica.qa"))
        assertFalse(ping(app = "io.github.ioannes78.voica"))
        assertFalse(ping(process = lab))
        assertFalse(ping(sendingUid = 99))
        assertFalse(ping(actualUid = 0, sendingUid = 0))
        assertFalse(ping(caller = "io.github.ioannes78.voica.qa"))
        assertFalse(ping(nonce = "not-uuid"))
        assertFalse(ping(nonce = null))
        assertFalse(ping(model = "REAL_MODEL"))
        assertFalse(ping(armed = true))
    }

    @Test fun pongDeniedOnReplayMismatchOrWrongBinderIdentity() {
        assertFalse(pong(app = "io.github.ioannes78.voica.qa"))
        assertFalse(pong(process = Stage17P2M4BContract.parentProcess(lab)))
        assertFalse(pong(sendingUid = 10034))
        assertFalse(pong(reportedUid = 10034))
        assertFalse(pong(reportedProcess = lab))
        assertFalse(pong(nonce = null))
        assertFalse(pong(nonce = "123e4567-e89b-12d3-a456-426614174001"))
        assertFalse(pong(expectedToken = "wrong"))
        assertFalse(pong(model = "REAL_MODEL"))
    }
}
