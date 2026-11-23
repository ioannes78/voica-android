package io.github.ioannes78.voica

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.UUID

/**
 * Isolated .m4blab ONLY, opt-in, non-destructive Binder liveness probe.
 * No Journal or controller ownership command, fake model owner, child, self-exit or native work.
 * A bound mock parent may be naturally stopped by Android after unbinding.
 */
class Stage17P2M4BBinderProbeActivity : ComponentActivity() {
    private val contract = Stage17P2M4BContract
    private val ui = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var expectedToken: String? = null
    private var attempted = false
    private var finished = false
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var start: Button
    private var probeToken: String? = null
    private var parentPidObserved: Int? = null
    private var startedAtMs: Long? = null
    private val timeout = Runnable {
        if (!finished) complete("DENY: Binder PONG timed out")
    }

    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
        val token = expectedToken
        if (!finished && token != null) {
            val data = message.data
            val valid = message.what == contract.WHAT_PROBE_PONG &&
                Stage17P2M4BProbePolicy.acceptsPong(
                    packageName = packageName,
                    processName = contract.currentProcessName(this),
                    actualUid = Process.myUid(),
                    senderUid = message.sendingUid,
                    reportedUid = data.getInt(contract.EXTRA_UID, -1),
                    controllerPid = Process.myPid(),
                    reportedParentPid = data.getInt(contract.EXTRA_PID, -1),
                    reportedProcess = data.getString(contract.EXTRA_PROCESS),
                    token = data.getString(contract.EXTRA_NONCE),
                    expectedToken = token,
                    model = data.getString(contract.EXTRA_MODEL),
                )
            if (valid) parentPidObserved = data.getInt(contract.EXTRA_PID, -1)
            complete(if (valid) "PASS: isolated parent Binder PONG identity verified" else
                "DENY: invalid, stale, or unexpected Binder callback")
        }
        true
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!contract.isLabPackage(packageName) ||
            contract.currentProcessName(this) != packageName
        ) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "P2-M4B1 — ISOLATED BINDER LIVENESS"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "User-triggered PING/PONG only. No owner admission, Journal write, child bind, process kill, JNI or GPU. A successful PONG proves only observed Binder communication, not ownership release or trusted enrollment."
        })
        status = TextView(this).apply { text = "NOT RUN — no process has been bound" }
        root.addView(status)
        details = TextView(this).apply {
            text = "No test report yet. No service bound."
            setTextIsSelectable(true)
        }
        root.addView(details)
        root.addView(Button(this).apply {
            text = "Copy local probe report"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(
                    "P2-M4B1 Binder probe report", details.text,
                ))
            }
        })
        start = Button(this).apply {
            text = "Run one read-only Binder PING"
            setOnClickListener { runOnce() }
        }
        root.addView(start)
        setContentView(root)
    }

    private fun runOnce() {
        if (attempted || finished || !contract.isLabPackage(packageName) ||
            contract.currentProcessName(this) != packageName
        ) return
        attempted = true
        start.isEnabled = false
        status.text = "PING in progress (no owner admission)"
        startedAtMs = System.currentTimeMillis()
        expectedToken = UUID.randomUUID().toString()
        probeToken = expectedToken
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (finished || connection !== this) return
                if (name?.packageName != packageName ||
                    name?.className != Stage17P2M4BParentService::class.java.name ||
                    binder == null
                ) {
                    complete("DENY: wrong bound Service identity")
                    return
                }
                val ping = Message.obtain(null, contract.WHAT_PROBE_PING)
                ping.data = Bundle().apply {
                    putString(contract.EXTRA_NONCE, expectedToken)
                    putString(contract.EXTRA_MODEL, contract.FAKE_MODEL)
                    putString(contract.EXTRA_PROCESS, packageName)
                }
                ping.replyTo = replyTo
                try {
                    Messenger(binder).send(ping)
                } catch (_: RemoteException) {
                    complete("DENY: parent Binder send failed")
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                complete("DENY: parent Binder disconnected")
            }
            override fun onNullBinding(name: ComponentName?) {
                complete("DENY: parent returned null Binder")
            }
            override fun onBindingDied(name: ComponentName?) {
                complete("DENY: parent binding died")
            }
        }
        connection = conn
        val bound = try {
            bindService(Intent(this, Stage17P2M4BParentService::class.java),
                conn, Context.BIND_AUTO_CREATE)
        } catch (_: RuntimeException) {
            false
        }
        if (!bound) {
            // A failed bind is not a proof that a parent process never ran.
            complete("DENY: unable to bind isolated parent")
            return
        }
        ui.postDelayed(timeout, 10_000L)
    }

    private fun complete(verdict: String) {
        if (finished) return
        finished = true
        ui.removeCallbacks(timeout)
        if (::status.isInitialized) status.text = verdict
        if (::details.isInitialized) details.text = buildString {
            appendLine("P2-M4B1_LAB_BINDER_PROBE_V1")
            appendLine("verdict=" + verdict)
            appendLine("package=" + packageName)
            appendLine("controller_pid=" + Process.myPid())
            appendLine("parent_pid=" + (parentPidObserved?.toString() ?: "NOT_VERIFIED"))
            appendLine("device_api=" + Build.VERSION.SDK_INT)
            appendLine("start_epoch_ms=" + (startedAtMs?.toString() ?: "NOT_STARTED"))
            appendLine("finish_epoch_ms=" + System.currentTimeMillis())
            appendLine("probe_token=" + (probeToken ?: "NOT_STARTED"))
            appendLine("owner_admission=false")
            appendLine("journal_mutation=false")
            appendLine("fault_injection=false")
            appendLine("proof_scope=ONE_OBSERVED_BINDER_EXCHANGE_ONLY")
            appendLine("not_proven=ENROLLMENT_OR_OWNER_RELEASE_OR_PROCESS_SURVIVAL")
        }
        expectedToken = null
        val conn = connection
        connection = null
        if (conn != null) {
            try { unbindService(conn) } catch (_: IllegalArgumentException) { }
        }
    }

    override fun onDestroy() {
        complete("CLOSED: no Binder ownership inferred")
        super.onDestroy()
    }
}
