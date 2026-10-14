package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileOperationError
import io.github.ioannes78.voica.ble.FileOperationErrorCode
import io.github.ioannes78.voica.ble.FileOperationStage
import io.github.ioannes78.voica.ble.FileOperationState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritizedDeviceDownloadTest {
    @Test
    fun waitsForWavHeaderProbeBeforeStartingDownload() = runBlocking {
        val remoteIdentity = "device|note.opus|1024|10"
        val state = MutableStateFlow<FileOperationState>(
            FileOperationState.Active(
                operationId = 1L,
                operation = DeviceFileOperationType.WAV_HEADER_PROBE,
                stage = FileOperationStage.TRANSFERRING,
                remoteIdentity = remoteIdentity,
                audioFormat = DeviceAudioFormat.WAV,
            ),
        )
        var calls = 0

        val deferred = async {
            runPrioritizedDeviceDownload(
                fileOperationState = state,
                remoteIdentity = remoteIdentity,
                format = DeviceAudioFormat.OPUS,
                download = {
                    calls += 1
                    state.value =
                        FileOperationState.Completed(
                            operationId = 2L,
                            operation = DeviceFileOperationType.DOWNLOAD,
                            remoteIdentity = remoteIdentity,
                            audioFormat = DeviceAudioFormat.OPUS,
                        )
                },
                overallTimeoutMs = 2_000L,
                retryDelayMs = 1L,
            )
        }

        delay(25L)
        assertEquals(0, calls)
        state.value =
            FileOperationState.Completed(
                operationId = 1L,
                operation = DeviceFileOperationType.WAV_HEADER_PROBE,
                remoteIdentity = remoteIdentity,
                audioFormat = DeviceAudioFormat.WAV,
            )

        val result = deferred.await()
        assertTrue(result.completed)
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
        assertTrue(result.busyWaits >= 1)
    }

    @Test
    fun retriesSilentCoordinatorLossInsteadOfTreatingItAsDownloadCompletion() = runBlocking {
        val remoteIdentity = "device|note.opus|1024|10"
        val state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        var calls = 0

        val result =
            runPrioritizedDeviceDownload(
                fileOperationState = state,
                remoteIdentity = remoteIdentity,
                format = DeviceAudioFormat.OPUS,
                download = {
                    calls += 1
                    state.value =
                        if (calls == 1) {
                            FileOperationState.Completed(
                                operationId = 3L,
                                operation = DeviceFileOperationType.WAV_HEADER_PROBE,
                                remoteIdentity = remoteIdentity,
                                audioFormat = DeviceAudioFormat.WAV,
                            )
                        } else {
                            FileOperationState.Completed(
                                operationId = 4L,
                                operation = DeviceFileOperationType.DOWNLOAD,
                                remoteIdentity = remoteIdentity,
                                audioFormat = DeviceAudioFormat.OPUS,
                            )
                        }
                },
                maxAttempts = 3,
                overallTimeoutMs = 2_000L,
                retryDelayMs = 1L,
            )

        assertTrue(result.completed)
        assertEquals(2, calls)
        assertEquals(2, result.attempts)
    }

    @Test
    fun realDownloadFailureIsTerminalAndPreservesError() = runBlocking {
        val remoteIdentity = "device|note.opus|1024|10"
        val state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        var calls = 0

        val result =
            runPrioritizedDeviceDownload(
                fileOperationState = state,
                remoteIdentity = remoteIdentity,
                format = DeviceAudioFormat.OPUS,
                download = {
                    calls += 1
                    state.value =
                        FileOperationState.Failed(
                            operationId = 5L,
                            operation = DeviceFileOperationType.DOWNLOAD,
                            remoteIdentity = remoteIdentity,
                            audioFormat = DeviceAudioFormat.OPUS,
                            error =
                                FileOperationError(
                                    code = FileOperationErrorCode.REMOTE_FILE_NOT_FOUND,
                                    detail = "missing",
                                ),
                        )
                },
                maxAttempts = 4,
                overallTimeoutMs = 2_000L,
                retryDelayMs = 1L,
            )

        assertFalse(result.completed)
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
        assertEquals("REMOTE_FILE_NOT_FOUND:missing", result.terminalError)
    }
}
