package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException

/**
 * QA-only fake IPC service. Manifest declaration exists ONLY in qa/releaseQa.
 * It cannot open a LiteRT-LM model, construct an Engine, or execute GPU/JNI code.
 * SUBMISSION_NOT_RETURNED is an event simulation, NOT a blocked native call.
 */
class Stage17P2LMockProbeService : Service() {
    private lateinit var mockThread: HandlerThread
    private lateinit var mockHandler: Handler
    @Volatile private var activeToken: Int = 0
    @Volatile private var scenario: Stage17P2LMockScenario? = null
    @Volatile private var parent: Messenger? = null
    @Volatile private var fakeTerminal: Boolean = false
    @Volatile private var attemptNonce: String? = null

    private val inbound = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            Stage17P2LMockContract.WHAT_RUN -> {
                acceptMockRun(message)
                true
            }
            Stage17P2LMockContract.WHAT_CANCEL -> {
                cancelMockRun(message)
                true
            }
            else -> false
        }
    })

    override fun onCreate() {
        super.onCreate()
        mockThread = HandlerThread("stage17-p2l-mock-events")
        mockThread.start()
        mockHandler = Handler(mockThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Fail closed even if a future manifest accidentally exposes this class.
        if (!Stage17P2LMockContract.allowPackage(packageName)) return null
        if (currentProcessName() != Stage17P2LMockContract.expectedProcess(packageName)) return null
        return inbound.binder
    }

    private fun acceptMockRun(message: Message) {
        if (!Stage17P2LMockContract.allowPackage(packageName)) return
        if (activeToken != 0 || message.arg1 <= 0) return
        val selected = Stage17P2LMockScenario.entries.getOrNull(message.arg2) ?: return
        val reply = message.replyTo ?: return
        val nonce = message.data.getString(Stage17P2M1MockJournal.EXTRA_NONCE) ?: return
        if (nonce.length != 36) return
        attemptNonce = nonce
        activeToken = message.arg1
        scenario = selected
        parent = reply
        fakeTerminal = false

        val token = message.arg1
        mockHandler.post {
            if (!matchesAttempt(token, nonce)) return@post
            fun emit(phase: Stage17P2LMockPhase) = sendFake(phase, token, nonce)
            emit(Stage17P2LMockPhase.MODEL_INITIALIZING)
            emit(Stage17P2LMockPhase.MODEL_READY)
            emit(Stage17P2LMockPhase.CONVERSATION_ENTERED)
            emit(Stage17P2LMockPhase.CONVERSATION_READY)
            emit(Stage17P2LMockPhase.SEND_ENTERED)

            when (selected) {
                Stage17P2LMockScenario.CHILD_PROCESS_SELF_EXIT_AFTER_SEND -> {
                    // This is the ONLY path permitted to deliberately terminate the
                    // private QA :stage17_mock_probe child; NO JNI/GPU is loaded.
                    // Allow queued fake SEND_ENTERED to reach the parent first.
                    mockHandler.postDelayed({
                        if (Stage17P2M3MockDeathGuard.canSelfTerminate(
                                packageName, currentProcessName(),
                                selected, matchesAttempt(token, nonce),
                            )
                        ) {
                            Process.killProcess(Process.myPid())
                        }
                    }, 180L)
                }
                Stage17P2LMockScenario.SUBMISSION_NOT_RETURNED -> {
                    // Deliberately stop reporting. No thread is actually blocked.
                }
                Stage17P2LMockScenario.CALLBACK_BEFORE_RETURN -> {
                    emit(Stage17P2LMockPhase.FIRST_EMISSION)
                    emit(Stage17P2LMockPhase.SEND_RETURNED)
                    emit(Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED)
                    mockHandler.postDelayed({ finishFakeNormally(token, nonce) }, 40L)
                }
                Stage17P2LMockScenario.NORMAL,
                Stage17P2LMockScenario.TERMINAL_MISSING,
                Stage17P2LMockScenario.CANCEL_ACKNOWLEDGED,
                Stage17P2LMockScenario.CANCEL_UNACKNOWLEDGED,
                -> {
                    emit(Stage17P2LMockPhase.SEND_RETURNED)
                    emit(Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED)
                    when (selected) {
                        Stage17P2LMockScenario.NORMAL ->
                            mockHandler.postDelayed({
                                emit(Stage17P2LMockPhase.FIRST_EMISSION)
                                finishFakeNormally(token, nonce)
                            }, 40L)
                        Stage17P2LMockScenario.TERMINAL_MISSING ->
                            mockHandler.postDelayed({
                                emit(Stage17P2LMockPhase.FIRST_EMISSION)
                                // No terminal (simulated), parent must report timeout.
                            }, 40L)
                        else -> Unit // Only explicit fake cancel causes an acknowledgment.
                    }
                }
            }
        }
    }

    private fun cancelMockRun(message: Message) {
        val token = message.arg1
        val nonce = attemptNonce ?: return
        if (!matchesAttempt(token, nonce) || fakeTerminal) return
        mockHandler.post {
            if (!matchesAttempt(token, nonce)) return@post
            sendFake(Stage17P2LMockPhase.CANCEL_SIGNAL_SENT, token, nonce)
            if (scenario == Stage17P2LMockScenario.CANCEL_ACKNOWLEDGED) {
                sendFake(Stage17P2LMockPhase.TERMINAL_ERROR, token, nonce)
                sendFake(Stage17P2LMockPhase.CANCEL_ACKNOWLEDGED, token, nonce)
                sendFake(Stage17P2LMockPhase.RELEASE_CONFIRMED, token, nonce)
                if (matchesAttempt(token, nonce)) fakeTerminal = true
            }
            // CANCEL_UNACKNOWLEDGED intentionally emits no acknowledgment.
        }
    }

    private fun matchesAttempt(token: Int, nonce: String): Boolean =
        Stage17P2M2MockCallbackGuard.matches(
            queuedToken = token,
            queuedNonce = nonce,
            liveToken = activeToken,
            liveNonce = attemptNonce,
        )

    private fun finishFakeNormally(token: Int, nonce: String) {
        if (!matchesAttempt(token, nonce) || fakeTerminal) return
        sendFake(Stage17P2LMockPhase.TERMINAL_DONE, token, nonce)
        sendFake(Stage17P2LMockPhase.RELEASE_CONFIRMED, token, nonce)
        if (matchesAttempt(token, nonce)) fakeTerminal = true
    }

    private fun sendFake(phase: Stage17P2LMockPhase, token: Int, nonce: String) {
        if (!matchesAttempt(token, nonce)) return
        val recipient = parent ?: return
        val event =
            Message.obtain(
                null,
                Stage17P2LMockContract.WHAT_EVENT,
                token,
                phase.ordinal,
            )
        event.data =
            Bundle().apply {
                putString(Stage17P2M1MockJournal.EXTRA_NONCE, nonce)
                putInt(Stage17P2LMockContract.EXTRA_PID, Process.myPid())
                putString(Stage17P2LMockContract.EXTRA_PROCESS, currentProcessName())
            }
        try {
            recipient.send(event)
        } catch (_: RemoteException) {
            // Parent can die independently. Do not retry or invoke any native cleanup.
        }
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName.orEmpty()
        }

    // Fake-only unbind resets the previous attempt token. This does not
    // demonstrate native resource cleanup: no native work occurs here.
    override fun onUnbind(intent: Intent?): Boolean {
        parent = null
        activeToken = 0
        attemptNonce = null
        scenario = null
        fakeTerminal = false
        return false
    }

    override fun onDestroy() {
        mockThread.quitSafely()
        parent = null
        activeToken = 0
        attemptNonce = null
        super.onDestroy()
    }
}
