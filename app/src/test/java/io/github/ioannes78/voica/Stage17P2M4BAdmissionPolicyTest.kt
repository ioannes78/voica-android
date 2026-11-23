package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests policy counterfactuals; the actual B1 controller always grants false. */
class Stage17P2M4BAdmissionPolicyTest {
    private val lab = Stage17P2M4BContract.LAB_PACKAGE
    private val historicalQa = "io.github.ioannes78.voica.qa"
    private val neverArmed = Stage17P2M4BRecord(
        0L, "", Stage17P2M4BPhase.VERIFIED_NEVER_ARMED,
    )
    private val nonce = "123e4567-e89b-12d3-a456-426614174000"

    private fun eligible(
        appId: String = lab,
        processName: String? = appId,
        ipcAdmissionEnabled: Boolean = true,
        trustedEnrollment: Boolean = true,
        record: Stage17P2M4BRecord? = neverArmed,
    ): Boolean = Stage17P2M4BAdmissionPolicy.canAdmit(
        appId, processName, ipcAdmissionEnabled, trustedEnrollment, record,
    )

    @Test fun onlyHypotheticalExternallyTrustedLabCouldBecomeEligible() {
        // Not an actual authorization: the B1 broker hardcodes trustedEnrollment=false.
        assertTrue(eligible())
        assertFalse(eligible(trustedEnrollment = false))
        assertFalse(eligible(ipcAdmissionEnabled = false))
        assertFalse(Stage17P2M4BContract.IPC_ADMISSION_ENABLED)
        assertFalse(Stage17P2M4BContract.FAULT_INJECTION_ENABLED)
    }

    @Test fun historicalQaAndProductionNeverAdmitEvenWithAllOtherFlagsTrue() {
        assertFalse(eligible(appId = historicalQa))
        assertFalse(eligible(appId = "io.github.ioannes78.voica"))
        assertFalse(eligible(appId = "$lab.fake"))
        assertFalse(eligible(processName = "$lab:stage17_m4b_parent"))
        assertFalse(eligible(processName = "$lab:stage17_m4b_child"))
        assertFalse(eligible(processName = null))
    }

    @Test fun interruptedPoisonedOrUntrustedJournalNeverAdmits() {
        assertFalse(eligible(record = null))
        for (phase in listOf(
            Stage17P2M4BPhase.INTENT_DURABLE,
            Stage17P2M4BPhase.PARENT_BOUND,
            Stage17P2M4BPhase.CHILD_ADMITTED,
            Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
            Stage17P2M4BPhase.UNRESOLVED_POISONED,
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
            Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED,
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
        )) {
            assertFalse(eligible(record = Stage17P2M4BRecord(1L, nonce, phase)))
        }
    }

    @Test fun syntheticClaimOfNeverArmedCannotReplaceExternalProof() {
        assertFalse(eligible(record = neverArmed, trustedEnrollment = false))
        // A completed fake run cannot be replayed even with hypothetical
        // independent proof and enabled IPC.
        assertFalse(eligible(record = Stage17P2M4BRecord(
            1L, nonce, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
        )))
        assertFalse(eligible(record = Stage17P2M4BRecord(
            1L, nonce, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
        ), trustedEnrollment = false))
    }
}
