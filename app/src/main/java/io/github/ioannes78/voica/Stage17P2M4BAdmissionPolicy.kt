package io.github.ioannes78.voica

/**
 * Gate for FAKE model Binder admission, deliberately separate from package
 * eligibility for read-only diagnostics. Historical .qa can NEVER admit.
 *
 * This pure policy does not issue a trusted enrollment grant. In B1 the broker
 * supplies trustedEnrollment = false; no app-local empty journal, mock host
 * JSON, or installed package state may substitute for external attestation.
 */
internal object Stage17P2M4BAdmissionPolicy {
    fun canAdmit(
        appId: String,
        processName: String?,
        ipcAdmissionEnabled: Boolean,
        trustedEnrollment: Boolean,
        ownerRecord: Stage17P2M4BRecord?,
    ): Boolean =
        Stage17P2M4BContract.isLabPackage(appId) &&
            processName == appId &&
            ipcAdmissionEnabled &&
            trustedEnrollment &&
            !Stage17P2M4BCodec.blocked(ownerRecord)
}
