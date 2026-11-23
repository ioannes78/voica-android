package io.github.ioannes78.voica

/** Independent fake-child ownership gate: never read permissions from a caller Bundle. */
internal object Stage17P2M4BChildAdmissionPolicy {
    fun accepts(
        packageName: String,
        currentProcess: String?,
        actualUid: Int,
        senderUid: Int,
        ipcEnabled: Boolean,
        alreadyAdmitted: Boolean,
        generation: Long,
        nonce: String?,
        model: String?,
        claimedUid: Int,
        claimedParentProcess: String?,
        hasReplyChannel: Boolean,
    ): Boolean =
        ipcEnabled && !alreadyAdmitted &&
            Stage17P2M4BContract.isLabPackage(packageName) &&
            Stage17P2M4BContract.validIdentity(
                packageName, currentProcess, Stage17P2M4BContract.CHILD_SUFFIX,
                generation, nonce, model, claimedUid, actualUid,
            ) &&
            senderUid == actualUid &&
            claimedParentProcess == Stage17P2M4BContract.parentProcess(packageName) &&
            hasReplyChannel
}
