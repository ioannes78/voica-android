package io.github.ioannes78.voica

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

internal data class PhoneMicrophoneExecutionLeaseSnapshot(
    val foregroundAcquireAttempts: Int = 0,
    val foregroundAcquireSuccesses: Int = 0,
    val foregroundReleaseAttempts: Int = 0,
    val wakeLockAcquireAttempts: Int = 0,
    val wakeLockAcquireSuccesses: Int = 0,
    val wakeLockReleaseAttempts: Int = 0,
    val wakeLockReleaseSuccesses: Int = 0,
    val foregroundRequested: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val lastFailure: String? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A.1 phone microphone execution lease]")
        append(
            "fgRequested=$foregroundRequested " +
                "fgAcquire=$foregroundAcquireSuccesses/$foregroundAcquireAttempts " +
                "fgRelease=$foregroundReleaseAttempts " +
                "wakeLockHeld=$wakeLockHeld " +
                "wakeAcquire=$wakeLockAcquireSuccesses/$wakeLockAcquireAttempts " +
                "wakeRelease=$wakeLockReleaseSuccesses/$wakeLockReleaseAttempts " +
                "lastFailure=${lastFailure ?: "n/a"}",
        )
    }
}

internal object PhoneMicrophoneExecutionLeaseDiagnostics {
    private val lock = Any()
    private var retainedSnapshot: PhoneMicrophoneExecutionLeaseSnapshot? = null

    fun snapshot(): PhoneMicrophoneExecutionLeaseSnapshot? =
        synchronized(lock) { retainedSnapshot }

    fun record(snapshot: PhoneMicrophoneExecutionLeaseSnapshot) {
        synchronized(lock) { retainedSnapshot = snapshot }
    }

    fun clearForTest() {
        synchronized(lock) { retainedSnapshot = null }
    }
}

internal interface PhoneMicrophonePartialWakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

internal interface PhoneMicrophoneForegroundLeaseHandle {
    fun acquire(label: String): Boolean
    fun release()
}

/**
 * Capture-scoped Android execution lease for formal phone microphone recording.
 *
 * This lease is intentionally independent from Stage 16A inference. Realtime ASR may fail, stop or
 * overflow while this lease remains active for the formal recording. Foreground-service acquisition
 * is mandatory for capture start; PARTIAL_WAKE_LOCK failure is retained as diagnostics so the caller
 * can surface reduced screen-off reliability without silently pretending the lease is healthy.
 */
internal class PhoneMicrophoneExecutionLease(
    private val foreground: PhoneMicrophoneForegroundLeaseHandle,
    private val wakeLock: PhoneMicrophonePartialWakeLockHandle,
) {
    private val lock = Any()
    private var snapshot = PhoneMicrophoneExecutionLeaseSnapshot()

    fun acquire(label: String): Boolean = synchronized(lock) {
        if (snapshot.foregroundRequested) return@synchronized true

        snapshot =
            snapshot.copy(
                foregroundAcquireAttempts = snapshot.foregroundAcquireAttempts + 1,
                lastFailure = null,
            )

        val foregroundAccepted =
            try {
                foreground.acquire(label)
            } catch (failure: Throwable) {
                snapshot = snapshot.copy(lastFailure = failure.describePhoneMicrophoneFailure())
                false
            }

        if (!foregroundAccepted) {
            snapshot = snapshot.copy(foregroundRequested = false)
            PhoneMicrophoneExecutionLeaseDiagnostics.record(snapshot)
            return@synchronized false
        }

        snapshot =
            snapshot.copy(
                foregroundAcquireSuccesses = snapshot.foregroundAcquireSuccesses + 1,
                foregroundRequested = true,
                wakeLockAcquireAttempts = snapshot.wakeLockAcquireAttempts + 1,
            )

        val wakeLockSuccess =
            try {
                if (!wakeLock.isHeld) wakeLock.acquire()
                wakeLock.isHeld
            } catch (failure: Throwable) {
                snapshot = snapshot.copy(lastFailure = failure.describePhoneMicrophoneFailure())
                false
            }

        snapshot =
            snapshot.copy(
                wakeLockAcquireSuccesses = snapshot.wakeLockAcquireSuccesses + if (wakeLockSuccess) 1 else 0,
                wakeLockHeld = wakeLockSuccess,
            )
        PhoneMicrophoneExecutionLeaseDiagnostics.record(snapshot)
        true
    }

    fun release() = synchronized(lock) {
        if (snapshot.wakeLockHeld || runCatching { wakeLock.isHeld }.getOrDefault(false)) {
            snapshot = snapshot.copy(wakeLockReleaseAttempts = snapshot.wakeLockReleaseAttempts + 1)
            val released =
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                    !wakeLock.isHeld
                } catch (failure: Throwable) {
                    snapshot = snapshot.copy(lastFailure = failure.describePhoneMicrophoneFailure())
                    false
                }
            snapshot =
                snapshot.copy(
                    wakeLockReleaseSuccesses = snapshot.wakeLockReleaseSuccesses + if (released) 1 else 0,
                    wakeLockHeld = runCatching { wakeLock.isHeld }.getOrDefault(false),
                )
        }

        if (snapshot.foregroundRequested) {
            snapshot = snapshot.copy(foregroundReleaseAttempts = snapshot.foregroundReleaseAttempts + 1)
            try {
                foreground.release()
            } catch (failure: Throwable) {
                snapshot = snapshot.copy(lastFailure = failure.describePhoneMicrophoneFailure())
            }
            snapshot = snapshot.copy(foregroundRequested = false)
        }

        PhoneMicrophoneExecutionLeaseDiagnostics.record(snapshot)
    }

    fun snapshot(): PhoneMicrophoneExecutionLeaseSnapshot = synchronized(lock) { snapshot }

    companion object {
        fun android(context: Context): PhoneMicrophoneExecutionLease {
            val appContext = context.applicationContext
            return PhoneMicrophoneExecutionLease(
                foreground = AndroidPhoneMicrophoneForegroundLeaseHandle(appContext),
                wakeLock = AndroidPhoneMicrophonePartialWakeLockHandle(appContext),
            )
        }
    }
}

private class AndroidPhoneMicrophoneForegroundLeaseHandle(
    private val context: Context,
) : PhoneMicrophoneForegroundLeaseHandle {
    override fun acquire(label: String): Boolean =
        PhoneMicrophoneForegroundService.acquire(context, label).requestAccepted

    override fun release() {
        PhoneMicrophoneForegroundService.release(context)
    }
}

private class AndroidPhoneMicrophonePartialWakeLockHandle(
    context: Context,
) : PhoneMicrophonePartialWakeLockHandle {
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
        const val WAKE_LOCK_TAG = "Voica:PhoneMicrophoneCapture"
    }
}

private fun Throwable.describePhoneMicrophoneFailure(): String =
    "${javaClass.simpleName}:${message.orEmpty()}"
