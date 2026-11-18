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
                    (state?.phase?.name ?: "UNTRUSTED_OR_UNSEEDED_JOURNAL") +
                    " / admissionEnabled=${c.IPC_ADMISSION_ENABLED}" +
                    " / faultEnabled=${c.FAULT_INJECTION_ENABLED}")
            }
        }
    }

    /** No usable seed exists in B1; never treat absence as a clean install. */
    fun requestAdmission() {
        handler.post {
            if (closed || !mainProcess() || !c.IPC_ADMISSION_ENABLED) {
                report("DENY_ADMISSION_DISABLED")
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
        if (data.getLong(c.EXTRA_GENERATION) != rec.generation ||
            data.getString(c.EXTRA_NONCE) != rec.nonce ||
            data.getString(c.EXTRA_MODEL) != c.FAKE_MODEL ||
            data.getString(c.EXTRA_PROCESS) != c.parentProcess(context.packageName) ||
            data.getInt(c.EXTRA_UID, -1) != Process.myUid()
        ) {
            poison("CALLBACK_IDENTITY_MISMATCH")
            return
        }
        when (message.what) {
            c.WHAT_PARENT_READY -> report("PARENT_CALLBACK_VALIDATED")
            c.WHAT_CHILD_READY -> {
                val next = journal.transition(
                    rec.generation, rec.nonce, Stage17P2M4BPhase.CHILD_ADMITTED,
                )
                if (next != null) {
                    active = next
                    report("CHILD_ADMITTED_DURABLE / FAKE_ONLY")
                } else poison("CHILD_ADMISSION_COMMIT_FAILED_OR_OUT_OF_ORDER")
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
