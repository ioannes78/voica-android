package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17P2M4BChildReleasePolicyTest {
    private val lab = Stage17P2M4BContract.LAB_PACKAGE
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"
    private fun accepted(
        app: String = lab,
        process: String? = Stage17P2M4BContract.childProcess(app),
        actualUid: Int = 10001,
        senderUid: Int = actualUid,
        enabled: Boolean = true,
        admitted: Boolean = true,
        released: Boolean = false,
        generation: Long = 1L,
        originalNonce: String = nonce,
        requestGeneration: Long = generation,
        requestNonce: String? = originalNonce,
        requestModel: String? = Stage17P2M4BContract.FAKE_MODEL,
        requestParent: String? = Stage17P2M4BContract.parentProcess(app),
        requestUid: Int = actualUid,
        hasReply: Boolean = true,
    ) = Stage17P2M4BChildReleasePolicy.accepts(
        app, process, actualUid, senderUid, enabled, admitted, released,
        generation, originalNonce, requestGeneration, requestNonce,
        requestModel, requestParent, requestUid, hasReply,
    )

    @Test fun onlyCounterfactualValidFakeMessageCouldBeAccepted() {
        assertTrue(accepted())
        assertFalse(accepted(enabled = Stage17P2M4BContract.IPC_ADMISSION_ENABLED))
    }

    @Test fun wrongPackageOrProcessNeverReleases() {
        assertFalse(accepted(app = "io.github.ioannes78.voica.qa"))
        assertFalse(accepted(app = "io.github.ioannes78.voica"))
        assertFalse(accepted(process = lab))
        assertFalse(accepted(process = Stage17P2M4BContract.parentProcess(lab)))
        assertFalse(accepted(process = null))
    }

    @Test fun missingOrRepeatedReleaseCannotAcknowledge() {
        assertFalse(accepted(admitted = false))
        assertFalse(accepted(released = true))
        assertFalse(accepted(enabled = false))
        assertFalse(accepted(generation = 0L))
        assertFalse(accepted(requestGeneration = 2L))
        assertFalse(accepted(requestNonce = "123e4567-e89b-12d3-a456-426614174001"))
        assertFalse(accepted(requestNonce = null))
        assertFalse(accepted(requestModel = "REAL_MODEL"))
        assertFalse(accepted(requestParent = Stage17P2M4BContract.childProcess(lab)))
        assertFalse(accepted(requestUid = 10002))
        assertFalse(accepted(senderUid = 10002))
        assertFalse(accepted(actualUid = 0, senderUid = 0, requestUid = 0))
        assertFalse(accepted(hasReply = false))
    }
}
