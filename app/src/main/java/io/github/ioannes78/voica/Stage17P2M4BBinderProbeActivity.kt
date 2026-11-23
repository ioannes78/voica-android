package io.github.ioannes78.voica

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.UUID

/**
 * Isolated .m4blab ONLY, opt-in, non-destructive Binder liveness probe.
 * No Journal/Controller, WHAT_ARM, fake model owner, child, self-exit or native work.
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
    private lateinit var start: Button

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
                    reportedProcess = data.getString(contract.EXTRA_PROCESS),
                    token = data.getString(contract.EXTRA_NONCE),
                    expectedToken = token,
                    model = data.getString(contract.EXTRA_MODEL),
                )
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
        expectedToken = UUID.randomUUID().toString()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (finished || connection !== this) return
                if (name?.packageName != packageName ||
                    name.className != Stage17P2M4BParentService::class.java.name ||
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
        ui.postDelayed({
            if (!finished) complete("DENY: Binder PONG timed out")
        }, 10_000L)
    }

    private fun complete(verdict: String) {
        if (finished) return
        finished = true
        status.text = verdict
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
