package io.github.ioannes78.voica

/**
 * Pure policy for a single fake child READY acknowledgment.
 * Binder sender UID is a separate trusted input, not a Bundle field.
 * This is not a native owner release proof.
 */
internal object Stage17P2M4BChildAckPolicy {
    fun accepts(
        packageName: String,
        armed: Boolean,
        admissionSent: Boolean,
        alreadyAccepted: Boolean,
        actualUid: Int,
        senderUid: Int,
        generation: Long,
        nonce: String,
        suppliedGeneration: Long,
        suppliedNonce: String?,
        suppliedModel: String?,
        suppliedProcess: String?,
        suppliedUid: Int,
    ): Boolean =
        Stage17P2M4BContract.allowPackage(packageName) &&
            armed && admissionSent && !alreadyAccepted &&
            actualUid > 0 && senderUid == actualUid &&
            generation > 0 && suppliedGeneration == generation &&
            nonce.isNotBlank() && suppliedNonce == nonce &&
            suppliedModel == Stage17P2M4BContract.FAKE_MODEL &&
            suppliedProcess == Stage17P2M4BContract.childProcess(packageName) &&
            suppliedUid == actualUid
}
