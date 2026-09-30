package io.github.ioannes78.voica.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileOperationModelsTest {
    @Test
    fun downloadProgressClampsToExpectedRange() {
        assertEquals(0.5f, DownloadProgress(50, 100).fraction)
        assertEquals(1.0f, DownloadProgress(120, 100).fraction)
        assertEquals(0.0f, DownloadProgress(-1, 100).fraction)
        assertNull(DownloadProgress(1, 0).fraction)
    }

    @Test
    fun remoteDeleteUnknownOutcomeKeepsStableErrorCode() {
        val state = FileOperationState.OutcomeUnknown(
            operationId = 9,
            operation = DeviceFileOperationType.DELETE_REMOTE,
            remoteIdentity = "remote-1",
            error = FileOperationError(
                FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN,
            ),
        )

        assertEquals(
            FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN,
            state.error.code,
        )
    }
}
