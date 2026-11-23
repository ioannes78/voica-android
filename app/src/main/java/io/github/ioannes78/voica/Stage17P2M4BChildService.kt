package io.github.ioannes78.voica

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException

/**
 * QA-only fake child. Does not load JNI, Engine, model, or GPU.
 * Bound connection is not a release acknowledgment. One admission per process.
 */
class Stage17P2M4BChildService : Service() {
    private val contract = Stage17P2M4BContract
    private var admitted = false
    private val inbound = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == contract.WHAT_CHILD_ADMIT &&
            contract.IPC_ADMISSION_ENABLED &&
            contract.isLabPackage(packageName) && !admitted
        ) {
            val data = msg.data
            val ok = contract.validIdentity(
                packageName, Stage17P2M4BContract.currentProcessName(this), contract.CHILD_SUFFIX,
                data.getLong(contract.EXTRA_GENERATION),
                data.getString(contract.EXTRA_NONCE),
                data.getString(contract.EXTRA_MODEL),
                data.getInt(contract.EXTRA_UID), Process.myUid(),
            ) && data.getString(contract.EXTRA_PROCESS) == contract.parentProcess(packageName) &&
                msg.replyTo != null
            if (ok && msg.sendingUid == Process.myUid()) {
                admitted = true
                val response = Message.obtain(null, contract.WHAT_CHILD_READY)
                response.data = Bundle().apply {
                    putLong(contract.EXTRA_GENERATION, data.getLong(contract.EXTRA_GENERATION))
                    putString(contract.EXTRA_NONCE, data.getString(contract.EXTRA_NONCE))
                    putString(contract.EXTRA_MODEL, contract.FAKE_MODEL)
                    putString(contract.EXTRA_PROCESS, contract.childProcess(packageName))
                    putInt(contract.EXTRA_UID, Process.myUid())
                    putInt(contract.EXTRA_PID, Process.myPid())
                }
                try { msg.replyTo?.send(response) } catch (_: RemoteException) {
                    // Parent disappeared. No new fake model owner is admitted.
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (contract.isLabPackage(packageName) &&
            Stage17P2M4BContract.currentProcessName(this) == contract.childProcess(packageName)
        ) inbound.binder else null
}
