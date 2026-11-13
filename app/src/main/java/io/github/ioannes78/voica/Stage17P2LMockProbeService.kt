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
    private var activeToken: Int = 0
    private var scenario: Stage17P2LMockScenario? = null
    private var parent: Messenger? = null
    private var fakeTerminal: Boolean = false

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
        activeToken = message.arg1
        scenario = selected
        parent = reply
        fakeTerminal = false

        mockHandler.post {
            sendFake(Stage17P2LMockPhase.MODEL_INITIALIZING)
            sendFake(Stage17P2LMockPhase.MODEL_READY)
            sendFake(Stage17P2LMockPhase.CONVERSATION_ENTERED)
            sendFake(Stage17P2LMockPhase.CONVERSATION_READY)
            sendFake(Stage17P2LMockPhase.SEND_ENTERED)

            when (selected) {
                Stage17P2LMockScenario.SUBMISSION_NOT_RETURNED -> {
                    // Deliberately stop reporting. No thread is actually blocked.
                }
                Stage17P2LMockScenario.CALLBACK_BEFORE_RETURN -> {
                    sendFake(Stage17P2LMockPhase.FIRST_EMISSION)
                    sendFake(Stage17P2LMockPhase.SEND_RETURNED)
                    sendFake(Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED)
                    mockHandler.postDelayed({ finishFakeNormally() }, 40L)
                }
                Stage17P2LMockScenario.NORMAL,
                Stage17P2LMockScenario.TERMINAL_MISSING,
                Stage17P2LMockScenario.CANCEL_ACKNOWLEDGED,
                Stage17P2LMockScenario.CANCEL_UNACKNOWLEDGED,
                -> {
                    sendFake(Stage17P2LMockPhase.SEND_RETURNED)
                    sendFake(Stage17P2LMockPhase.TERMINAL_AWAIT_ENTERED)
                    when (selected) {
                        Stage17P2LMockScenario.NORMAL ->
                            mockHandler.postDelayed({
                                sendFake(Stage17P2LMockPhase.FIRST_EMISSION)
                                finishFakeNormally()
                            }, 40L)
                        Stage17P2LMockScenario.TERMINAL_MISSING ->
                            mockHandler.postDelayed({
                                sendFake(Stage17P2LMockPhase.FIRST_EMISSION)
                                // No terminal (simulated), parent must report timeout.
                            }, 40L)
                        else -> Unit // Only explicit fake cancel causes an acknowledgment.
                    }
                }
            }
        }
    }

    private fun cancelMockRun(message: Message) {
        if (activeToken == 0 || message.arg1 != activeToken || fakeTerminal) return
        mockHandler.post {
            sendFake(Stage17P2LMockPhase.CANCEL_SIGNAL_SENT)
            if (scenario == Stage17P2LMockScenario.CANCEL_ACKNOWLEDGED) {
                sendFake(Stage17P2LMockPhase.TERMINAL_ERROR)
                sendFake(Stage17P2LMockPhase.CANCEL_ACKNOWLEDGED)
                sendFake(Stage17P2LMockPhase.RELEASE_CONFIRMED)
                fakeTerminal = true
            }
            // CANCEL_UNACKNOWLEDGED intentionally emits no acknowledgment.
        }
    }

    private fun finishFakeNormally() {
        if (fakeTerminal) return
        sendFake(Stage17P2LMockPhase.TERMINAL_DONE)
        sendFake(Stage17P2LMockPhase.RELEASE_CONFIRMED)
        fakeTerminal = true
    }

    private fun sendFake(phase: Stage17P2LMockPhase) {
        val recipient = parent ?: return
        val token = activeToken
        if (token <= 0) return
        val event =
            Message.obtain(
                null,
                Stage17P2LMockContract.WHAT_EVENT,
                token,
                phase.ordinal,
            )
        event.data =
            Bundle().apply {
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

    override fun onDestroy() {
        mockThread.quitSafely()
        parent = null
        activeToken = 0
        super.onDestroy()
    }
}
