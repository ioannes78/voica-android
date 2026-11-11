package io.github.ioannes78.voica.localllm

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R17C P2-F: test-source-only, fake-native, no LiteRT-LM import, no Android hardware.
 * Models the ownership protocol; it is NOT a new production runtime/driver fix.
 */
private sealed interface P2FTerminal {
    data object Done : P2FTerminal
    data class Failed(val error: Throwable) : P2FTerminal
}

private interface P2FNativeFake {
    fun initialize()
    fun createConversation()
    fun sendMessageAsync(onTerminal: (P2FTerminal) -> Unit)
    fun cancelProcess()
    fun closeConversation()
    fun closeEngine()
}

/**
 * Never blocks the single owner executor awaiting the callback.
 * Cancellation and close are dispatched to the same owner OS thread.
 * Unknown native terminal state poisons the whole harness, leaving fake
 * native objects untouched instead of pretending that release was safe.
 */
private class P2FMockSingleOwner(
    private val makeSession: () -> P2FNativeFake,
    private val acknowledgementTimeoutMs: Long = 60L,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "P2F-mock-jni-owner").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val occupied = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)
    val isPoisoned: Boolean get() = poisoned.get()
    val waitingForTerminal = CompletableDeferred<Unit>()

    suspend fun generate() {
        check(!poisoned.get()) { "fake JNI owner was quarantined after uncertain native release" }
        check(occupied.compareAndSet(false, true)) { "fake JNI owner already running" }
        val terminal = CompletableDeferred<P2FTerminal>()
        val submitted = AtomicBoolean(false)
        val conversationReady = AtomicBoolean(false)
        var confirmedTerminal = false
        var session: P2FNativeFake? = null
        try {
            try {
                withContext(dispatcher) {
                    val native = makeSession()
                    session = native
                    native.initialize()
                    native.createConversation()
                    conversationReady.set(true)
                    // Set before send: a synchronous exception may still leave native work active.
                    submitted.set(true)
                    native.sendMessageAsync { event -> terminal.complete(event) }
                }
                waitingForTerminal.complete(Unit)
                when (val result = terminal.await()) {
                    P2FTerminal.Done -> confirmedTerminal = true
                    is P2FTerminal.Failed -> {
                        confirmedTerminal = true
                        throw result.error
                    }
                }
            } catch (cancelled: CancellationException) {
                if (submitted.get() && !confirmedTerminal) {
                    val signalled = runCatching {
                        withContext(NonCancellable + dispatcher) {
                            checkNotNull(session).cancelProcess()
                        }
                    }.isSuccess
                    if (signalled) {
                        confirmedTerminal = withContext(NonCancellable) {
                            withTimeoutOrNull(acknowledgementTimeoutMs) { terminal.await() }
                        } != null
                    }
                    if (!confirmedTerminal) poisoned.set(true)
                } else if (!conversationReady.get()) {
                    // Init/create may still be incomplete; never release unknown native state.
                    poisoned.set(true)
                }
                throw cancelled
            } catch (error: Throwable) {
                if ((submitted.get() && !confirmedTerminal) || !conversationReady.get()) {
                    poisoned.set(true)
                }
                throw error
            }
        } finally {
            try {
                val native = session
                if (native != null && conversationReady.get() && !poisoned.get() &&
                    (!submitted.get() || confirmedTerminal)
                ) {
                    withContext(NonCancellable + dispatcher) {
                        try {
                            native.closeConversation()
                            native.closeEngine()
                        } catch (releaseFailure: Throwable) {
                            poisoned.set(true)
                            throw releaseFailure
                        }
                    }
                }
            } finally {
                occupied.set(false)
            }
        }
    }

    override fun close() {
        // Only mock dispatcher resources. Never attempts to recover quarantined native state.
        dispatcher.close()
        check(executor.awaitTermination(2, TimeUnit.SECONDS)) {
            "mock owner thread did not exit; possible test harness deadlock"
        }
    }
}

private class P2FRecordingFake(
    private val acknowledgeCancel: Boolean = true,
    private val failClose: Boolean = false,
    private val failSend: Boolean = false,
) : P2FNativeFake {
    val sent = CompletableDeferred<Unit>()
    val operations = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    private lateinit var callback: (P2FTerminal) -> Unit
    private fun record(name: String) {
        operations.add(name to Thread.currentThread().id)
    }

    override fun initialize() = record("initialize")
    override fun createConversation() = record("createConversation")
    override fun sendMessageAsync(onTerminal: (P2FTerminal) -> Unit) {
        record("sendMessageAsync")
        callback = onTerminal
        if (failSend) error("fake synchronous send uncertain")
        sent.complete(Unit)
    }
    override fun cancelProcess() {
        record("cancelProcess")
        if (acknowledgeCancel) {
            callback(P2FTerminal.Failed(CancellationException("fake native cancelled")))
        }
    }
    override fun closeConversation() {
        record("closeConversation")
        if (failClose) error("fake native close failed")
    }
    override fun closeEngine() = record("closeEngine")

    fun finishFromForeignThread(terminal: P2FTerminal = P2FTerminal.Done): Long {
        var callbackThread = -1L
        val worker = Thread({
            callbackThread = Thread.currentThread().id
            callback(terminal)
        }, "P2F-fake-native-callback")
        worker.start()
        worker.join(2_000L)
        check(!worker.isAlive) { "fake native callback stuck" }
        return callbackThread
    }

    fun names(): List<String> = operations.map { it.first }
    fun ownerIds(): Set<Long> = operations.map { it.second }.toSet()
}

