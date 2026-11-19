package io.github.ioannes78.voica

import android.content.Context
import android.os.Looper

/**
 * MAIN PROCESS ONLY. Never instantiate in :stage17_m4b_parent or :stage17_m4b_child.
 * commit() is synchronous; apply() is forbidden for owner authorization.
 * No auto-seed, reset, or poison clearing entrypoint.
 */
internal class Stage17P2M4BControllerJournal(private val context: Context) {
    // Any failed/ambiguous commit remains untrusted for the life of this broker.
    private var writeUncertain = false
    private val prefs by lazy {
        context.getSharedPreferences(Stage17P2M4BContract.JOURNAL, Context.MODE_PRIVATE)
    }

    private fun requireController() {
        check(Stage17P2M4BContract.allowPackage(context.packageName))
        check(Stage17P2M4BContract.currentProcessName(context) == context.packageName) {
            "P2-M4B controller journal is main-process-only"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "P2-M4B commits must execute on a serialized non-UI worker"
        }
    }

    @Synchronized fun recovered(): Stage17P2M4BRecord? {
        requireController()
        // Seed must have been explicitly verified by a separate fresh fixture.
        if (writeUncertain) return null
        // A blank file, partial fixture marker, or added unexpected keys is not a clean seed.
        if (!prefs.getBoolean(Stage17P2M4BContract.KEY_SEEDED, false)) return null
        if (prefs.all.keys != setOf(
                Stage17P2M4BContract.KEY_SEEDED,
                Stage17P2M4BContract.KEY_RECORD,
            )
        ) return null
        return Stage17P2M4BCodec.decode(
            prefs.getString(Stage17P2M4BContract.KEY_RECORD, null),
        )
    }

    /** One-time fresh installation fixture proof is deliberately NOT exposed by this class. */
    @Synchronized fun claim(nonce: String): Stage17P2M4BRecord? {
        requireController()
        val next = Stage17P2M4BCodec.claim(recovered(), nonce) ?: return null
        return commitAndVerify(next)
    }

    @Synchronized fun transition(
        generation: Long, nonce: String, phase: Stage17P2M4BPhase,
    ): Stage17P2M4BRecord? {
        requireController()
        val next = Stage17P2M4BCodec.next(recovered(), generation, nonce, phase)
            ?: return null
        return commitAndVerify(next)
    }
    /**
     * A successful SharedPreferences.commit() is necessary but not enough:
     * immediately re-read and compare the exact durable generation/nonce/phase.
     * Fail-closed on a write exception, rejection, or readback mismatch.
     */
    private fun commitAndVerify(next: Stage17P2M4BRecord): Stage17P2M4BRecord? {
        if (writeUncertain) return null
        val raw = Stage17P2M4BCodec.encode(next)
        val wrote = try {
            prefs.edit().putString(Stage17P2M4BContract.KEY_RECORD, raw).commit()
        } catch (_: RuntimeException) {
            false
        }
        if (!wrote) {
            writeUncertain = true
            return null
        }
        val restored = try { recovered() } catch (_: RuntimeException) { null }
        if (restored != next) {
            writeUncertain = true
            return null
        }
        return restored
    }
}

