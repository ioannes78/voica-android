package io.github.ioannes78.voica

/** Fake-only child terminal release, never proves a native model or GPU release. */
internal object Stage17P2M4BChildReleasePolicy {
    fun accepts(
        packageName: String,
        processName: String?,
        actualUid: Int,
        senderUid: Int,
        ipcEnabled: Boolean,
        admitted: Boolean,
        alreadyReleased: Boolean,
        generation: Long,
        nonce: String,
        requestGeneration: Long,
        requestNonce: String?,
        requestModel: String?,
        requestParentProcess: String?,
        requestUid: Int,
        hasReplyChannel: Boolean,
    ): Boolean =
        ipcEnabled && admitted && !alreadyReleased &&
            Stage17P2M4BContract.isLabPackage(packageName) &&
            processName == Stage17P2M4BContract.childProcess(packageName) &&
            actualUid > 0 && senderUid == actualUid && requestUid == actualUid &&
            generation > 0L && requestGeneration == generation &&
            nonce.isNotBlank() && requestNonce == nonce &&
            requestModel == Stage17P2M4BContract.FAKE_MODEL &&
            requestParentProcess == Stage17P2M4BContract.parentProcess(packageName) &&
            hasReplyChannel
}
