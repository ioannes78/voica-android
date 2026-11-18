package io.github.ioannes78.voica

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException

/** Deliberately disposable PARENT-LIKE process, not the real application main process. */
class Stage17P2M4BParentService : Service() {
    private val contract = Stage17P2M4BContract
    private var armed = false
    private var connected = false
    private var generation = 0L
    private var nonce = ""
    private var controller: Messenger? = null
    private var child: Messenger? = null

    private val childReceiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == contract.WHAT_CHILD_READY && armed &&
            msg.data.getLong(contract.EXTRA_GENERATION) == generation &&
            msg.data.getString(contract.EXTRA_NONCE) == nonce &&
            msg.data.getString(contract.EXTRA_PROCESS) == contract.childProcess(packageName) &&
            msg.data.getInt(contract.EXTRA_UID) == Process.myUid()
        ) {
            report(contract.WHAT_CHILD_READY)
        }
        true
    })

    private val childConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!armed || binder == null || child != null) return
            child = Messenger(binder)
            val message = Message.obtain(null, contract.WHAT_CHILD_ADMIT)
            message.data = identity()
            message.replyTo = childReceiver
            try {
                child?.send(message)
            } catch (_: RemoteException) {
                report(contract.WHAT_DENIED)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            child = null
            report(contract.WHAT_DENIED)
        }
    }

    private val inbound = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            contract.WHAT_ARM -> {
                val supplied = msg.data
                val incomingGeneration = supplied.getLong(contract.EXTRA_GENERATION)
                val incomingNonce = supplied.getString(contract.EXTRA_NONCE)
                val approved = contract.validIdentity(
                    packageName, Stage17P2M4BContract.currentProcessName(this), contract.PARENT_SUFFIX,
                    incomingGeneration, incomingNonce, supplied.getString(contract.EXTRA_MODEL),
                    supplied.getInt(contract.EXTRA_UID), Process.myUid(),
                )
                if (!contract.IPC_ADMISSION_ENABLED || !approved || armed || msg.replyTo == null) {
                    // No automatic retry, no second run, no native cleanup.
                    true
                } else {
                    armed = true
                    generation = incomingGeneration
                    nonce = requireNotNull(incomingNonce)
                    controller = msg.replyTo
                    report(contract.WHAT_PARENT_READY)
                    connected = bindService(
                        Intent(this, Stage17P2M4BChildService::class.java),
                        childConnection, Context.BIND_AUTO_CREATE,
                    )
                    if (!connected) report(contract.WHAT_DENIED)
                    true
                }
            }
            // No WHAT_EXIT in B1. Any unknown command is rejected.
            else -> true
        }
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (contract.allowPackage(packageName) &&
            Stage17P2M4BContract.currentProcessName(this) == contract.parentProcess(packageName)
        ) inbound.binder else null

    private fun identity(): Bundle = Bundle().apply {
        putLong(contract.EXTRA_GENERATION, generation)
        putString(contract.EXTRA_NONCE, nonce)
        putString(contract.EXTRA_MODEL, contract.FAKE_MODEL)
        putString(contract.EXTRA_PROCESS, contract.parentProcess(packageName))
        putInt(contract.EXTRA_UID, Process.myUid())
        putInt(contract.EXTRA_PID, Process.myPid())
    }

    private fun report(what: Int) {
        val msg = Message.obtain(null, what)
        msg.data = identity()
        try { controller?.send(msg) } catch (_: RemoteException) { /* deny; no retry */ }
    }

    override fun onDestroy() {
        if (connected) unbindService(childConnection)
        child = null
        controller = null
        super.onDestroy()
    }
}
