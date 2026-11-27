package io.github.ioannes78.voica.localllm

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R17C P2-H: JVM-only fake JNI submission/callback boundary, independent of Android/GPU.
 * Does not import com.google.ai.edge.litertlm or persist model/user content.
 *
 * The observer records only bounded, content-free phases. It is NOT connected
 * to the production LiteRtLocalLlmRuntime and is not deployed in any APK.
 */
private enum class P2HPhase {
    SEND_ENTERED,
    SEND_RETURNED,
    TERMINAL_AWAIT_ENTERED,
    FIRST_EMISSION,
    TERMINAL_DONE,
    TERMINAL_ERROR,
    CANCEL_REQUESTED,
    CANCEL_SIGNAL_SENT,
    CANCEL_ACK_TIMEOUT,
    RELEASE_BLOCKED,
}

private enum class P2HStatus {
    NOT_STARTED,
    JNI_SUBMISSION_NOT_RETURNED,
    JNI_SUBMISSION_NOT_RETURNED_BUT_CALLBACK_OBSERVED,
    SUBMITTED_AWAITING_FIRST_CALLBACK,
    SUBMITTED_AWAITING_TERMINAL_AFTER_OUTPUT,
    TERMINAL_DONE_ZERO_OUTPUT,
    TERMINAL_DONE_WITH_OUTPUT,
    TERMINAL_ERROR_ZERO_OUTPUT,
    TERMINAL_ERROR_WITH_OUTPUT,
    NATIVE_TERMINAL_UNCONFIRMED,
}

private data class P2HPhaseMark(
    val phase: P2HPhase,
    val elapsedMs: Long,
)

/**
 * Time-ordered, bounded phase evidence. No prompt, generated text, stack trace,
 * thread names, OS thread id, model path or recording identifier.
 *
 * Terminal status is judged by the observed callback, not by send-return or a
 * coroutine cancellation exception. A callback may race with SEND_RETURNED.
 */
private class P2HPhaseLedger(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val startedAt = clockNanos()
    private val marks = Collections.synchronizedList(mutableListOf<P2HPhaseMark>())
    private val emitted = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val unconfirmed = AtomicBoolean(false)

    fun mark(phase: P2HPhase) {
        synchronized(marks) {
            if (marks.size < 32) {
                marks.add(
                    P2HPhaseMark(
                        phase = phase,
                        elapsedMs = ((clockNanos() - startedAt).coerceAtLeast(0L)) / 1_000_000L,
                    ),
                )
            }
        }
    }

    fun emission() {
        if (emitted.compareAndSet(false, true)) mark(P2HPhase.FIRST_EMISSION)
    }

    fun done() {
        if (terminal.compareAndSet(false, true)) mark(P2HPhase.TERMINAL_DONE)
    }

    fun error() {
        if (terminal.compareAndSet(false, true)) mark(P2HPhase.TERMINAL_ERROR)
    }

    fun unconfirmed() {
        unconfirmed.set(true)
        mark(P2HPhase.CANCEL_ACK_TIMEOUT)
        mark(P2HPhase.RELEASE_BLOCKED)
    }

    fun phases(): List<P2HPhase> = synchronized(marks) { marks.map { it.phase } }

    fun evidence(): List<P2HPhaseMark> = synchronized(marks) { marks.toList() }

    fun status(): P2HStatus {
        val observed = phases().toSet()
        if (unconfirmed.get()) return P2HStatus.NATIVE_TERMINAL_UNCONFIRMED
        if (P2HPhase.SEND_ENTERED !in observed) return P2HStatus.NOT_STARTED
        if (P2HPhase.SEND_RETURNED !in observed) {
            return if (P2HPhase.FIRST_EMISSION in observed ||
                P2HPhase.TERMINAL_DONE in observed ||
                P2HPhase.TERMINAL_ERROR in observed
            ) {
                P2HStatus.JNI_SUBMISSION_NOT_RETURNED_BUT_CALLBACK_OBSERVED
            } else {
                P2HStatus.JNI_SUBMISSION_NOT_RETURNED
            }
        }
        val output = P2HPhase.FIRST_EMISSION in observed
        if (P2HPhase.TERMINAL_DONE in observed) {
            return if (output) P2HStatus.TERMINAL_DONE_WITH_OUTPUT
            else P2HStatus.TERMINAL_DONE_ZERO_OUTPUT
        }
        if (P2HPhase.TERMINAL_ERROR in observed) {
            return if (output) P2HStatus.TERMINAL_ERROR_WITH_OUTPUT
            else P2HStatus.TERMINAL_ERROR_ZERO_OUTPUT
        }
        return if (output) P2HStatus.SUBMITTED_AWAITING_TERMINAL_AFTER_OUTPUT
        else P2HStatus.SUBMITTED_AWAITING_FIRST_CALLBACK
    }
}

