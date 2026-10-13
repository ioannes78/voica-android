package io.github.ioannes78.voica

/**
 * Pull-only app diagnostics bridge. It owns no media consumer and performs no background polling;
 * the device diagnostics screen reads the latest lifecycle/reconnect snapshots on demand.
 */
object Stage16ALifecycleDiagnosticsRegistry {
    private val lock = Any()
    private var provider: (() -> String)? = null

    fun install(
        lifecycle: () -> Stage16ARecordingLifecycleSnapshot,
        reconnect: () -> Stage16AReconnectRecoverySnapshot,
    ) {
        synchronized(lock) {
            provider = { lifecycle().toDiagnosticsText(reconnect()) }
        }
    }

    fun snapshotText(): String? {
        val current = synchronized(lock) { provider } ?: return null
        return runCatching(current).getOrNull()
    }

    internal fun clearForTest() {
        synchronized(lock) { provider = null }
    }
}
