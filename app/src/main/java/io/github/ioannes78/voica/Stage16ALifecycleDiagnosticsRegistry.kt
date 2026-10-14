package io.github.ioannes78.voica

/**
 * Pull-only app diagnostics bridge. It owns no media consumer and performs no background polling;
 * the device diagnostics screen reads the latest lifecycle/reconnect snapshots on demand.
 */
object Stage16ALifecycleDiagnosticsRegistry {
    private val lock = Any()
    private var lifecycleProvider: (() -> Stage16ARecordingLifecycleSnapshot)? = null
    private var reconnectProvider: (() -> Stage16AReconnectRecoverySnapshot)? = null

    fun installLifecycle(provider: () -> Stage16ARecordingLifecycleSnapshot) {
        synchronized(lock) { lifecycleProvider = provider }
    }

    fun installReconnect(provider: () -> Stage16AReconnectRecoverySnapshot) {
        synchronized(lock) { reconnectProvider = provider }
    }

    fun snapshotText(): String? {
        val pair = synchronized(lock) { lifecycleProvider to reconnectProvider }
        val lifecycle = pair.first?.let { runCatching(it).getOrNull() } ?: return null
        val reconnect = pair.second?.let { runCatching(it).getOrNull() } ?: Stage16AReconnectRecoverySnapshot()
        val executionLease = Stage16ARealtimeExecutionLeaseDiagnostics.snapshot()
        val qaSlowdown = Stage16AQaArtificialAsrSlowdown.snapshot().takeIf { it.enabled }
        return buildString {
            append(lifecycle.toDiagnosticsText(reconnect))
            executionLease?.let { snapshot ->
                appendLine()
                appendLine()
                append(snapshot.toDiagnosticsText())
            }
            qaSlowdown?.let { snapshot ->
                appendLine()
                appendLine()
                append(snapshot.toDiagnosticsText())
            }
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            lifecycleProvider = null
            reconnectProvider = null
        }
        Stage16ARealtimeExecutionLeaseDiagnostics.clearForTest()
        Stage16AQaArtificialAsrSlowdown.clearForTest()
    }
}
