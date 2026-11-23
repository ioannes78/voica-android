package io.github.ioannes78.voica

/** Fake-only Parent release state machine. Calling code must separately verify Binder caller. */
internal object Stage17P2M4BParentReleasePolicy {
    fun mayObserveTerminal(
        armed: Boolean, childReady: Boolean, terminalReported: Boolean,
        childReleaseSent: Boolean,
    ): Boolean = armed && childReady && !terminalReported && !childReleaseSent

    fun mayRequestChildRelease(
        armed: Boolean, terminalReported: Boolean, childReady: Boolean,
        childReleaseSent: Boolean, childBound: Boolean,
    ): Boolean = armed && terminalReported && childReady &&
        !childReleaseSent && childBound

    fun acceptsChildReleaseAck(
        packageName: String, armed: Boolean,
        childReady: Boolean, terminalReported: Boolean,
        childReleaseSent: Boolean, alreadyAccepted: Boolean,
        actualUid: Int, sendingUid: Int, generation: Long, nonce: String,
        suppliedGeneration: Long, suppliedNonce: String?,
        suppliedModel: String?, suppliedProcess: String?, suppliedUid: Int,
    ): Boolean = armed && childReady && terminalReported &&
        childReleaseSent && !alreadyAccepted &&
        Stage17P2M4BContract.isLabPackage(packageName) &&
        actualUid > 0 && sendingUid == actualUid &&
        generation > 0L && generation == suppliedGeneration &&
        nonce.isNotBlank() && nonce == suppliedNonce &&
        suppliedModel == Stage17P2M4BContract.FAKE_MODEL &&
        suppliedProcess == Stage17P2M4BContract.childProcess(packageName) &&
        suppliedUid == actualUid

    fun mayReleaseParent(
        armed: Boolean, childReleaseAccepted: Boolean,
        alreadyReleased: Boolean, childBound: Boolean,
    ): Boolean = armed && childReleaseAccepted && !alreadyReleased && childBound
}
