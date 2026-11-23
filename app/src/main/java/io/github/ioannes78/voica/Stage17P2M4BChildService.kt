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
    private var released = false
    private var generation = 0L
    private var nonce = ""
    private val inbound = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == contract.WHAT_CHILD_ADMIT) {
            val data = msg.data
            val ok = Stage17P2M4BChildAdmissionPolicy.accepts(
                packageName = packageName,
                currentProcess = contract.currentProcessName(this),
                actualUid = Process.myUid(),
                senderUid = msg.sendingUid,
                ipcEnabled = contract.IPC_ADMISSION_ENABLED,
                alreadyAdmitted = admitted,
                generation = data.getLong(contract.EXTRA_GENERATION),
                nonce = data.getString(contract.EXTRA_NONCE),
                model = data.getString(contract.EXTRA_MODEL),
                claimedUid = data.getInt(contract.EXTRA_UID),
                claimedParentProcess = data.getString(contract.EXTRA_PROCESS),
                hasReplyChannel = msg.replyTo != null,
            )
            if (ok) {
                admitted = true
                generation = data.getLong(contract.EXTRA_GENERATION)
                nonce = requireNotNull(data.getString(contract.EXTRA_NONCE))
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
        } else if (msg.what == contract.WHAT_FAKE_CHILD_RELEASE_REQUEST) {
            val data = msg.data
            val ok = Stage17P2M4BChildReleasePolicy.accepts(
                packageName = packageName,
                processName = contract.currentProcessName(this),
                actualUid = Process.myUid(),
                senderUid = msg.sendingUid,
                ipcEnabled = contract.IPC_ADMISSION_ENABLED,
                admitted = admitted,
                alreadyReleased = released,
                generation = generation,
                nonce = nonce,
                requestGeneration = data.getLong(contract.EXTRA_GENERATION),
                requestNonce = data.getString(contract.EXTRA_NONCE),
                requestModel = data.getString(contract.EXTRA_MODEL),
                requestParentProcess = data.getString(contract.EXTRA_PROCESS),
                requestUid = data.getInt(contract.EXTRA_UID, -1),
                hasReplyChannel = msg.replyTo != null,
            )
            if (ok) {
                released = true
                val response = Message.obtain(null, contract.WHAT_FAKE_CHILD_RELEASE_ACK)
                response.data = Bundle().apply {
                    putLong(contract.EXTRA_GENERATION, generation)
                    putString(contract.EXTRA_NONCE, nonce)
                    putString(contract.EXTRA_MODEL, contract.FAKE_MODEL)
                    putString(contract.EXTRA_PROCESS, contract.childProcess(packageName))
                    putInt(contract.EXTRA_UID, Process.myUid())
                    putInt(contract.EXTRA_PID, Process.myPid())
                }
                try { msg.replyTo?.send(response) } catch (_: RemoteException) {
                    // No owner release may be inferred when parent callback is lost.
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
