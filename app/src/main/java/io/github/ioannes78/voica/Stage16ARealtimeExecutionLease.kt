package io.github.ioannes78.voica

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

internal data class Stage16ARealtimeExecutionLeaseSnapshot(
    val acquireAttempts: Int = 0,
    val acquireSuccesses: Int = 0,
    val releaseAttempts: Int = 0,
    val releaseSuccesses: Int = 0,
    val wakeLockHeld: Boolean = false,
    val lastFailure: String? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A execution lease]")
        append(
            "wakeLockHeld=$wakeLockHeld " +
                "acquire=$acquireSuccesses/$acquireAttempts " +
                "release=$releaseSuccesses/$releaseAttempts " +
                "lastFailure=${lastFailure ?: "n/a"}",
        )
    }
}

internal object Stage16ARealtimeExecutionLeaseDiagnostics {
    private val lock = Any()
    private var retainedSnapshot: Stage16ARealtimeExecutionLeaseSnapshot? = null

    fun snapshot(): Stage16ARealtimeExecutionLeaseSnapshot? =
        synchronized(lock) { retainedSnapshot }

    fun record(snapshot: Stage16ARealtimeExecutionLeaseSnapshot) {
        synchronized(lock) { retainedSnapshot = snapshot }
    }

    fun clearForTest() {
        synchronized(lock) { retainedSnapshot = null }
    }
}

internal interface Stage16APartialWakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Run-scoped PARTIAL_WAKE_LOCK lease for true-streaming inference.
 *
 * A fresh instance is created for each Stage 16A pipeline bundle. The lock is acquired only after
 * the native pipeline is fully constructed and is released from bundle cleanup on every terminal
 * path. Android also drops the lock if the process dies, so there is no persistent cross-process
 * ownership. Failures are diagnostic-only: recorder media must remain usable even if an OEM rejects
 * the wake-lock request.
 */
internal class Stage16ARealtimeExecutionLease(
    private val wakeLock: Stage16APartialWakeLockHandle,
) {
    private val lock = Any()
    private var snapshot = Stage16ARealtimeExecutionLeaseSnapshot()

    fun acquire(): Boolean = synchronized(lock) {
        if (snapshot.wakeLockHeld) return@synchronized true
        snapshot = snapshot.copy(acquireAttempts = snapshot.acquireAttempts + 1, lastFailure = null)
        val success =
            try {
                if (!wakeLock.isHeld) wakeLock.acquire()
                wakeLock.isHeld
            } catch (failure: Throwable) {
                snapshot = snapshot.copy(lastFailure = failure.describe())
                false
            }
        snapshot =
            snapshot.copy(
                acquireSuccesses = snapshot.acquireSuccesses + if (success) 1 else 0,
                wakeLockHeld = success,
            )
        Stage16ARealtimeExecutionLeaseDiagnostics.record(snapshot)
        success
    }

    fun release() = synchronized(lock) {
        snapshot = snapshot.copy(releaseAttempts = snapshot.releaseAttempts + 1)
        var success = false
        try {
            if (wakeLock.isHeld) wakeLock.release()
            success = !wakeLock.isHeld
        } catch (failure: Throwable) {
            snapshot = snapshot.copy(lastFailure = failure.describe())
        }
        snapshot =
            snapshot.copy(
                releaseSuccesses = snapshot.releaseSuccesses + if (success) 1 else 0,
                wakeLockHeld = runCatching { wakeLock.isHeld }.getOrDefault(false),
            )
        Stage16ARealtimeExecutionLeaseDiagnostics.record(snapshot)
    }

    fun snapshot(): Stage16ARealtimeExecutionLeaseSnapshot = synchronized(lock) { snapshot }

    companion object {
        fun android(context: Context): Stage16ARealtimeExecutionLease =
            Stage16ARealtimeExecutionLease(AndroidPartialWakeLockHandle(context.applicationContext))
    }
}

private class AndroidPartialWakeLockHandle(
    context: Context,
) : Stage16APartialWakeLockHandle {
    private val wakeLock =
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    override val isHeld: Boolean
        get() = wakeLock.isHeld

    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        wakeLock.acquire()
    }

    override fun release() {
        wakeLock.release()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "Voica:Stage16ARealtime"
    }
}

private fun Throwable.describe(): String =
    "${javaClass.simpleName}:${message.orEmpty()}"
