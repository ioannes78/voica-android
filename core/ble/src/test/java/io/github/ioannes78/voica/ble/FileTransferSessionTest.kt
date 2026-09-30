package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolCodec
import io.github.ioannes78.voica.protocol.ProtocolConstants
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferSessionTest {
    @Test
    fun streamsMixedAe22Ae23FramesAndCompletes() = runTest {
        val sink = FakeSink()
        val session = FileTransferSession(
            startTimeoutMs = 2_000,
            idleTimeoutMs = 2_000,
            absoluteTimeoutMs = 10_000,
        )
        val progressCount = AtomicInteger()
        val result = async {
            session.execute(
                sink = sink,
                sendRequest = { DeviceSendOnlyResult(9, true) },
                sendAbort = { DeviceSendOnlyResult(10, true) },
                expectedBytes = 8,
                onProgress = { progressCount.incrementAndGet() },
            )
        }

        yield()
        assertTrue(session.offer(start(NotificationSource.AE23)))
        assertTrue(session.offer(data(NotificationSource.AE22, byteArrayOf(1, 2, 3))))
        assertTrue(session.offer(data(NotificationSource.AE23, byteArrayOf(4, 5, 6, 7, 8))))
        assertTrue(session.offer(end(NotificationSource.AE22, 0)))

        val completed = result.await() as FileTransferExecutionResult.Completed
        assertEquals(8, completed.value.receivedBytes)
        assertEquals(2, completed.value.dataFrameCount)
        assertEquals(NotificationSource.AE23, completed.value.startSource)
        assertEquals(NotificationSource.AE22, completed.value.endSource)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8), sink.bytes.toByteArray())
        assertEquals(2, progressCount.get())
    }

    @Test
    fun remoteFileNotFoundDoesNotCommitData() = runTest {
        val sink = FakeSink()
        val session = FileTransferSession(
            startTimeoutMs = 2_000,
            idleTimeoutMs = 2_000,
            absoluteTimeoutMs = 10_000,
        )
        val result = async {
            session.execute(
                sink,
                { DeviceSendOnlyResult(1, true) },
                { DeviceSendOnlyResult(2, true) },
                expectedBytes = 100,
            )
        }

        yield()
        session.offer(end(NotificationSource.AE22, 1))

        val failed = result.await() as FileTransferExecutionResult.Failed
        assertEquals(FileOperationErrorCode.REMOTE_FILE_NOT_FOUND, failed.error.code)
        assertTrue(sink.aborted)
    }

    @Test
    fun userCancelSendsAbortAndDeletesPartialSink() = runTest {
        val sink = FakeSink()
        val abortCount = AtomicInteger()
        val session = FileTransferSession(
            startTimeoutMs = 2_000,
            idleTimeoutMs = 2_000,
            absoluteTimeoutMs = 10_000,
            abortWaitMs = 20,
        )
        val result = async {
            session.execute(
                sink,
                { DeviceSendOnlyResult(1, true) },
                {
                    abortCount.incrementAndGet()
                    DeviceSendOnlyResult(2, true)
                },
                expectedBytes = 100,
            )
        }

        yield()
        session.offer(start(NotificationSource.AE22))
        session.offer(data(NotificationSource.AE22, byteArrayOf(1, 2)))
        session.requestCancel(TransferCancelReason.USER)

        val cancelled = result.await() as FileTransferExecutionResult.Cancelled
        assertEquals(FileOperationErrorCode.USER_CANCELLED, cancelled.error.code)
        assertEquals(1, abortCount.get())
        assertTrue(sink.aborted)
    }

    private class FakeSink : FileTransferDataSink {
        val bytes = mutableListOf<Byte>()
        var aborted = false

        override suspend fun write(bytes: ByteArray) {
            this.bytes += bytes.toList()
        }

        override suspend fun abort() {
            aborted = true
        }
    }

    private fun start(source: NotificationSource): RoutedNotification =
        routed(
            source,
            ProtocolConstants.File.IMPORT_START,
            "note.wav".encodeToByteArray(),
        )

    private fun data(source: NotificationSource, bytes: ByteArray): RoutedNotification =
        routed(source, ProtocolConstants.File.DATA, bytes)

    private fun end(source: NotificationSource, status: Int): RoutedNotification =
        routed(source, ProtocolConstants.File.IMPORT_END, byteArrayOf(status.toByte()))

    private fun routed(
        source: NotificationSource,
        command: Int,
        body: ByteArray,
    ): RoutedNotification {
        val frame = io.github.ioannes78.voica.protocol.FrameParser("transfer-session-test")
            .feed(
                ProtocolCodec.buildCommand(
                    sequence = 1,
                    type = ProtocolConstants.Type.FILE,
                    command = command,
                    params = body,
                ),
            )
            .single()
        return RoutedNotification(source, frame)
    }
}
