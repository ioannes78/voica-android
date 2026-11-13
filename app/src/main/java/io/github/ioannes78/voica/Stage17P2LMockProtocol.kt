package io.github.ioannes78.voica

/**
 * Stage 17.3 P2-L: PURE KOTLIN FAKE protocol. No LiteRT-LM, native Engine, GPU or prompt.
 * This protocol describes observed fake transitions; it never claims native release.
 */
internal object Stage17P2LMockContract {
    const val PROCESS_SUFFIX = ":stage17_mock_probe"
    const val NATIVE_EXECUTION_ENABLED = false
    const val MAX_EVENTS = 32
    const val WHAT_RUN = 1
    const val WHAT_EVENT = 2
    const val WHAT_CANCEL = 3
    const val EXTRA_PID = "mock_child_pid"
    const val EXTRA_PROCESS = "mock_child_process"

    fun allowPackage(applicationId: String): Boolean =
        applicationId.endsWith(".qa") && !NATIVE_EXECUTION_ENABLED

    fun expectedProcess(applicationId: String): String = applicationId + PROCESS_SUFFIX

    fun matchesChild(
        applicationId: String,
        reportedProcess: String?,
        reportedPid: Int,
        observedProcess: String?,
    ): Boolean =
        allowPackage(applicationId) &&
            reportedPid > 0 &&
            reportedProcess == expectedProcess(applicationId) &&
            observedProcess == expectedProcess(applicationId)
}

internal enum class Stage17P2LMockScenario {
    NORMAL,
    CALLBACK_BEFORE_RETURN,
    SUBMISSION_NOT_RETURNED,
    TERMINAL_MISSING,
    CANCEL_ACKNOWLEDGED,
    CANCEL_UNACKNOWLEDGED,
}

internal enum class Stage17P2LMockPhase {
    PARENT_ARM_PERSISTED,
    BIND_REQUESTED,
    CHILD_BOUND,
    MODEL_INITIALIZING,
    MODEL_READY,
    CONVERSATION_ENTERED,
    CONVERSATION_READY,
    SEND_ENTERED,
    SEND_RETURNED,
    TERMINAL_AWAIT_ENTERED,
    FIRST_EMISSION,
    TERMINAL_DONE,
    TERMINAL_ERROR,
    CANCEL_REQUESTED,
    CANCEL_SIGNAL_SENT,
    CANCEL_ACKNOWLEDGED,
    RELEASE_CONFIRMED,
    RELEASE_UNCERTAIN,
    BIND_FAILED,
    BINDER_DIED,
    CHILD_IDENTITY_UNCONFIRMED,
    REPORT_TIMEOUT,
}

internal data class Stage17P2LMockEvent(val phase: Stage17P2LMockPhase, val elapsedMs: Long)

/**
 * A single fake attempt with sticky uncertainty. No automatic retry after ambiguous
 * process death, timeout or release. The app's genuine LLM coordinator is untouched.
 */
internal class Stage17P2LMockLedger(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val events = ArrayList<Stage17P2LMockEvent>(Stage17P2LMockContract.MAX_EVENTS)
    private var attempt: Int = 0
    private var startedAtNanos = 0L
    private var active = false
    private var poisoned = false
    private var terminal = false

    @Synchronized
    fun arm(attemptId: Int): Boolean {
        if (attemptId <= 0 || active || poisoned) return false
        attempt = attemptId
        startedAtNanos = clockNanos()
        events.clear()
        terminal = false
        active = true
        return mark(attemptId, Stage17P2LMockPhase.PARENT_ARM_PERSISTED)
    }

    @Synchronized
    fun mark(attemptId: Int, phase: Stage17P2LMockPhase): Boolean {
        if (!active || attemptId != attempt || events.size >= Stage17P2LMockContract.MAX_EVENTS) {
            return false
        }
        if (phase == Stage17P2LMockPhase.PARENT_ARM_PERSISTED && events.isNotEmpty()) return false
        if (phase == Stage17P2LMockPhase.FIRST_EMISSION &&
            events.any { it.phase == Stage17P2LMockPhase.FIRST_EMISSION }
        ) return false
        if (phase == Stage17P2LMockPhase.TERMINAL_DONE ||
            phase == Stage17P2LMockPhase.TERMINAL_ERROR
        ) {
            if (terminal) return false
            terminal = true
        }
        val elapsed = ((clockNanos() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000L
        events += Stage17P2LMockEvent(phase, elapsed)
        when (phase) {
            Stage17P2LMockPhase.RELEASE_CONFIRMED -> {
                if (!terminal) poisoned = true // fake release without terminal is invalid
                active = false
            }
            Stage17P2LMockPhase.RELEASE_UNCERTAIN,
            Stage17P2LMockPhase.BIND_FAILED,
            Stage17P2LMockPhase.BINDER_DIED,
            Stage17P2LMockPhase.CHILD_IDENTITY_UNCONFIRMED,
            Stage17P2LMockPhase.REPORT_TIMEOUT,
            -> {
                poisoned = true
                active = false
            }
            else -> Unit
        }
        return true
    }

    @Synchronized
    fun canAccept(attemptId: Int): Boolean = active && !poisoned && attemptId == attempt

    @Synchronized
    fun isBlocked(): Boolean = active || poisoned

    @Synchronized
    fun isPoisoned(): Boolean = poisoned

    @Synchronized
    fun snapshot(): List<Stage17P2LMockEvent> = events.toList()
}
