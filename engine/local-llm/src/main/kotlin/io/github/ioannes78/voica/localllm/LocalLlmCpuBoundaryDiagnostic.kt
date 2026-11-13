package io.github.ioannes78.voica.localllm

/**
 * Stage 17.3 P2-I diagnostic boundary only. These events are observations of the Kotlin/JNI
 * wrapper, NOT evidence of GPU decode, native worker liveness, or a root cause.
 * No prompt, response, transcript, file path, PID or thread ID can enter this API.
 */
enum class LocalLlmCpuBoundaryPhase {
    SEND_ENTERED,
    SEND_RETURNED,
    TERMINAL_AWAIT_ENTERED,
    FIRST_EMISSION,
    TERMINAL_DONE,
    TERMINAL_ERROR,
    CANCEL_REQUESTED,
    CANCEL_SIGNAL_SENT,
    CANCEL_ACK_TIMEOUT,
    RELEASE_BLOCKED,
}

data class LocalLlmCpuBoundaryEntry(
    val phase: LocalLlmCpuBoundaryPhase,
    val elapsedMs: Long,
)

/** Bounded in-memory phase ledger; all callback work is short, content free and synchronized. */
class LocalLlmCpuBoundaryLedger(
    private val nanoClock: () -> Long = System::nanoTime,
    private val capacity: Int = 32,
) {
    init {
        require(capacity in 1..32)
    }

    private val events = ArrayList<LocalLlmCpuBoundaryEntry>(capacity)
    private var armed = false
    private var startNanos = 0L

    @Synchronized
    fun arm() {
        events.clear()
        startNanos = nanoClock()
        armed = true
    }

    @Synchronized
    fun disarm() {
        armed = false
    }

    @Synchronized
    fun record(phase: LocalLlmCpuBoundaryPhase): List<LocalLlmCpuBoundaryEntry>? {
        if (!armed || events.size >= capacity) return null
        if (phase == LocalLlmCpuBoundaryPhase.FIRST_EMISSION &&
            events.any { it.phase == LocalLlmCpuBoundaryPhase.FIRST_EMISSION }
        ) return null
        val elapsedMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000L
        events.add(LocalLlmCpuBoundaryEntry(phase, elapsedMs))
        return events.toList()
    }

    @Synchronized
    fun snapshot(): List<LocalLlmCpuBoundaryEntry> = events.toList()
}

/**
 * Optional QA observer, installed only for an explicit CPU attempt on the QA screen.
 * There is no listener in normal product operation. Never let QA diagnostics throw across JNI.
 */
object LocalLlmCpuBoundaryObserverRegistry {
    @Volatile
    private var listener: ((LocalLlmCpuBoundaryPhase) -> Unit)? = null

    fun install(next: ((LocalLlmCpuBoundaryPhase) -> Unit)?) {
        listener = next
    }

    fun emit(phase: LocalLlmCpuBoundaryPhase) {
        try {
            listener?.invoke(phase)
        } catch (_: Exception) {
            // Diagnostic failures must not affect the native inference/cancellation lifecycle.
        }
    }
}
