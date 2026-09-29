package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolFrame
import io.github.ioannes78.voica.protocol.SequenceGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

sealed interface DeviceCommandResult {
    data class Success(
        val requestSequence: Int,
        val response: ProtocolFrame,
        val source: NotificationSource? = null,
        val latencyMs: Long? = null,
    ) : DeviceCommandResult

    data object WriteFailed : DeviceCommandResult
    data object ResponseTimedOut : DeviceCommandResult
    data object Cancelled : DeviceCommandResult
}

class DeviceCommandClient(
    private val writer: suspend (ByteArray) -> Boolean,
    private val responseTimeoutMs: Long = 5_000L,
    private val sequenceGenerator: SequenceGenerator = SequenceGenerator(),
) {
    private data class MatchedResponse(
        val frame: ProtocolFrame,
        val source: NotificationSource?,
    )

    private data class Pending(
        val expectedType: Int,
        val expectedCommand: Int,
        val requestSequence: Int,
        val startedAtNanos: Long,
        val deferred: CompletableDeferred<MatchedResponse>,
    )

    private val requestMutex = Mutex()
    private val pendingLock = Any()
    private var pending: Pending? = null

    suspend fun request(
        expectedType: Int,
        expectedCommand: Int,
        buildRequest: (sequence: Int) -> ByteArray,
    ): DeviceCommandResult {
        requestMutex.lock()
        try {
            val sequence = sequenceGenerator.next()
            val deferred = CompletableDeferred<MatchedResponse>()
            val request = Pending(
                expectedType = expectedType,
                expectedCommand = expectedCommand,
                requestSequence = sequence,
                startedAtNanos = System.nanoTime(),
                deferred = deferred,
            )
            synchronized(pendingLock) {
                check(pending == null) { "Only one device request may be pending" }
                pending = request
            }

            val written = try {
                writer(buildRequest(sequence))
            } catch (_: RuntimeException) {
                false
            }
            if (!written) {
                clearPending(request)
                return DeviceCommandResult.WriteFailed
            }

            val response = withTimeoutOrNull(responseTimeoutMs) {
                deferred.await()
            }
            clearPending(request)
            return if (response == null) {
                DeviceCommandResult.ResponseTimedOut
            } else {
                DeviceCommandResult.Success(
                    requestSequence = sequence,
                    response = response.frame,
                    source = response.source,
                    latencyMs = (System.nanoTime() - request.startedAtNanos) / 1_000_000L,
                )
            }
        } finally {
            requestMutex.unlock()
        }
    }

    suspend fun sendOnly(
        buildRequest: (sequence: Int) -> ByteArray,
    ): Boolean {
        requestMutex.lock()
        return try {
            val sequence = sequenceGenerator.next()
            writer(buildRequest(sequence))
        } finally {
            requestMutex.unlock()
        }
    }

    fun accept(frame: ProtocolFrame): Boolean =
        accept(frame = frame, source = null)

    fun accept(notification: RoutedNotification): Boolean =
        accept(frame = notification.frame, source = notification.source)

    private fun accept(
        frame: ProtocolFrame,
        source: NotificationSource?,
    ): Boolean {
        val current = synchronized(pendingLock) { pending } ?: return false
        if (frame.type != current.expectedType || frame.command != current.expectedCommand) {
            return false
        }
        return current.deferred.complete(MatchedResponse(frame, source))
    }

    fun cancelPending() {
        val current = synchronized(pendingLock) {
            val value = pending
            pending = null
            value
        }
        current?.deferred?.cancel()
    }

    fun resetSequence() {
        sequenceGenerator.reset()
    }

    private fun clearPending(expected: Pending) {
        synchronized(pendingLock) {
            if (pending === expected) pending = null
        }
    }
}
