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
    private var childAdmissionSent = false
    private var childReadyAccepted = false
    private var terminalReported = false
    private var childReleaseSent = false
    private var childReleaseAccepted = false
    private var parentReleaseSent = false
    private var generation = 0L
    private var nonce = ""
    private var controller: Messenger? = null
    private var child: Messenger? = null

    private val childReceiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val data = msg.data
        val accepted = msg.what == contract.WHAT_CHILD_READY &&
            Stage17P2M4BChildAckPolicy.accepts(
                packageName = packageName,
                armed = armed,
                admissionSent = childAdmissionSent,
                alreadyAccepted = childReadyAccepted,
                actualUid = Process.myUid(),
                senderUid = msg.sendingUid,
                generation = generation,
                nonce = nonce,
                suppliedGeneration = data.getLong(contract.EXTRA_GENERATION),
                suppliedNonce = data.getString(contract.EXTRA_NONCE),
                suppliedModel = data.getString(contract.EXTRA_MODEL),
                suppliedProcess = data.getString(contract.EXTRA_PROCESS),
                suppliedUid = data.getInt(contract.EXTRA_UID, -1),
            )
        val releaseAccepted = msg.what == contract.WHAT_FAKE_CHILD_RELEASE_ACK &&
            Stage17P2M4BParentReleasePolicy.acceptsChildReleaseAck(
                packageName = packageName,
                armed = armed,
                childReady = childReadyAccepted,
                terminalReported = terminalReported,
                childReleaseSent = childReleaseSent,
                alreadyAccepted = childReleaseAccepted,
                actualUid = Process.myUid(),
                sendingUid = msg.sendingUid,
                generation = generation,
                nonce = nonce,
                suppliedGeneration = data.getLong(contract.EXTRA_GENERATION),
                suppliedNonce = data.getString(contract.EXTRA_NONCE),
                suppliedModel = data.getString(contract.EXTRA_MODEL),
                suppliedProcess = data.getString(contract.EXTRA_PROCESS),
                suppliedUid = data.getInt(contract.EXTRA_UID, -1),
            )
        if (accepted) {
            childReadyAccepted = true
            report(contract.WHAT_CHILD_READY)
        } else if (releaseAccepted) {
            childReleaseAccepted = true
            report(contract.WHAT_FAKE_CHILD_RELEASE_ACK)
        } else if (armed) {
            // A malformed, duplicate or stale reply must poison the Controller.
            report(contract.WHAT_DENIED)
        }
        true
    })

    private val childConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!armed) return
            if (binder == null || child != null || childAdmissionSent ||
                name?.className != Stage17P2M4BChildService::class.java.name
            ) {
                report(contract.WHAT_DENIED)
                return
            }
            childAdmissionSent = true
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
            if (!parentReleaseSent) report(contract.WHAT_DENIED)
        }
    }

    private fun matchesController(msg: Message): Boolean {
        val data = msg.data
        return contract.IPC_ADMISSION_ENABLED &&
            contract.isLabPackage(packageName) && armed &&
            contract.currentProcessName(this) == contract.parentProcess(packageName) &&
            msg.sendingUid == Process.myUid() &&
            data.getLong(contract.EXTRA_GENERATION) == generation &&
            data.getString(contract.EXTRA_NONCE) == nonce &&
            data.getString(contract.EXTRA_MODEL) == contract.FAKE_MODEL &&
            data.getString(contract.EXTRA_PROCESS) == packageName &&
            data.getInt(contract.EXTRA_UID, -1) == Process.myUid()
    }

    private val inbound = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            contract.WHAT_PROBE_PING -> {
                // Independent, read-only Binder liveness probe. This must never
                // arm a fake model, open owner Journal, or bind the child.
                val data = msg.data
                val token = data.getString(contract.EXTRA_NONCE)
                val valid = Stage17P2M4BProbePolicy.acceptsPing(
                    packageName = packageName,
                    processName = contract.currentProcessName(this),
                    actualUid = Process.myUid(),
                    senderUid = msg.sendingUid,
                    controllerProcess = data.getString(contract.EXTRA_PROCESS),
                    token = token,
                    model = data.getString(contract.EXTRA_MODEL),
                    parentArmed = armed,
                )
                if (valid && msg.replyTo != null) {
                    val reply = Message.obtain(null, contract.WHAT_PROBE_PONG)
                    reply.data = Bundle().apply {
                        putString(contract.EXTRA_NONCE, token)
                        putString(contract.EXTRA_MODEL, contract.FAKE_MODEL)
                        putString(contract.EXTRA_PROCESS, contract.parentProcess(packageName))
                        putInt(contract.EXTRA_UID, Process.myUid())
                        putInt(contract.EXTRA_PID, Process.myPid())
                    }
                    try { msg.replyTo?.send(reply) } catch (_: RemoteException) { }
                }
                true
            }
            contract.WHAT_ARM -> {
                val supplied = msg.data
                val incomingGeneration = supplied.getLong(contract.EXTRA_GENERATION)
                val incomingNonce = supplied.getString(contract.EXTRA_NONCE)
                val approved = contract.validIdentity(
                    packageName, Stage17P2M4BContract.currentProcessName(this), contract.PARENT_SUFFIX,
                    incomingGeneration, incomingNonce, supplied.getString(contract.EXTRA_MODEL),
                    supplied.getInt(contract.EXTRA_UID), Process.myUid(),
                )
                if (!contract.isLabPackage(packageName) ||
                    !contract.IPC_ADMISSION_ENABLED || msg.sendingUid != Process.myUid() ||
                    !approved || supplied.getString(contract.EXTRA_PROCESS) != packageName ||
                    armed || msg.replyTo == null) {
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
            contract.WHAT_FAKE_TERMINAL_REQUEST -> {
                if (matchesController(msg) &&
                    Stage17P2M4BParentReleasePolicy.mayObserveTerminal(
                        armed, childReadyAccepted, terminalReported, childReleaseSent,
                    )
                ) {
                    terminalReported = true
                    report(contract.WHAT_FAKE_TERMINAL_OBSERVED)
                } else if (armed) report(contract.WHAT_DENIED)
                true
            }
            contract.WHAT_FAKE_CHILD_RELEASE_REQUEST -> {
                if (matchesController(msg) &&
                    Stage17P2M4BParentReleasePolicy.mayRequestChildRelease(
                        armed, terminalReported, childReadyAccepted,
                        childReleaseSent, child != null,
                    )
                ) {
                    childReleaseSent = true
                    val request = Message.obtain(null, contract.WHAT_FAKE_CHILD_RELEASE_REQUEST)
                    request.data = identity()
                    request.replyTo = childReceiver
                    try {
                        child?.send(request)
                    } catch (_: RemoteException) {
                        report(contract.WHAT_DENIED)
                    }
                } else if (armed) report(contract.WHAT_DENIED)
                true
            }
            contract.WHAT_FAKE_PARENT_RELEASE_REQUEST -> {
                if (matchesController(msg) &&
                    Stage17P2M4BParentReleasePolicy.mayReleaseParent(
                        armed, childReleaseAccepted, parentReleaseSent, connected,
                    )
                ) {
                    parentReleaseSent = true
                    // Bound fake Service release, never evidence of native teardown.
                    unbindService(childConnection)
                    connected = false
                    child = null
                    report(contract.WHAT_FAKE_PARENT_RELEASE_ACK)
                    // Keep one-shot latches; a second arm cannot reuse this process.
                } else if (armed) report(contract.WHAT_DENIED)
                true
            }
            // No WHAT_EXIT in B1. Any unknown command is rejected.
            else -> true
        }
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (contract.isLabPackage(packageName) &&
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
