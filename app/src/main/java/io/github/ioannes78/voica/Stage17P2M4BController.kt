package io.github.ioannes78.voica

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.util.UUID

/**
 * P2-M4B1 main-process controller broker. All journal operations run on one worker.
 * No clean seed is offered; on existing/untrusted installs admission is blocked.
 * No fault injection, no native model, no process termination, no automatic retry.
 */
internal class Stage17P2M4BController(
    private val context: Context,
    private val onReport: (String) -> Unit,
) {
    private val c = Stage17P2M4BContract
    private val worker = HandlerThread("stage17-m4b-journal").apply { start() }
    private val handler = Handler(worker.looper)
    private val journal = Stage17P2M4BControllerJournal(context)
    private var connection: ServiceConnection? = null
    private var remote: Messenger? = null
    private var deathBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var active: Stage17P2M4BRecord? = null
    private var closed = false
    private var parentReady = false
    private var childReady = false
    private val ipcTimeoutMs = 15_000L

    private val incoming = Messenger(Handler(worker.looper) { message ->
        if (!closed) onCallback(message)
        true
    })

    private fun mainProcess(): Boolean =
        c.allowPackage(context.packageName) &&
            Stage17P2M4BContract.currentProcessName(context) == context.packageName

    private fun report(message: String) {
        // Callback is diagnostic only; never use it as ownership authority.
        onReport("P2-M4B1 SAFE MOCK | $message")
    }

    fun readOnlyDryRun() {
        handler.post {
            if (!mainProcess()) {
                report("DENY_WRONG_PROCESS")
            } else {
                val state = journal.recovered()
                report("DRY_RUN_ONLY / " +
                    Stage17P2M4BCodec.recoveryVerdict(state) +
                    " / admissionEnabled=${c.IPC_ADMISSION_ENABLED}" +
                    " / faultEnabled=${c.FAULT_INJECTION_ENABLED}")
            }
        }
    }

    /** No usable seed exists in B1; never treat absence as a clean install. */
    fun requestAdmission() {
        handler.post {
            if (closed || !mainProcess()) {
                report("DENY_WRONG_PROCESS_OR_CLOSED")
                return@post
            }
            val allowed = Stage17P2M4BAdmissionPolicy.canAdmit(
                appId = context.packageName,
                processName = Stage17P2M4BContract.currentProcessName(context),
                ipcAdmissionEnabled = c.IPC_ADMISSION_ENABLED,
                // B1 does not yet have independent, device-bound enrollment
                // attestation. In-app journal state or host JSON cannot grant it.
                trustedEnrollment = false,
                ownerRecord = journal.recovered(),
            )
            if (!allowed) {
                report("DENY_NO_TRUSTED_LAB_ENROLLMENT_OR_IPC_DISABLED")
                return@post
            }
            if (connection != null || active != null) {
                report("DENY_ALREADY_ACTIVE")
                return@post
            }
            val record = journal.claim(UUID.randomUUID().toString())
            if (record == null) {
                report("DENY_NO_DURABLE_OWNER_AUTHORIZATION")
                return@post
            }
            active = record
            parentReady = false
            childReady = false
            val armedGeneration = record.generation
            val armedNonce = record.nonce
            handler.postDelayed({
                val observed = active
                if (!closed && observed?.generation == armedGeneration &&
                    observed.nonce == armedNonce &&
                    observed.phase != Stage17P2M4BPhase.UNRESOLVED_POISONED
                ) poison("IPC_TIMEOUT_UNRESOLVED")
            }, ipcTimeoutMs)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    handler.post {
                        if (closed || binder == null ||
                            name?.className != Stage17P2M4BParentService::class.java.name
                        ) {
                            poison("BIND_IDENTITY_UNCERTAIN")
                            return@post
                        }
                        if (connection !== this) return@post
                        try {
                            val death = IBinder.DeathRecipient {
                                handler.post { poison("PARENT_BINDER_DIED") }
                            }
                            binder.linkToDeath(death, 0)
                            deathBinder = binder
                            deathRecipient = death
                            remote = Messenger(binder)
                            val rec = active ?: return@post poison("NO_ACTIVE_ATTEMPT")
                            val bound = journal.transition(
                                rec.generation, rec.nonce, Stage17P2M4BPhase.PARENT_BOUND,
                            ) ?: return@post poison("PARENT_BOUND_COMMIT_FAILED")
                            active = bound
                            val command = Message.obtain(null, c.WHAT_ARM)
                            command.data = identity(bound)
                            command.replyTo = incoming
                            remote?.send(command)
                            report("PARENT_BOUND_DURABLE")
                        } catch (_: RemoteException) {
                            poison("PARENT_IPC_FAILED")
                        } catch (_: Exception) {
                            poison("PARENT_BINDER_SETUP_FAILED")
                        }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    handler.post { poison("PARENT_CONNECTION_LOST_UNRESOLVED") }
                }
                override fun onBindingDied(name: ComponentName?) {
                    handler.post { poison("PARENT_BINDING_DIED") }
                }
                override fun onNullBinding(name: ComponentName?) {
                    handler.post { poison("PARENT_NULL_BINDING") }
                }
            }
            connection = conn
            val bound = try {
                context.bindService(
                    Intent(context, Stage17P2M4BParentService::class.java),
                    conn, Context.BIND_AUTO_CREATE,
                )
            } catch (_: Exception) {
                false
            }
            if (!bound) poison("PARENT_BIND_REQUEST_FAILED")
        }
    }

    private fun sendFakeCommand(what: Int, record: Stage17P2M4BRecord): Boolean {
        // Journal transition must be synchronously committed before command send.
        val recipient = remote ?: return false
        val message = Message.obtain(null, what)
        message.data = identity(record)
        return try {
            recipient.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun identity(record: Stage17P2M4BRecord) = Bundle().apply {
        putLong(c.EXTRA_GENERATION, record.generation)
        putString(c.EXTRA_NONCE, record.nonce)
        putString(c.EXTRA_MODEL, c.FAKE_MODEL)
        putString(c.EXTRA_PROCESS, context.packageName)
        putInt(c.EXTRA_UID, Process.myUid())
        putInt(c.EXTRA_PID, Process.myPid())
    }

    private fun onCallback(message: Message) {
        val rec = active ?: return
        val data = message.data
        if (message.sendingUid != Process.myUid() ||
            data.getLong(c.EXTRA_GENERATION) != rec.generation ||
            data.getString(c.EXTRA_NONCE) != rec.nonce ||
            data.getString(c.EXTRA_MODEL) != c.FAKE_MODEL ||
            data.getString(c.EXTRA_PROCESS) != c.parentProcess(context.packageName) ||
            data.getInt(c.EXTRA_UID, -1) != Process.myUid()
        ) {
            poison("CALLBACK_IDENTITY_MISMATCH")
            return
        }
        when (message.what) {
            c.WHAT_PARENT_READY -> {
                if (parentReady || rec.phase != Stage17P2M4BPhase.PARENT_BOUND) {
                    poison("DUPLICATE_OR_OUT_OF_ORDER_PARENT_READY")
                } else {
                    parentReady = true
                    report("PARENT_CALLBACK_VALIDATED")
                }
            }
            c.WHAT_CHILD_READY -> {
                if (!parentReady || childReady || rec.phase != Stage17P2M4BPhase.PARENT_BOUND) {
                    poison("DUPLICATE_OR_OUT_OF_ORDER_CHILD_READY")
                    return
                }
                val next = journal.transition(
                    rec.generation, rec.nonce, Stage17P2M4BPhase.CHILD_ADMITTED,
                )
                if (next != null) {
                    active = next
                    childReady = true
                    report("CHILD_ADMITTED_DURABLE / FAKE_ONLY")
                    val running = journal.transition(
                        next.generation, next.nonce, Stage17P2M4BPhase.FAKE_OWNER_ACTIVE,
                    )
                    if (running == null) {
                        poison("FAKE_OWNER_ACTIVE_COMMIT_FAILED")
                    } else {
                        active = running
                        if (!sendFakeCommand(c.WHAT_FAKE_TERMINAL_REQUEST, running)) {
                            poison("FAKE_TERMINAL_REQUEST_IPC_FAILED")
                        } else report("FAKE_OWNER_ACTIVE_DURABLE")
                    }
                } else poison("CHILD_ADMISSION_COMMIT_FAILED_OR_OUT_OF_ORDER")
            }
            c.WHAT_FAKE_TERMINAL_OBSERVED -> {
                if (!parentReady || !childReady ||
                    rec.phase != Stage17P2M4BPhase.FAKE_OWNER_ACTIVE
                ) return poison("FAKE_TERMINAL_OUT_OF_ORDER")
                val terminal = journal.transition(
                    rec.generation, rec.nonce, Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED,
                ) ?: return poison("FAKE_TERMINAL_COMMIT_FAILED")
                active = terminal
                if (!sendFakeCommand(c.WHAT_FAKE_CHILD_RELEASE_REQUEST, terminal)) {
                    poison("FAKE_CHILD_RELEASE_REQUEST_IPC_FAILED")
                } else report("TERMINAL_FAKE_OBSERVED_DURABLE")
            }
            c.WHAT_FAKE_CHILD_RELEASE_ACK -> {
                if (rec.phase != Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED) {
                    return poison("CHILD_RELEASE_ACK_OUT_OF_ORDER")
                }
                val ack = journal.transition(
                    rec.generation, rec.nonce, Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED,
                ) ?: return poison("CHILD_RELEASE_ACK_COMMIT_FAILED")
                active = ack
                if (!sendFakeCommand(c.WHAT_FAKE_PARENT_RELEASE_REQUEST, ack)) {
                    poison("PARENT_RELEASE_REQUEST_IPC_FAILED")
                } else report("CHILD_RELEASE_ACK_FAKE_DURABLE")
            }
            c.WHAT_FAKE_PARENT_RELEASE_ACK -> {
                if (rec.phase != Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED) {
                    return poison("PARENT_RELEASE_ACK_OUT_OF_ORDER")
                }
                val ack = journal.transition(
                    rec.generation, rec.nonce, Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED,
                ) ?: return poison("PARENT_RELEASE_ACK_COMMIT_FAILED")
                active = ack
                val released = journal.transition(
                    ack.generation, ack.nonce, Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
                ) ?: return poison("FAKE_RELEASE_CONFIRM_COMMIT_FAILED")
                report("RELEASE_CONFIRMED_DURABLE / FAKE_ONLY / NO_NATIVE_PROOF")
                active = null
                detach()
            }
            else -> poison("UNEXPECTED_PARENT_CALLBACK")
        }
    }

    private fun poison(reason: String) {
        val rec = active
        if (rec != null) {
            val persisted = journal.transition(
                rec.generation, rec.nonce, Stage17P2M4BPhase.UNRESOLVED_POISONED,
            )
            if (persisted != null) active = persisted
            report("$reason / " + if (persisted != null) "POISON_DURABLE" else "POISON_WRITE_UNCERTAIN_DENY")
        } else report("$reason / DENY")
        detach()
        // An unresolved durable attempt must never be automatically replayed.
    }

    private fun detach() {
        val binder = deathBinder
        val death = deathRecipient
        if (binder != null && death != null) binder.unlinkToDeath(death, 0)
        deathBinder = null
        deathRecipient = null
        remote = null
        val conn = connection
        connection = null
        if (conn != null) {
            try { context.unbindService(conn) } catch (_: IllegalArgumentException) { }
        }
    }

    fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            if (active != null &&
                active?.phase != Stage17P2M4BPhase.UNRESOLVED_POISONED
            ) poison("CONTROLLER_CLOSED_WHILE_ARMED")
            detach()
            worker.quitSafely()
        }
    }
}
