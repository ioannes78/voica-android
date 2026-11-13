package io.github.ioannes78.voica.localllm

/**
 * Content-free diagnostic event emitted at the exact native runtime phase boundary.
 *
 * The event intentionally contains only scalar execution metadata. Prompt text, transcript text,
 * generated output and any other user content are structurally absent.
 */
data class LocalLlmRuntimeDiagnosticPhase(
    val phase: LocalLlmRuntimePhase,
    val requestedPolicy: LocalLlmBackendPolicy,
    val resolvedBackend: LocalLlmBackend,
    val schemaEnabled: Boolean,
    val schemaSizeChars: Int,
    val emissionCount: Long,
)

/**
 * QA instrumentation hook for Stage 17 regression forensics.
 *
 * Production installs no observer, so this is a no-op on the normal product path. Observer failures
 * are swallowed deliberately: diagnostics must never alter native ownership, cancellation, terminal
 * sequencing or release semantics.
 */
object LocalLlmRuntimeDiagnosticObserverRegistry {
    @Volatile
    private var observer: ((LocalLlmRuntimeDiagnosticPhase) -> Unit)? = null

    fun install(observer: ((LocalLlmRuntimeDiagnosticPhase) -> Unit)?) {
        this.observer = observer
    }

    internal fun emit(event: LocalLlmRuntimeDiagnosticPhase) {
        val activeObserver = observer ?: return
        runCatching { activeObserver(event) }
    }
}
