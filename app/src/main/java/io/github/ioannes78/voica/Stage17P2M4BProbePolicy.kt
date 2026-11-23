package io.github.ioannes78.voica

/** Only read-only Binder liveness; never authorizes a fake model owner. */
internal object Stage17P2M4BProbePolicy {
    private val noncePattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun acceptsPing(
        packageName: String,
        processName: String?,
        actualUid: Int,
        senderUid: Int,
        controllerProcess: String?,
        token: String?,
        model: String?,
        parentArmed: Boolean,
    ): Boolean =
        Stage17P2M4BContract.isLabPackage(packageName) &&
            processName == Stage17P2M4BContract.parentProcess(packageName) &&
            actualUid > 0 && senderUid == actualUid &&
            controllerProcess == packageName &&
            token != null && noncePattern.matches(token) &&
            model == Stage17P2M4BContract.FAKE_MODEL && !parentArmed

    fun acceptsPong(
        packageName: String,
        processName: String?,
        actualUid: Int,
        senderUid: Int,
        reportedUid: Int,
        controllerPid: Int,
        reportedParentPid: Int,
        reportedProcess: String?,
        token: String?,
        expectedToken: String,
        model: String?,
    ): Boolean =
        Stage17P2M4BContract.isLabPackage(packageName) &&
            processName == packageName &&
            actualUid > 0 && senderUid == actualUid && reportedUid == actualUid &&
            controllerPid > 0 && reportedParentPid > 0 && controllerPid != reportedParentPid &&
            reportedProcess == Stage17P2M4BContract.parentProcess(packageName) &&
            noncePattern.matches(expectedToken) && token == expectedToken &&
            model == Stage17P2M4BContract.FAKE_MODEL
}
