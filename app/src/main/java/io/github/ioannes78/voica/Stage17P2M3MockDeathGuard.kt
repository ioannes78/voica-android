package io.github.ioannes78.voica

/**
 * QA-only, mock-only PROCESS TERMINATION gate.
 * This deliberate fault is only legal for the separate child process. Never
 * kill the host app, never load any model/native runtime, never auto-repeat.
 */
internal object Stage17P2M3MockDeathGuard {
    fun canSelfTerminate(
        applicationId: String,
        observedProcess: String?,
        scenario: Stage17P2LMockScenario,
        originalAttemptStillActive: Boolean,
    ): Boolean =
        Stage17P2LMockContract.allowPackage(applicationId) &&
            !Stage17P2LMockContract.NATIVE_EXECUTION_ENABLED &&
            observedProcess == Stage17P2LMockContract.expectedProcess(applicationId) &&
            scenario == Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND &&
            originalAttemptStillActive
}