private sealed interface P2HTerminal {
    data object Done : P2HTerminal
    data object Error : P2HTerminal
}

/** A stand-in for MessageCallback without any model output or native dependency. */
private interface P2HCallback {
    fun onMessage()
    fun onDone()
    fun onError()
}

private class P2HMockSubmission {
    val ledger = P2HPhaseLedger()
    private val terminal = CompletableDeferred<P2HTerminal>()

    /** Caller controls the fake synchronous JNI submission, including a blocking lambda. */
    fun sendMessageAsync(fakeNativeSubmit: (P2HCallback) -> Unit) {
        ledger.mark(P2HPhase.SEND_ENTERED)
        fakeNativeSubmit(
            object : P2HCallback {
                override fun onMessage() = ledger.emission()

                override fun onDone() {
                    ledger.done()
                    terminal.complete(P2HTerminal.Done)
                }

                override fun onError() {
                    ledger.error()
                    terminal.complete(P2HTerminal.Error)
                }
            },
        )
        ledger.mark(P2HPhase.SEND_RETURNED)
    }

    suspend fun awaitTerminal(): P2HTerminal {
        ledger.mark(P2HPhase.TERMINAL_AWAIT_ENTERED)
        return terminal.await()
    }

    /**
     * Fake-only cancellation. Absence of a terminal acknowledgment must never
     * be reported as a successful release. No native close is attempted here.
     */
    suspend fun fakeCancel(acknowledge: Boolean) {
        ledger.mark(P2HPhase.CANCEL_REQUESTED)
        ledger.mark(P2HPhase.CANCEL_SIGNAL_SENT)
        if (acknowledge) {
            ledger.error()
            terminal.complete(P2HTerminal.Error)
        }
        if (withTimeoutOrNull(25L) { terminal.await() } == null) ledger.unconfirmed()
    }
}

