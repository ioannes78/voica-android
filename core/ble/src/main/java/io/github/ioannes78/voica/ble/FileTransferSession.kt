package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

enum class TransferCancelReason {
    USER,
    BACKGROUND,
    RECORDING_PRIORITY,
}

interface FileTransferDataSink {
    suspend fun write(bytes: ByteArray)
    suspend fun abort()
}

class FileTransferSinkException(
    val operationError: FileOperationError,
    cause: Throwable? = null,
) : Exception(operationError.detail, cause)

data class FileTransferCompleted(
    val requestSequence: Int,
    val actualFilename: String?,
    val receivedBytes: Long,
    val firstDataPrefix: ByteArray,
    val startSource: NotificationSource,
    val endSource: NotificationSource,
    val dataFrameCount: Int,
    val lastDataSource: NotificationSource?,
    val remoteStatusCode: Int,
)

sealed interface FileTransferExecutionResult {
    data class Completed(val value: FileTransferCompleted) : FileTransferExecutionResult
    data class Failed(val error: FileOperationError) : FileTransferExecutionResult
    data class Cancelled(val error: FileOperationError) : FileTransferExecutionResult
}

class FileTransferSession(
    private val startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val absoluteTimeoutMs: Long = DEFAULT_ABSOLUTE_TIMEOUT_MS,
    private val abortWaitMs: Long = DEFAULT_ABORT_WAIT_MS,
    private val eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
    private val nowNanos: () -> Long = System::nanoTime,
) : ReliableFileTransferConsumer {
    private sealed interface Signal {
        data class Event(val event: FileTransferFrameEvent) : Signal
        data class Cancel(val reason: TransferCancelReason) : Signal
        data object Closed : Signal
        data object Timeout : Signal
    }

    private val router = FileTransferFrameRouter()
    private val events = Channel<FileTransferFrameEvent>(capacity = eventCapacity)
    private val cancelSignal = CompletableDeferred<TransferCancelReason>()
    private val overflowed = AtomicBoolean(false)
    private val transportDisconnected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun offer(notification: RoutedNotification): Boolean {
        if (closed.get()) return false
        val event = router.route(notification) ?: return true
        val result = events.trySend(event)
        if (result.isSuccess) return true

        overflowed.set(true)
        events.close()
        return false
    }

    fun requestCancel(reason: TransferCancelReason) {
        cancelSignal.complete(reason)
    }

    suspend fun execute(
        sink: FileTransferDataSink,
        sendRequest: suspend () -> DeviceSendOnlyResult,
        sendAbort: suspend () -> DeviceSendOnlyResult,
        onProgress: (DownloadProgress) -> Unit = {},
        expectedBytes: Long,
    ): FileTransferExecutionResult {
        val startedNanos = nowNanos()
        val deadlineNanos = startedNanos + absoluteTimeoutMs * NANOS_PER_MILLI

        val request = try {
            sendRequest()
        } catch (error: RuntimeException) {
            sink.abortQuietly()
            return FileTransferExecutionResult.Failed(
                FileOperationError(
                    FileOperationErrorCode.GATT_WRITE_FAILED,
                    error.message,
                ),
            )
        }

        if (!request.written) {
            sink.abortQuietly()
            return FileTransferExecutionResult.Failed(
                FileOperationError(FileOperationErrorCode.GATT_WRITE_FAILED),
            )
        }

        val firstTimeout = boundedTimeout(startTimeoutMs, deadlineNanos)
        if (firstTimeout <= 0L) {
            sink.abortQuietly()
            return FileTransferExecutionResult.Failed(
                FileOperationError(FileOperationErrorCode.TRANSFER_TIMEOUT),
            )
        }

        val firstSignal = awaitSignal(firstTimeout)
        val start = when (firstSignal) {
            is Signal.Cancel ->
                return cancelAndAbort(sink, sendAbort, firstSignal.reason)
            Signal.Closed ->
                return overflowOrClosedFailure(sink)
            Signal.Timeout -> {
                sink.abortQuietly()
                return FileTransferExecutionResult.Failed(
                    FileOperationError(FileOperationErrorCode.START_TIMEOUT),
                )
            }
            is Signal.Event -> when (val event = firstSignal.event) {
                is FileTransferFrameEvent.Start -> event
                is FileTransferFrameEvent.End -> {
                    sink.abortQuietly()
                    return if (event.statusCode == REMOTE_STATUS_OK) {
                        FileTransferExecutionResult.Failed(
                            FileOperationError(
                                FileOperationErrorCode.UNEXPECTED_FRAME,
                                "IMPORT_END received before IMPORT_START",
                            ),
                        )
                    } else {
                        FileTransferExecutionResult.Failed(remoteStatusError(event.statusCode))
                    }
                }
                is FileTransferFrameEvent.Malformed -> {
                    sink.abortQuietly()
                    return FileTransferExecutionResult.Failed(
                        FileOperationError(
                            if (event.command == ProtocolConstants.File.IMPORT_START) {
                                FileOperationErrorCode.MALFORMED_START
                            } else {
                                FileOperationErrorCode.MALFORMED_END
                            },
                            event.reason,
                        ),
                    )
                }
                else -> {
                    sink.abortQuietly()
                    return FileTransferExecutionResult.Failed(
                        FileOperationError(
                            FileOperationErrorCode.UNEXPECTED_FRAME,
                            "cmd arrived before IMPORT_START",
                        ),
                    )
                }
            }
        }

        var receivedBytes = 0L
        var dataFrameCount = 0
        var lastDataSource: NotificationSource? = null
        val prefix = ArrayList<Byte>(FIRST_DATA_PREFIX_LIMIT)

        while (true) {
            val remainingMs = boundedTimeout(idleTimeoutMs, deadlineNanos)
            if (remainingMs <= 0L) {
                sink.abortQuietly()
                return FileTransferExecutionResult.Failed(
                    FileOperationError(FileOperationErrorCode.TRANSFER_TIMEOUT),
                )
            }

            when (val signal = awaitSignal(remainingMs)) {
                is Signal.Cancel ->
                    return cancelAndAbort(sink, sendAbort, signal.reason)

                Signal.Closed ->
                    return overflowOrClosedFailure(sink)

                Signal.Timeout -> {
                    sink.abortQuietly()
                    return FileTransferExecutionResult.Failed(
                        FileOperationError(
                            if (nowNanos() >= deadlineNanos) {
                                FileOperationErrorCode.TRANSFER_TIMEOUT
                            } else {
                                FileOperationErrorCode.TRANSFER_IDLE_TIMEOUT
                            },
                        ),
                    )
                }

                is Signal.Event -> when (val event = signal.event) {
                    is FileTransferFrameEvent.Data -> {
                        try {
                            sink.write(event.bytes)
                        } catch (error: FileTransferSinkException) {
                            sink.abortQuietly()
                            return FileTransferExecutionResult.Failed(error.operationError)
                        } catch (error: Throwable) {
                            sink.abortQuietly()
                            return FileTransferExecutionResult.Failed(
                                FileOperationError(
                                    FileOperationErrorCode.LOCAL_WRITE_FAILED,
                                    error.message,
                                ),
                            )
                        }
                        receivedBytes += event.bytes.size
                        dataFrameCount += 1
                        lastDataSource = event.source
                        if (prefix.size < FIRST_DATA_PREFIX_LIMIT) {
                            val remaining = FIRST_DATA_PREFIX_LIMIT - prefix.size
                            event.bytes.take(remaining).forEach(prefix::add)
                        }
                        onProgress(
                            DownloadProgress(
                                receivedBytes = receivedBytes,
                                expectedBytes = expectedBytes,
                            ),
                        )
                    }

                    is FileTransferFrameEvent.End -> {
                        if (event.statusCode != REMOTE_STATUS_OK) {
                            sink.abortQuietly()
                            return FileTransferExecutionResult.Failed(
                                remoteStatusError(event.statusCode),
                            )
                        }
                        closed.set(true)
                        events.close()
                        return FileTransferExecutionResult.Completed(
                            FileTransferCompleted(
                                requestSequence = request.requestSequence,
                                actualFilename = start.actualFilename,
                                receivedBytes = receivedBytes,
                                firstDataPrefix = prefix.toByteArray(),
                                startSource = start.source,
                                endSource = event.source,
                                dataFrameCount = dataFrameCount,
                                lastDataSource = lastDataSource,
                                remoteStatusCode = event.statusCode,
                            ),
                        )
                    }

                    is FileTransferFrameEvent.Malformed -> {
                        sink.abortQuietly()
                        return FileTransferExecutionResult.Failed(
                            FileOperationError(
                                if (event.command == ProtocolConstants.File.IMPORT_START) {
                                    FileOperationErrorCode.MALFORMED_START
                                } else {
                                    FileOperationErrorCode.MALFORMED_END
                                },
                                event.reason,
                            ),
                        )
                    }

                    is FileTransferFrameEvent.Start -> {
                        sink.abortQuietly()
                        return FileTransferExecutionResult.Failed(
                            FileOperationError(
                                FileOperationErrorCode.UNEXPECTED_FRAME,
                                "duplicate IMPORT_START",
                            ),
                        )
                    }

                    is FileTransferFrameEvent.AbortResponse -> {
                        sink.abortQuietly()
                        return FileTransferExecutionResult.Failed(
                            FileOperationError(
                                FileOperationErrorCode.REMOTE_STOPPED,
                                remoteStatusCode = event.statusCode,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun notifyTransportDisconnected() {
        transportDisconnected.set(true)
        if (closed.compareAndSet(false, true)) {
            events.close()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            events.close()
        }
    }

    private suspend fun cancelAndAbort(
        sink: FileTransferDataSink,
        sendAbort: suspend () -> DeviceSendOnlyResult,
        reason: TransferCancelReason,
    ): FileTransferExecutionResult {
        runCatching { sendAbort() }

        val waitDeadline = nowNanos() + abortWaitMs * NANOS_PER_MILLI
        while (nowNanos() < waitDeadline) {
            val remaining = ((waitDeadline - nowNanos()) / NANOS_PER_MILLI).coerceAtLeast(1L)
            when (val signal = awaitSignal(remaining)) {
                is Signal.Event -> {
                    if (
                        signal.event is FileTransferFrameEvent.AbortResponse ||
                        signal.event is FileTransferFrameEvent.End
                    ) {
                        break
                    }
                }
                else -> break
            }
        }

        sink.abortQuietly()
        close()
        val code = when (reason) {
            TransferCancelReason.USER -> FileOperationErrorCode.USER_CANCELLED
            TransferCancelReason.BACKGROUND -> FileOperationErrorCode.BACKGROUND_CANCELLED
            TransferCancelReason.RECORDING_PRIORITY ->
                FileOperationErrorCode.RECORDING_PRIORITY_CANCELLED
        }
        return FileTransferExecutionResult.Cancelled(FileOperationError(code))
    }

    private suspend fun overflowOrClosedFailure(
        sink: FileTransferDataSink,
    ): FileTransferExecutionResult {
        sink.abortQuietly()
        return FileTransferExecutionResult.Failed(
            FileOperationError(
                when {
                    overflowed.get() -> FileOperationErrorCode.DATA_PIPELINE_OVERFLOW
                    transportDisconnected.get() -> FileOperationErrorCode.BLE_DISCONNECTED
                    else -> FileOperationErrorCode.SESSION_REPLACED
                },
            ),
        )
    }

    private suspend fun awaitSignal(timeoutMs: Long): Signal =
        withTimeoutOrNull(timeoutMs) {
            select {
                events.onReceiveCatching { result ->
                    result.getOrNull()?.let(Signal::Event) ?: Signal.Closed
                }
                cancelSignal.onAwait { Signal.Cancel(it) }
            }
        } ?: Signal.Timeout

    private fun boundedTimeout(requestedMs: Long, deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - nowNanos()
        if (remainingNanos <= 0L) return 0L
        val remainingMs = (remainingNanos / NANOS_PER_MILLI).coerceAtLeast(1L)
        return minOf(requestedMs, remainingMs)
    }

    private fun remoteStatusError(statusCode: Int): FileOperationError =
        when (statusCode) {
            REMOTE_STATUS_FILE_NOT_FOUND ->
                FileOperationError(
                    FileOperationErrorCode.REMOTE_FILE_NOT_FOUND,
                    remoteStatusCode = statusCode,
                )
            REMOTE_STATUS_BAD_OFFSET ->
                FileOperationError(
                    FileOperationErrorCode.REMOTE_BAD_OFFSET,
                    remoteStatusCode = statusCode,
                )
            REMOTE_STATUS_STOPPED ->
                FileOperationError(
                    FileOperationErrorCode.REMOTE_STOPPED,
                    remoteStatusCode = statusCode,
                )
            else ->
                FileOperationError(
                    FileOperationErrorCode.UNKNOWN_REMOTE_STATUS,
                    remoteStatusCode = statusCode,
                )
        }

    private suspend fun FileTransferDataSink.abortQuietly() {
        runCatching { abort() }
        close()
    }

    private companion object {
        const val DEFAULT_START_TIMEOUT_MS = 8_000L
        const val DEFAULT_IDLE_TIMEOUT_MS = 8_000L
        const val DEFAULT_ABSOLUTE_TIMEOUT_MS = 10 * 60_000L
        const val DEFAULT_ABORT_WAIT_MS = 1_500L
        const val DEFAULT_EVENT_CAPACITY = 64
        const val FIRST_DATA_PREFIX_LIMIT = 32
        const val NANOS_PER_MILLI = 1_000_000L

        const val REMOTE_STATUS_OK = 0
        const val REMOTE_STATUS_FILE_NOT_FOUND = 1
        const val REMOTE_STATUS_BAD_OFFSET = 2
        const val REMOTE_STATUS_STOPPED = 3
    }
}
