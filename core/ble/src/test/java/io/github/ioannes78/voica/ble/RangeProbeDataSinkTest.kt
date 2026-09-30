package io.github.ioannes78.voica.ble

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProbeDataSinkTest {
    @Test
    fun collectsSmallProbeWithoutChangingBytes() = runTest {
        val sink = RangeProbeDataSink(maximumBytes = 16)
        sink.write(byteArrayOf(1, 2))
        sink.write(byteArrayOf(3, 4))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.bytes())
    }

    @Test
    fun failsRatherThanSilentlyTruncatingOverflow() = runTest {
        val sink = RangeProbeDataSink(maximumBytes = 3)
        sink.write(byteArrayOf(1, 2))

        val error = runCatching {
            sink.write(byteArrayOf(3, 4))
        }.exceptionOrNull()

        assertTrue(error is FileTransferSinkException)
        assertEquals(
            FileOperationErrorCode.DATA_PIPELINE_OVERFLOW,
            (error as FileTransferSinkException).operationError.code,
        )
    }
}