class Stage17P2HMockSubmissionBoundaryTest {
    @Test fun distinguishesSynchronousJniSubmissionBlockBeforeReturn() {
        val fake = P2HMockSubmission()
        val enteredFakeJni = CountDownLatch(1)
        val releaseFakeJni = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor { job ->
            Thread(job, "P2H-mock-only").apply { isDaemon = true }
        }
        try {
            val future = worker.submit {
                fake.sendMessageAsync {
                    enteredFakeJni.countDown()
                    check(releaseFakeJni.await(2, TimeUnit.SECONDS)) {
                        "test fake submit release timed out"
                    }
                }
            }
            assertTrue(enteredFakeJni.await(1, TimeUnit.SECONDS))
            assertEquals(
                P2HStatus.JNI_SUBMISSION_NOT_RETURNED,
                fake.ledger.status(),
            )
            assertEquals(listOf(P2HPhase.SEND_ENTERED), fake.ledger.phases())
            releaseFakeJni.countDown()
            future.get(2, TimeUnit.SECONDS)
            assertEquals(P2HStatus.SUBMITTED_AWAITING_FIRST_CALLBACK, fake.ledger.status())
        } finally {
            releaseFakeJni.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun returnedSubmissionWithoutCallbackIsNotMistakenForJniBlock() = runBlocking {
        val fake = P2HMockSubmission()
        fake.sendMessageAsync { /* accepted submission but no callback */ }
        assertEquals(
            P2HStatus.SUBMITTED_AWAITING_FIRST_CALLBACK,
            fake.ledger.status(),
        )
        assertEquals(null, withTimeoutOrNull(30L) { fake.awaitTerminal() })
        assertEquals(
            listOf(
                P2HPhase.SEND_ENTERED,
                P2HPhase.SEND_RETURNED,
                P2HPhase.TERMINAL_AWAIT_ENTERED,
            ),
            fake.ledger.phases(),
        )
        // This is a test harness timeout, NOT a real-native generation timeout.
        assertEquals(P2HStatus.SUBMITTED_AWAITING_FIRST_CALLBACK, fake.ledger.status())
    }

    @Test fun callbackCanPrecedeReturnOfSubmissionWithoutFalseCompletion() = runBlocking {
        val fake = P2HMockSubmission()
        fake.sendMessageAsync { callback ->
            callback.onDone()
            assertEquals(
                P2HStatus.JNI_SUBMISSION_NOT_RETURNED_BUT_CALLBACK_OBSERVED,
                fake.ledger.status(),
            )
        }
        assertEquals(P2HTerminal.Done, fake.awaitTerminal())
        assertEquals(
            listOf(
                P2HPhase.SEND_ENTERED, P2HPhase.TERMINAL_DONE,
                P2HPhase.SEND_RETURNED, P2HPhase.TERMINAL_AWAIT_ENTERED,
            ),
            fake.ledger.phases(),
        )
        assertEquals(P2HStatus.TERMINAL_DONE_ZERO_OUTPUT, fake.ledger.status())
    }

    @Test fun foreignThreadFirstEmissionWithoutTerminalIsSeparateState() = runBlocking {
        val fake = P2HMockSubmission()
        lateinit var callback: P2HCallback
        fake.sendMessageAsync { callback = it }
        val foreign = Thread({ callback.onMessage() }, "P2H-fake-native-callback")
        foreign.start()
        foreign.join(1_000)
        assertFalse(foreign.isAlive)
        assertEquals(
            P2HStatus.SUBMITTED_AWAITING_TERMINAL_AFTER_OUTPUT,
            fake.ledger.status(),
        )
        assertEquals(null, withTimeoutOrNull(30L) { fake.awaitTerminal() })
        assertEquals(1, fake.ledger.phases().count { it == P2HPhase.FIRST_EMISSION })
    }

    @Test fun zeroEmissionTerminalErrorIsNotNoCallback() = runBlocking {
        val fake = P2HMockSubmission()
        fake.sendMessageAsync { it.onError() }
        assertEquals(P2HTerminal.Error, fake.awaitTerminal())
        assertEquals(P2HStatus.TERMINAL_ERROR_ZERO_OUTPUT, fake.ledger.status())
    }

    @Test fun missingNativeCancelAcknowledgmentBlocksRelease() = runBlocking {
        val fake = P2HMockSubmission()
        fake.sendMessageAsync { }
        fake.fakeCancel(acknowledge = false)
        assertEquals(P2HStatus.NATIVE_TERMINAL_UNCONFIRMED, fake.ledger.status())
        assertTrue(P2HPhase.CANCEL_ACK_TIMEOUT in fake.ledger.phases())
        assertTrue(P2HPhase.RELEASE_BLOCKED in fake.ledger.phases())
        assertFalse(P2HPhase.TERMINAL_DONE in fake.ledger.phases())
    }

    @Test fun acknowledgedCancellationDoesNotClaimSuccessfulGeneration() = runBlocking {
        val fake = P2HMockSubmission()
        fake.sendMessageAsync { }
        fake.fakeCancel(acknowledge = true)
        assertEquals(P2HStatus.TERMINAL_ERROR_ZERO_OUTPUT, fake.ledger.status())
        assertFalse(P2HPhase.RELEASE_BLOCKED in fake.ledger.phases())
    }

    @Test fun phaseLedgerIsBoundedAndContentFree() {
        val ledger = P2HPhaseLedger()
        repeat(150) { ledger.emission() }
        repeat(150) { ledger.mark(P2HPhase.TERMINAL_AWAIT_ENTERED) }
        assertEquals(1, ledger.phases().count { it == P2HPhase.FIRST_EMISSION })
        assertEquals(32, ledger.evidence().size)
        assertTrue(ledger.evidence().all { it.elapsedMs >= 0L })
        assertEquals(P2HStatus.NOT_STARTED, ledger.status())
    }
}
