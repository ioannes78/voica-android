package io.github.ioannes78.voica

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-lifetime holder for the internal Stage 15.9 QA session. */
internal object Stage159FinalGateSessionRegistry {
    private val lock = Any()
    private var session: Stage159FinalGateSession? = null

    fun get(runtime: RealtimeAudioRuntime): Stage159FinalGateSession = synchronized(lock) {
        session ?: Stage159FinalGateSession(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            runtime = runtime,
        ).also { session = it }
    }
}
