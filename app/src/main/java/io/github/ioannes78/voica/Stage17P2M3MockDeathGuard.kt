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


/**
 * Generation-bound evidence from bounded ApplicationExitInfo correlation.
 * Persist ONLY a normalized verdict. Historical OS evidence is never
 * treated as proof that native resources were released.
 */
internal object Stage17P2M3MockExitEvidence {
    private const val VERSION = "P2M3V1"
    private val allowed = setOf(
        "MATCHED", "UNKNOWN", "AMBIGUOUS",
        "PID_UNOBSERVED", "API_UNAVAILABLE",
    )

    fun encode(generation: Long, verdict: String): String {
        require(generation > 0)
        require(verdict in allowed)
        return "$VERSION|$generation|$verdict"
    }

    fun recovered(raw: String?, expectedGeneration: Long?): String {
        if (raw == null) return "NOT_RECORDED"
        val fields = raw.split('|')
        if (fields.size != 3 || fields[0] != VERSION) return "INVALID_RECORD"
        val generation = fields[1].toLongOrNull()
        if (generation == null || generation <= 0L ||
            fields[2] !in allowed
        ) return "INVALID_RECORD"
        if (expectedGeneration != generation) return "DIFFERENT_ATTEMPT"
        return fields[2]
    }
}
