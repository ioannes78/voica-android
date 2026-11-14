package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private val journalPrefs =
        app.getSharedPreferences("stage17_p2m1_mock_owner_journal", Context.MODE_PRIVATE)
    private val priorRecovery =
        Stage17P2M1MockJournal.restore(journalPrefs.getString("owner", null), app.packageName)
    private var ownerRecord: Stage17P2M1MockOwnerRecord? = priorRecovery.record
    private var ownerBlocked = priorRecovery.blocked
    private var ownerDurableState = priorRecovery.reason
    private var releaseCommitPending = false
    private val tokenRandom = SecureRandom()
    private var attemptNonce: String? = null
    private var childPid = 0
    private var childExitCorrelation = "NOT_CHECKED"
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { work ->
        Thread(work, "stage17-p2l-mock-parent-write").apply { isDaemon = true }
    }
    private val _report = MutableStateFlow(
        prefs.getString("last", "P2-L MOCK ONLY · 尚无模拟记录").orEmpty() +
            "\np2m1OwnerRecovery=" + priorRecovery.reason +
            "\np2m1OwnerBlocked=" + priorRecovery.blocked,
    )
    val report: StateFlow<String> = _report.asStateFlow()
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
        if (connection != null || ledger.isBlocked() || ownerBlocked ||
            releaseCommitPending || !qaOwnerAdmission.compareAndSet(false, true)
        ) return false

        val lastGeneration = ownerRecord?.generation ?: 0L
        if (lastGeneration == Long.MAX_VALUE) {
            ownerBlocked = true
            return false
        }
        var token = tokenRandom.nextInt(Int.MAX_VALUE - 1) + 1
        while (token == ownerRecord?.token) {
            token = tokenRandom.nextInt(Int.MAX_VALUE - 1) + 1
        }
        val nonce = UUID.randomUUID().toString()
        val armedRecord = Stage17P2M1MockOwnerRecord(
            generation = lastGeneration + 1L,
            token = token,
            nonce = nonce,
            armEpochMs = System.currentTimeMillis(),
            processName = Stage17P2LMockContract.expectedProcess(app.packageName),
            state = Stage17P2M1MockOwnerState.ARMED,
        )
        ownerRecord = armedRecord
        attemptNonce = nonce
        childPid = 0
        childExitCorrelation = "NOT_CHECKED"
        ownerDurableState = "ARM_COMMIT_PENDING"
        if (!ledger.arm(token)) {
            ownerBlocked = true
            return false
        }
        runningToken = token
        selectedScenario = scenario
        val initial = formatReport()
        val durable =
            withContext(Dispatchers.IO) {
                runCatching {
                    writer.submit<Boolean> {
                        val committed = journalPrefs.edit().putString(
                            "owner", Stage17P2M1MockJournal.encode(armedRecord),
                        ).commit()
                        committed && prefs.edit().putString("last", initial).commit()
                    }.get()
                }.getOrDefault(false)
            }
        if (!durable || closed) {
            ownerBlocked = true
            ownerDurableState = "ARM_COMMIT_FAILED_NO_BIND"
            record(token, Stage17P2LMockPhase.BIND_FAILED)
            return false
        }
        ownerDurableState = "ARMED_DURABLE"
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
                    request.data = android.os.Bundle().apply {
                        putString(Stage17P2M1MockJournal.EXTRA_NONCE, nonce)
                    }
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
        val actual = manager.runningAppProcesses?.firstOrNull { it.pid == pid }
        val actualName = actual?.processName
        if (!Stage17P2LMockContract.matchesChild(
                app.packageName, reportedName, pid, actualName,
            ) || actual?.uid != app.applicationInfo.uid ||
            message.data.getString(Stage17P2M1MockJournal.EXTRA_NONCE) != attemptNonce
        ) {
            fail(token, Stage17P2LMockPhase.CHILD_IDENTITY_UNCONFIRMED)
            return
        }
        childPid = pid
        if (record(token, phase) && phase == Stage17P2LMockPhase.RELEASE_CONFIRMED) {
            if (!ledger.isPoisoned() &&
                Stage17P2M1MockCompletionPolicy.canCommit(ledger.snapshot())
            ) {
                finishDurableRelease(token)
            } else {
                ownerBlocked = true
                ownerDurableState = "INVALID_RELEASE_SEQUENCE"
                releaseConnection()
                persistPoison()
            }
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
        if (!ledger.canAccept(token)) return
        ownerBlocked = true
        ownerDurableState = "UNRESOLVED_PENDING"
        record(token, phase)
        releaseConnection()
        persistPoison()
        if (phase == Stage17P2LMockPhase.BINDER_DIED) observeHistoricalChildExit()
    }

    /**
     * The event and fake RELEASE_CONFIRMED are observations, NOT a durable owner release.
     * Only the explicit journal commit can re-enable a later MOCK request.
     */
    private fun finishDurableRelease(token: Int) {
        releaseCommitPending = true
        ownerDurableState = "RELEASE_COMMIT_PENDING"
        releaseConnection()
        val record = ownerRecord ?: run {
            ownerBlocked = true
            return
        }
        val confirmed = record.copy(state = Stage17P2M1MockOwnerState.RELEASE_CONFIRMED)
        writer.execute {
            val committed = runCatching {
                journalPrefs.edit().putString(
                    "owner", Stage17P2M1MockJournal.encode(confirmed),
                ).commit()
            }.getOrDefault(false)
            main.post {
                if (committed) {
                    ownerRecord = confirmed
                    ownerDurableState = "RELEASE_CONFIRMED_DURABLE"
                    releaseCommitPending = false
                    qaOwnerAdmission.set(false)
                } else {
                    ownerBlocked = true
                    ownerDurableState = "RELEASE_COMMIT_FAILED"
                    releaseCommitPending = false
                }
                if (!closed) _report.value = formatReport()
            }
        }
    }

    private fun persistPoison() {
        val record = ownerRecord ?: return
        val poisoned = record.copy(state = Stage17P2M1MockOwnerState.UNRESOLVED_POISONED)
        writer.execute {
            val committed = runCatching {
                journalPrefs.edit().putString(
                    "owner", Stage17P2M1MockJournal.encode(poisoned),
                ).commit()
            }.getOrDefault(false)
            main.post {
                if (committed) {
                    ownerRecord = poisoned
                    ownerDurableState = "UNRESOLVED_POISON_DURABLE"
                } else {
                    // ARMED was already durable; restart will still deny the mock attempt.
                    ownerDurableState = "POISON_COMMIT_FAILED_ARMED_STILL_BLOCKS"
                }
                if (!closed) _report.value = formatReport()
            }
        }
    }

    /**
     * Exit info is metadata only; absent/ambiguous matches remain UNKNOWN.
     * This does not fetch raw traces or attribute GPU faults to any component.
     */
    private fun observeHistoricalChildExit() {
        val record = ownerRecord ?: return
        val pid = childPid
        if (pid <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            childExitCorrelation = "UNKNOWN"
            return
        }
        writer.execute {
            val now = System.currentTimeMillis()
            val match = runCatching {
                val manager = app.getSystemService(ActivityManager::class.java)
                val history = manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
                Stage17P2M1MockExitCorrelator.classify(
                    candidates = history.map { info ->
                        Stage17P2M1MockExitRecord(
                            processName = info.processName,
                            pid = info.pid,
                            uid = info.realUid,
                            exitEpochMs = info.timestamp,
                            reason = info.reason,
                        )
                    },
                    expectedProcess = record.processName,
                    childPid = pid,
                    appUid = app.applicationInfo.uid,
                    armEpochMs = record.armEpochMs,
                    observedAtEpochMs = now,
                )
            }.getOrDefault(Stage17P2M1MockExitCorrelator.Verdict.UNKNOWN)
            main.post {
                childExitCorrelation = match.name
                if (!closed) _report.value = formatReport()
            }
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
        appendLine("poisoned=${ledger.isPoisoned() || ownerBlocked}")
        appendLine("p2m1OwnerDurability=$ownerDurableState")
        appendLine("p2m1OwnerBlocked=$ownerBlocked")
        appendLine("p2m1ReleaseCommitPending=$releaseCommitPending")
        appendLine("p2m1ChildExitCorrelation=$childExitCorrelation")
        val phases = ledger.snapshot()
        appendLine("eventCount=${phases.size}")
        phases.forEach { event -> appendLine("+${event.elapsedMs}ms ${event.phase.name}") }
    }.trimEnd()

    fun close() {
        if (closed) return
        if (ledger.canAccept(runningToken)) {
            ownerBlocked = true
            ownerDurableState = "ACTIVITY_CLOSED_WHILE_ACTIVE"
            record(runningToken, Stage17P2LMockPhase.RELEASE_UNCERTAIN)
            persistPoison()
        }
        closed = true
        releaseConnection()
        writer.shutdown() // No synchronous disk work on the UI or Binder callback.
    }

    private companion object {
        val qaOwnerAdmission = AtomicBoolean(false)
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
