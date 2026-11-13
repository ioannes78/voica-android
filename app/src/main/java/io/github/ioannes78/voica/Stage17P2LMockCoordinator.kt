package io.github.ioannes78.voica

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * QA-only parent owner of a simulated remote Service. Child sends ONLY scalar enum events
 * and its PID/process name, never prompt, output, media, raw trace or arbitrary file path.
 * Neither this class nor its companion Service owns a native LLM runtime.
 */
internal class Stage17P2LMockCoordinator(context: Context) {
    private val app = context.applicationContext
    private val prefs =
        app.getSharedPreferences("stage17_p2l_mock_parent_evidence", Context.MODE_PRIVATE)
    private val ledger = Stage17P2LMockLedger()
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { work ->
        Thread(work, "stage17-p2l-mock-parent-write").apply { isDaemon = true }
    }
    private val _report = MutableStateFlow(
        prefs.getString("last", "P2-L MOCK ONLY · 尚无模拟记录").orEmpty(),
    )
    val report: StateFlow<String> = _report.asStateFlow()
    private var nextToken = 1
    private var connection: ServiceConnection? = null
    private var remote: Messenger? = null
    private var runningToken = 0
    private var selectedScenario: Stage17P2LMockScenario? = null
    private var deadline: Runnable? = null
    private var closed = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Stage17P2LMockContract.WHAT_EVENT) {
            onChildEvent(message)
            true
        } else {
            false
        }
    })

    /** Durable parent arm is completed on an IO worker BEFORE any bind attempt. */
    suspend fun start(scenario: Stage17P2LMockScenario): Boolean {
        if (closed || !Stage17P2LMockContract.allowPackage(app.packageName)) return false
        if (connection != null || ledger.isBlocked()) return false

        val token = nextToken++
        if (!ledger.arm(token)) return false
        runningToken = token
        selectedScenario = scenario
        val initial = formatReport()
        val durable =
            withContext(Dispatchers.IO) {
                runCatching {
                    writer.submit<Boolean> {
                        prefs.edit().putString("last", initial).commit()
                    }.get()
                }.getOrDefault(false)
            }
        if (!durable || closed) {
            record(token, Stage17P2LMockPhase.BIND_FAILED)
            return false
        }
        _report.value = initial
        record(token, Stage17P2LMockPhase.BIND_REQUESTED)

        val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!ledger.canAccept(token) || name?.className !=
                        Stage17P2LMockProbeService::class.java.name || binder == null
                    ) {
                        fail(token, Stage17P2LMockPhase.BIND_FAILED)
                        return
                    }
                    remote = Messenger(binder)
                    record(token, Stage17P2LMockPhase.CHILD_BOUND)
                    val request =
                        Message.obtain(
                            null,
                            Stage17P2LMockContract.WHAT_RUN,
                            token,
                            scenario.ordinal,
                        )
                    request.replyTo = replies
                    try {
                        remote?.send(request)
                    } catch (_: RemoteException) {
                        fail(token, Stage17P2LMockPhase.BINDER_DIED)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    fail(token, Stage17P2LMockPhase.BINDER_DIED)
                }

                override fun onBindingDied(name: ComponentName?) {
                    fail(token, Stage17P2LMockPhase.BINDER_DIED)
                }

                override fun onNullBinding(name: ComponentName?) {
                    fail(token, Stage17P2LMockPhase.BIND_FAILED)
                }
            }

        connection = serviceConnection
        val bound = runCatching {
            app.bindService(
                Intent(app, Stage17P2LMockProbeService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            fail(token, Stage17P2LMockPhase.BIND_FAILED)
            return false
        }
        val watchdog = Runnable {
            if (ledger.canAccept(token)) {
                fail(token, Stage17P2LMockPhase.REPORT_TIMEOUT)
            }
        }
        deadline = watchdog
        main.postDelayed(watchdog, 2_500L) // FAKE-only, report-only; never kills a JNI worker.
        return true
    }

    fun requestFakeCancel(): Boolean {
        val token = runningToken
        if (selectedScenario !in setOf(
                Stage17P2LMockScenario.CANCEL_ACKNOWLEDGED,
                Stage17P2LMockScenario.CANCEL_UNACKNOWLEDGED,
            ) || !ledger.canAccept(token)
        ) return false
        val recipient = remote ?: return false
        if (!record(token, Stage17P2LMockPhase.CANCEL_REQUESTED)) return false
        val cancel = Message.obtain(null, Stage17P2LMockContract.WHAT_CANCEL, token, 0)
        return runCatching { recipient.send(cancel); true }.getOrElse {
            fail(token, Stage17P2LMockPhase.BINDER_DIED)
            false
        }
    }

    private fun onChildEvent(message: Message) {
        val token = message.arg1
        if (closed || !ledger.canAccept(token) || token != runningToken) return
        val phase = Stage17P2LMockPhase.entries.getOrNull(message.arg2) ?: return
        if (phase !in CHILD_EMIT_PHASES) return
        val pid = message.data.getInt(Stage17P2LMockContract.EXTRA_PID, -1)
        val reportedName = message.data.getString(Stage17P2LMockContract.EXTRA_PROCESS)
        val manager = app.getSystemService(ActivityManager::class.java)
        val actualName = manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        if (!Stage17P2LMockContract.matchesChild(
                app.packageName, reportedName, pid, actualName,
            )
        ) {
            fail(token, Stage17P2LMockPhase.CHILD_IDENTITY_UNCONFIRMED)
            return
        }
        if (record(token, phase) && phase == Stage17P2LMockPhase.RELEASE_CONFIRMED) {
            releaseConnection()
        }
    }

    private fun record(token: Int, phase: Stage17P2LMockPhase): Boolean {
        val accepted = ledger.mark(token, phase)
        if (!accepted) return false
        val current = formatReport()
        _report.value = current
        if (!closed) {
            writer.execute { prefs.edit().putString("last", current).commit() }
        }
        return true
    }

    private fun fail(token: Int, phase: Stage17P2LMockPhase) {
        if (ledger.canAccept(token)) {
            record(token, phase)
            releaseConnection()
        }
    }

    private fun releaseConnection() {
        deadline?.let(main::removeCallbacks)
        deadline = null
        remote = null
        connection?.let { current ->
            runCatching { app.unbindService(current) }
        }
        connection = null
        selectedScenario = null
    }

    private fun formatReport(): String = buildString {
        appendLine("P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU")
        appendLine("nativeExecution=false")
        appendLine("poisoned=${ledger.isPoisoned()}")
        val phases = ledger.snapshot()
        appendLine("eventCount=${phases.size}")
        phases.forEach { event -> appendLine("+${event.elapsedMs}ms ${event.phase.name}") }
    }.trimEnd()

    fun close() {
        if (closed) return
        if (ledger.canAccept(runningToken)) {
            record(runningToken, Stage17P2LMockPhase.RELEASE_UNCERTAIN)
        }
        closed = true
        releaseConnection()
        writer.shutdown() // No synchronous disk work on the UI or Binder callback.
    }

    private companion object {
        val CHILD_EMIT_PHASES = setOf(
            Stage17P2LMockPhase.MODEL_INITIALIZING,
            Stage17P2LMockPhase.MODEL_READY,
            Stage17P2LMockPhase.CONVERSATION_ENTERED,
            Stage17P2LMockPhase.CONVERSATION_READY,
            Stage17P2LMockPhase.SEND_ENTERED,
            Stage17P2LMockPhase.SEND_RETURNED,
            Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED,
            Stage17P2LMockPhase.FIRST_EMISSION,
            Stage17P2LMockPhase.TERMINAL_DONE,
            Stage17P2LMockPhase.TERMINAL_ERROR,
            Stage17P2LMockPhase.CANCEL_SIGNAL_SENT,
            Stage17P2LMockPhase.CANCEL_ACKNOWLEDGED,
            Stage17P2LMockPhase.RELEASE_CONFIRMED,
            Stage17P2LMockPhase.RELEASE_UNCERTAIN,
        )
    }
}