class Stage17P2FMockJniThreadAffinityTest {
    @Test fun sameOwnerAcrossInitializeSendAndCloseWithForeignCallback() = runBlocking {
        val fake = P2FRecordingFake()
        P2FMockSingleOwner({ fake }).use { harness ->
            val work = async { harness.generate() }
            withTimeout(3_000) { fake.sent.await() }
            val callbackThreadId = fake.finishFromForeignThread()
            withTimeout(3_000) { work.await() }
            assertEquals(
                listOf("initialize", "createConversation", "sendMessageAsync",
                    "closeConversation", "closeEngine"),
                fake.names(),
            )
            assertEquals(1, fake.ownerIds().size)
            assertTrue(callbackThreadId !in fake.ownerIds())
            assertFalse(harness.isPoisoned)
        }
    }

    @Test fun cancelUsesSameOwnerAndClosesOnlyAfterNativeAcknowledges() = runBlocking {
        val fake = P2FRecordingFake(acknowledgeCancel = true)
        P2FMockSingleOwner({ fake }).use { harness ->
            val work = launch { harness.generate() }
            withTimeout(3_000) { fake.sent.await() }
            withTimeout(3_000) { harness.waitingForTerminal.await() }
            withTimeout(3_000) { work.cancelAndJoin() }
            assertEquals(
                listOf("initialize", "createConversation", "sendMessageAsync",
                    "cancelProcess", "closeConversation", "closeEngine"),
                fake.names(),
            )
            assertEquals(1, fake.ownerIds().size)
            assertFalse(harness.isPoisoned)
        }
    }

    @Test fun missingNativeCancelAcknowledgmentQuarantinesWithoutCloseOrRetry() = runBlocking {
        val fake = P2FRecordingFake(acknowledgeCancel = false)
        P2FMockSingleOwner({ fake }, acknowledgementTimeoutMs = 25L).use { harness ->
            val work = launch { harness.generate() }
            withTimeout(3_000) { fake.sent.await() }
            withTimeout(3_000) { harness.waitingForTerminal.await() }
            withTimeout(3_000) { work.cancelAndJoin() }
            assertEquals(
                listOf("initialize", "createConversation", "sendMessageAsync",
                    "cancelProcess"),
                fake.names(),
            )
            assertTrue(harness.isPoisoned)
            val second = runCatching { harness.generate() }.exceptionOrNull()
            assertTrue(second is IllegalStateException)
            assertEquals(4, fake.names().size)
        }
    }

    @Test fun terminalErrorCanReleaseOnOwnerButReleaseFailurePoisons() = runBlocking {
        val fake = P2FRecordingFake(failClose = true)
        P2FMockSingleOwner({ fake }).use { harness ->
            val work = async { runCatching { harness.generate() }.exceptionOrNull() }
            withTimeout(3_000) { fake.sent.await() }
            fake.finishFromForeignThread(P2FTerminal.Failed(IllegalArgumentException("mock generation error")))
            val failure = withTimeout(3_000) { work.await() }
            assertTrue(failure is IllegalStateException)
            assertTrue(harness.isPoisoned)
            assertEquals(
                listOf("initialize", "createConversation", "sendMessageAsync",
                    "closeConversation"),
                fake.names(),
            )
            assertEquals(1, fake.ownerIds().size)
        }
    }

    @Test fun synchronousSendFailureIsQuarantinedWithoutUnsafeClose() = runBlocking {
        val fake = P2FRecordingFake(failSend = true)
        P2FMockSingleOwner({ fake }).use { harness ->
            val failure = runCatching { harness.generate() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(harness.isPoisoned)
            assertEquals(
                listOf("initialize", "createConversation", "sendMessageAsync"),
                fake.names(),
            )
            assertEquals(1, fake.ownerIds().size)
            assertTrue(runCatching { harness.generate() }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun concurrentRequestsCannotCreateSecondNativeOwner() = runBlocking {
        val fake = P2FRecordingFake()
        P2FMockSingleOwner({ fake }).use { harness ->
            val first = async { harness.generate() }
            withTimeout(3_000) { fake.sent.await() }
            assertTrue(runCatching { harness.generate() }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, fake.names().count { it == "initialize" })
            fake.finishFromForeignThread()
            withTimeout(3_000) { first.await() }
        }
    }
}
