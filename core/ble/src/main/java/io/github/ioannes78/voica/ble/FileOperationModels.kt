package io.github.ioannes78.voica.ble

enum class DeviceFileOperationType {
    REFRESH,
    DOWNLOAD,
    RANGE_PROBE,
    DELETE_REMOTE,
}

enum class RefreshReason {
    INITIAL_CONNECTION,
    RECONNECTED,
    RECORDING_FINALIZED,
    REMOTE_DELETE_COMPLETED,
    USER_REQUESTED,
    RECOVERY,
}

enum class FileOperationStage {
    PREPARING,
    REQUESTING,
    AWAITING_REMOTE,
    TRANSFERRING,
    VERIFYING,
    COMMITTING,
    CANCELLING,
}

enum class FileOperationErrorCode {
    NOT_READY,
    RECORDING_ACTIVE,
    FILE_LIST_NOT_FRESH,
    FILE_OPERATION_BUSY,
    INVALID_REMOTE_RECORDING,
    INVALID_FILENAME,
    BLE_DISCONNECTED,
    GATT_WRITE_FAILED,
    SESSION_REPLACED,
    DATA_PIPELINE_OVERFLOW,
    UNEXPECTED_FRAME,
    MALFORMED_START,
    MALFORMED_END,
    INVALID_TRANSFER_FILENAME,
    UNKNOWN_REMOTE_STATUS,
    START_TIMEOUT,
    TRANSFER_IDLE_TIMEOUT,
    TRANSFER_TIMEOUT,
    REMOTE_FILE_NOT_FOUND,
    REMOTE_BAD_OFFSET,
    REMOTE_STOPPED,
    SIZE_MISMATCH,
    INVALID_AUDIO_CONTAINER,
    INSUFFICIENT_STORAGE,
    TEMP_FILE_CREATE_FAILED,
    LOCAL_WRITE_FAILED,
    FSYNC_FAILED,
    ATOMIC_COMMIT_FAILED,
    LOCAL_ARTIFACT_CONFLICT,
    DELETE_WRITE_FAILED,
    DELETE_RESPONSE_TIMEOUT,
    DELETE_REJECTED,
    DELETE_VERIFICATION_FAILED,
    DELETE_OUTCOME_UNKNOWN,
    LOCAL_DELETE_FAILED,
    USER_CANCELLED,
    BACKGROUND_CANCELLED,
    RECORDING_PRIORITY_CANCELLED,
}

data class FileOperationError(
    val code: FileOperationErrorCode,
    val detail: String? = null,
    val remoteStatusCode: Int? = null,
)

data class DownloadProgress(
    val receivedBytes: Long,
    val expectedBytes: Long,
) {
    val fraction: Float?
        get() = if (expectedBytes > 0L) {
            (receivedBytes.toDouble() / expectedBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            null
        }
}

sealed interface FileOperationState {
    data object Idle : FileOperationState

    data class Active(
        val operationId: Long,
        val operation: DeviceFileOperationType,
        val stage: FileOperationStage,
        val remoteIdentity: String? = null,
        val progress: DownloadProgress? = null,
    ) : FileOperationState

    data class Completed(
        val operationId: Long,
        val operation: DeviceFileOperationType,
        val remoteIdentity: String? = null,
    ) : FileOperationState

    data class Failed(
        val operationId: Long,
        val operation: DeviceFileOperationType,
        val remoteIdentity: String? = null,
        val error: FileOperationError,
    ) : FileOperationState

    data class Cancelled(
        val operationId: Long,
        val operation: DeviceFileOperationType,
        val remoteIdentity: String? = null,
        val reason: FileOperationError,
    ) : FileOperationState

    data class OutcomeUnknown(
        val operationId: Long,
        val operation: DeviceFileOperationType,
        val remoteIdentity: String,
        val error: FileOperationError,
    ) : FileOperationState
}


data class FileTransferDiagnostics(
    val operationId: Long? = null,
    val transportSessionId: Long? = null,
    val remoteIdentity: String? = null,
    val listFilename: String? = null,
    val requestFilename: String? = null,
    val requestFilenameByteLength: Int? = null,
    val requestFrameLength: Int? = null,
    val requestSequence: Int? = null,
    val actualTransferFilename: String? = null,
    val startSource: NotificationSource? = null,
    val lastDataSource: NotificationSource? = null,
    val endSource: NotificationSource? = null,
    val dataFrameCount: Int = 0,
    val expectedBytes: Long? = null,
    val receivedBytes: Long = 0L,
    val firstDataPrefixHex: String? = null,
    val detectedContainer: AudioContainer? = null,
    val remoteStatusCode: Int? = null,
    val lastError: FileOperationError? = null,
)


data class RemoteDeleteDiagnostics(
    val operationId: Long? = null,
    val remoteIdentity: String? = null,
    val requestBodyLength: Int? = null,
    val responseSource: NotificationSource? = null,
    val responseStatusCode: Int? = null,
    val responseLatencyMs: Long? = null,
    val verificationResult: String? = null,
    val outcomeUnknown: Boolean = false,
    val lastError: FileOperationError? = null,
)
