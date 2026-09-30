package io.github.ioannes78.voica.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.ioannes78.voica.protocol.DeviceDecoders
import io.github.ioannes78.voica.protocol.FileTransferProtocol
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.RecordingCommandResult
import io.github.ioannes78.voica.protocol.RecordingGain
import io.github.ioannes78.voica.protocol.RecordingStatus
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DeviceRepository {
    val scanState: StateFlow<BleScanState>
    val connectionState: StateFlow<DeviceConnectionState>
    val deviceInfo: StateFlow<DeviceInfo>
    val recordingState: StateFlow<RecordingDeviceState>
    val deviceFileListState: StateFlow<DeviceFileListState>
    val fileOperationState: StateFlow<FileOperationState>
    val fileTransferDiagnostics: StateFlow<FileTransferDiagnostics>
    val localRecordings: StateFlow<List<LocalRecordingArtifact>>
    val diagnostics: StateFlow<BleDiagnostics>

    fun missingPermissions(): Set<String>
    fun onPermissionsChanged()
    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun setForeground(foreground: Boolean)
    suspend fun refreshDeviceInfo()
    suspend fun syncTime(): Boolean

    suspend fun startRecording()
    suspend fun pauseRecording()
    suspend fun resumeRecording()
    suspend fun saveRecording()
    suspend fun syncRecordingState()
    suspend fun setRecordingGain(gain: RecordingGain)
    suspend fun refreshDeviceFiles()
    suspend fun downloadDeviceFile(file: RemoteDeviceFile)
    suspend fun cancelDeviceFileDownload()
    suspend fun deleteLocalRecording(localId: String): LocalDeleteResult
}

class DefaultDeviceRepository(
    context: Context,
    parentScope: CoroutineScope,
) : DeviceRepository, Closeable {
    private val applicationContext = context.applicationContext
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val bluetoothManager =
        applicationContext.getSystemService(BluetoothManager::class.java)
    private val scanner = AndroidBleScanner(applicationContext, scope)
    private val session = AndroidDeviceSession(applicationContext, scope)
    private val recordingFrameRouter = RecordingFrameRouter()
    private val fileListFrameRouter = FileListFrameRouter()
    private val fileListSessionCoordinator = FileListSessionCoordinator()
    private val fileOperationCoordinator = DeviceFileOperationCoordinator()
    private val localRecordingStore = LocalRecordingStore(
        File(applicationContext.noBackupFilesDir, "recordings"),
    )
    private val recordingSyncMutex = Mutex()
    private val rememberedDeviceStore = RememberedDeviceStore(applicationContext)

    override val scanState: StateFlow<BleScanState> = scanner.state

    private val mutableConnectionState =
        MutableStateFlow<DeviceConnectionState>(initialEnvironmentState())
    override val connectionState: StateFlow<DeviceConnectionState> =
        mutableConnectionState.asStateFlow()

    private val mutableDeviceInfo = MutableStateFlow(DeviceInfo())
    override val deviceInfo: StateFlow<DeviceInfo> = mutableDeviceInfo.asStateFlow()

    private val mutableRecordingState = MutableStateFlow(RecordingDeviceState())
    override val recordingState: StateFlow<RecordingDeviceState> =
        mutableRecordingState.asStateFlow()

    private val mutableDeviceFileListState = MutableStateFlow(DeviceFileListState())
    override val deviceFileListState: StateFlow<DeviceFileListState> =
        mutableDeviceFileListState.asStateFlow()

    private val mutableFileOperationState =
        MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    override val fileOperationState: StateFlow<FileOperationState> =
        mutableFileOperationState.asStateFlow()

    private val mutableFileTransferDiagnostics =
        MutableStateFlow(FileTransferDiagnostics())
    override val fileTransferDiagnostics: StateFlow<FileTransferDiagnostics> =
        mutableFileTransferDiagnostics.asStateFlow()

    override val localRecordings: StateFlow<List<LocalRecordingArtifact>> =
        localRecordingStore.recordings

    override val diagnostics: StateFlow<BleDiagnostics> = session.diagnostics

    private var foreground = false
    private var lastAddress: String? = rememberedDeviceStore.readAddress()
    private var userDisconnectedThisProcess = false
    private var pauseSemanticLatched = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var refreshJob: Job? = null
    private var recordingPollJob: Job? = null
    private var recordingReconcileJob: Job? = null
    private var fileListFirstResponseJob: Job? = null
    private var fileListTotalTimeoutJob: Job? = null
    private var activeFileTransferSession: FileTransferSession? = null
    private var activeFileTransferOperationId: Long? = null
    private var lastReadySessionId: Long? = null
    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR,
                )
            ) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF,
                -> {
                    scanner.stop()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectAttempt = 0
                    session.setReconnectAttempt(0)
                    stopRecordingPoller()
                    recordingReconcileJob?.cancel()
                    recordingReconcileJob = null
                    markRecordingDisconnected()
                    markDeviceFilesDisconnected()
                    session.handleBluetoothOff()
                    mutableConnectionState.value = DeviceConnectionState.BluetoothOff
                }

                BluetoothAdapter.STATE_ON -> {
                    session.markIdleIfTransportAvailable()
                    if (mutableConnectionState.value is DeviceConnectionState.BluetoothOff) {
                        mutableConnectionState.value = initialEnvironmentState()
                    }
                    scope.launch {
                        delay(AUTO_CONNECT_AFTER_BLUETOOTH_ON_MS)
                        maybeAutoConnectRememberedDevice()
                    }
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()

        scope.launch {
            scanner.state.collect { scan ->
                if (scan.isScanning) {
                    if (mutableConnectionState.value is DeviceConnectionState.Idle) {
                        mutableConnectionState.value = DeviceConnectionState.Scanning
                    }
                } else if (mutableConnectionState.value is DeviceConnectionState.Scanning) {
                    mutableConnectionState.value = initialEnvironmentState()
                }
            }
        }

        scope.launch {
            session.notifications.collect { event ->
                val frame = event.frame
                if (
                    frame.type == ProtocolConstants.Type.CONTROL &&
                    frame.command == ProtocolConstants.Control.BATTERY_RESPONSE
                ) {
                    mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                        battery = DeviceDecoders.decodeBatteryState(frame.body),
                    )
                }

                recordingFrameRouter.route(event)?.let(::handleRecordingFrameEvent)
                fileListFrameRouter.route(event)?.let(::handleFileListFrameEvent)
            }
        }

        scope.launch {
            session.state.collect { state ->
                mutableConnectionState.value = state
                when (state) {
                    is DeviceConnectionState.Ready -> {
                        lastAddress = state.address
                        rememberedDeviceStore.remember(state.address)
                        userDisconnectedThisProcess = false
                        reconnectJob?.cancel()
                        reconnectJob = null
                        reconnectAttempt = 0
                        session.setReconnectAttempt(0)
                        mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                            address = state.address,
                            negotiatedMtu = state.negotiatedMtu,
                            name = session.currentDeviceName()
                                ?: mutableDeviceInfo.value.name,
                        )
                        refreshJob?.cancel()
                        refreshJob = scope.launch { refreshDeviceInfo() }

                        val sessionId = session.diagnostics.value.sessionId
                        val reason =
                            if (lastReadySessionId == null) {
                                RecordingSyncReason.INITIAL_READY
                            } else if (lastReadySessionId != sessionId) {
                                RecordingSyncReason.RECONNECT
                            } else {
                                RecordingSyncReason.INITIAL_READY
                            }
                        lastReadySessionId = sessionId
                        scheduleRecordingSync(reason, delayMs = 0)
                    }

                    is DeviceConnectionState.Disconnected -> {
                        stopRecordingPoller()
                        markDeviceFilesDisconnected()
                        recordingReconcileJob?.cancel()
                        recordingReconcileJob = null
                        markRecordingDisconnected()
                        if (state.reason == DisconnectReason.REMOTE) {
                            scheduleReconnect(state.address ?: lastAddress)
                        }
                    }

                    DeviceConnectionState.BluetoothOff,
                    is DeviceConnectionState.PermissionRequired,
                    -> {
                        stopRecordingPoller()
                        markRecordingDisconnected()
                        markDeviceFilesDisconnected()
                    }

                    is DeviceConnectionState.Error -> {
                        stopRecordingPoller()
                        markRecordingDisconnected()
                        markDeviceFilesDisconnected()
                        if (ReconnectPolicy.shouldRetry(state.error)) {
                            scheduleReconnect(lastAddress)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun missingPermissions(): Set<String> =
        BlePermissionPolicy.missingPermissions(applicationContext)

    override fun onPermissionsChanged() {
        refreshEnvironment()
        if (foreground && missingPermissions().isEmpty()) {
            maybeAutoConnectRememberedDevice()
        }
    }

    override fun startScan() {
        refreshEnvironment()
        if (missingPermissions().isNotEmpty()) return
        if (!isBluetoothEnabled()) return
        scanner.start()
    }

    override fun stopScan() {
        scanner.stop()
    }

    override fun connect(address: String) {
        userDisconnectedThisProcess = false
        scanner.stop()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
        stopRecordingPoller()
        recordingReconcileJob?.cancel()
        recordingReconcileJob = null
        lastReadySessionId = null
        cancelFileListTimeouts()
        fileListSessionCoordinator.cancel(nowMs())
        if (mutableDeviceFileListState.value.deviceAddress != address) {
            mutableDeviceFileListState.value = DeviceFileListState()
            session.updateFileListDiagnostics(FileListDiagnostics())
        } else {
            markDeviceFilesStale()
        }
        lastAddress = address
        connectInternal(address)
    }

    override fun disconnect() {
        userDisconnectedThisProcess = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
        stopRecordingPoller()
        recordingReconcileJob?.cancel()
        recordingReconcileJob = null
        markRecordingDisconnected()
        markDeviceFilesDisconnected()
        session.disconnect()
    }

    override fun setForeground(foreground: Boolean) {
        val wasForeground = this.foreground
        this.foreground = foreground

        if (!foreground) {
            activeFileTransferSession?.requestCancel(TransferCancelReason.BACKGROUND)
            scanner.stop()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            session.setReconnectAttempt(0)
            stopRecordingPoller()
            if (mutableConnectionState.value is DeviceConnectionState.ReconnectWaiting) {
                mutableConnectionState.value = session.state.value
            }
            return
        }

        val environment = initialEnvironmentState()
        when (environment) {
            is DeviceConnectionState.PermissionRequired -> {
                scanner.stop()
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                session.setReconnectAttempt(0)
                stopRecordingPoller()
                markRecordingDisconnected()
                markDeviceFilesDisconnected()
                session.handlePermissionRevoked()
                mutableConnectionState.value = environment
                return
            }
            DeviceConnectionState.BluetoothOff -> {
                scanner.stop()
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                session.setReconnectAttempt(0)
                stopRecordingPoller()
                markRecordingDisconnected()
                markDeviceFilesDisconnected()
                session.handleBluetoothOff()
                mutableConnectionState.value = environment
                return
            }
            DeviceConnectionState.Unavailable -> {
                mutableConnectionState.value = environment
                return
            }
            else -> Unit
        }

        when (val current = session.state.value) {
            is DeviceConnectionState.Ready -> {
                if (!wasForeground) {
                    scheduleRecordingSync(
                        RecordingSyncReason.FOREGROUND_RETURN,
                        delayMs = 0,
                    )
                }
            }
            is DeviceConnectionState.Disconnected -> {
                if (current.reason == DisconnectReason.REMOTE) {
                    scheduleReconnect(current.address ?: lastAddress)
                } else {
                    maybeAutoConnectRememberedDevice()
                }
            }
            is DeviceConnectionState.Error -> {
                if (ReconnectPolicy.shouldRetry(current.error)) {
                    scheduleReconnect(lastAddress)
                } else {
                    maybeAutoConnectRememberedDevice()
                }
            }
            DeviceConnectionState.Idle -> maybeAutoConnectRememberedDevice()
            else -> Unit
        }
    }

    override suspend fun refreshDeviceInfo() {
        val ready = session.state.value as? DeviceConnectionState.Ready ?: return
        val address = ready.address
        val scanName = scanner.state.value.devices
            .firstOrNull { it.address == address }
            ?.name

        mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
            name = scanName ?: session.currentDeviceName(),
            address = address,
            negotiatedMtu = ready.negotiatedMtu,
        )

        session.syncTime()

        session.readBattery()?.let { battery ->
            mutableDeviceInfo.value =
                mutableDeviceInfo.value.copy(battery = battery)
        }

        session.readCapacity()?.let { capacity ->
            mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                remainingKb = capacity.remaining,
                totalKb = capacity.total,
                capacityByteOrder = capacity.byteOrder,
            )
        }

        session.readFirmware()?.let { firmware ->
            mutableDeviceInfo.value =
                mutableDeviceInfo.value.copy(firmwareVersion = firmware)
        }

        session.readAuth()?.let { auth ->
            mutableDeviceInfo.value = mutableDeviceInfo.value.copy(
                authAvailable = auth.text != null || auth.hex.isNotEmpty(),
                authDisplay = auth.text ?: auth.hex,
            )
        }
    }

    override suspend fun syncTime(): Boolean = session.syncTime()

    override suspend fun refreshDeviceFiles() {
        if (mutableDeviceFileListState.value.freshness == FileListFreshness.LOADING) return

        val ready = session.state.value as? DeviceConnectionState.Ready
        if (ready == null) {
            noteFileListGuardFailure(
                FileListError(FileListErrorCode.NOT_READY, "device is not Ready"),
            )
            return
        }

        val recording = mutableRecordingState.value
        if (
            recording.status != RecordingStatus.Idle ||
            recording.freshness != RecordingFreshness.FRESH ||
            recording.commandState != RecordingCommandState.IDLE
        ) {
            noteFileListGuardFailure(
                FileListError(
                    FileListErrorCode.RECORDING_ACTIVE,
                    "recording status=" + recording.status +
                        " freshness=" + recording.freshness +
                        " command=" + recording.commandState,
                ),
            )
            return
        }

        val transportSessionId = session.diagnostics.value.sessionId
        val fileSessionId = fileListSessionCoordinator.start(
            transportSessionId = transportSessionId,
            deviceAddress = ready.address,
            startedAtMs = nowMs(),
        )

        mutableDeviceFileListState.value = mutableDeviceFileListState.value.copy(
            deviceAddress = ready.address,
            freshness = FileListFreshness.LOADING,
            activeSessionId = fileSessionId,
            lastError = null,
        )
        publishFileListDiagnostics(
            fileListSessionCoordinator.snapshot(fileSessionId),
            completionReason = null,
            error = null,
        )

        val request = session.requestFileList()
        if (!request.written) {
            failActiveFileList(
                FileListError(FileListErrorCode.WRITE_FAILED, "TYPE=2 CMD=0 write failed"),
                FileListCompletionReason.WRITE_FAILED,
            )
            return
        }

        fileListSessionCoordinator.noteRequestSequence(
            fileSessionId,
            request.requestSequence,
        )
        publishFileListDiagnostics(
            fileListSessionCoordinator.snapshot(fileSessionId),
            completionReason = null,
            error = null,
        )
        scheduleFileListTimeouts(fileSessionId)
    }

    override suspend fun downloadDeviceFile(file: RemoteDeviceFile) {
        val operation = fileOperationCoordinator.tryStart(DeviceFileOperationType.DOWNLOAD)
            ?: return

        val operationId = operation.operationId
        val remoteIdentity = file.identity
        mutableFileOperationState.value = FileOperationState.Active(
            operationId = operationId,
            operation = DeviceFileOperationType.DOWNLOAD,
            stage = FileOperationStage.PREPARING,
            remoteIdentity = remoteIdentity,
        )

        var prepared: LocalRecordingStore.PreparedLocalDownload? = null
        var transfer: FileTransferSession? = null
        try {
            val ready = session.state.value as? DeviceConnectionState.Ready
            if (ready == null) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.NOT_READY),
                )
                return
            }

            val recording = mutableRecordingState.value
            if (
                recording.status != RecordingStatus.Idle ||
                recording.freshness != RecordingFreshness.FRESH ||
                recording.commandState != RecordingCommandState.IDLE
            ) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.RECORDING_ACTIVE),
                )
                return
            }

            val listState = mutableDeviceFileListState.value
            if (listState.freshness != FileListFreshness.FRESH) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.FILE_LIST_NOT_FRESH),
                )
                return
            }
            if (
                listState.deviceAddress != ready.address ||
                listState.files.none { it.identity == remoteIdentity }
            ) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.INVALID_REMOTE_RECORDING),
                )
                return
            }

            if (localRecordingStore.isDownloaded(remoteIdentity)) {
                mutableFileOperationState.value = FileOperationState.Completed(
                    operationId = operationId,
                    operation = DeviceFileOperationType.DOWNLOAD,
                    remoteIdentity = remoteIdentity,
                )
                return
            }

            if (!localRecordingStore.hasCapacity(file.sizeBytes)) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.INSUFFICIENT_STORAGE),
                )
                return
            }

            val requestFilename = file.displayFilename
            val filenameBytes = requestFilename.encodeToByteArray()
            if (filenameBytes.isEmpty() || filenameBytes.any { it.toInt() == 0 }) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.INVALID_FILENAME),
                )
                return
            }

            val preparedDownload = try {
                localRecordingStore.prepare(file)
            } catch (error: FileTransferSinkException) {
                failDownloadPrecondition(operationId, remoteIdentity, error.operationError)
                return
            } catch (error: Throwable) {
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(
                        FileOperationErrorCode.TEMP_FILE_CREATE_FAILED,
                        error.message,
                    ),
                )
                return
            }

            prepared = preparedDownload
            val transferSession = FileTransferSession()
            transfer = transferSession
            if (!session.registerFileTransferConsumer(transferSession)) {
                localRecordingStore.abort(preparedDownload)
                failDownloadPrecondition(
                    operationId,
                    remoteIdentity,
                    FileOperationError(FileOperationErrorCode.FILE_OPERATION_BUSY),
                )
                return
            }
            activeFileTransferSession = transferSession
            activeFileTransferOperationId = operationId

            mutableFileTransferDiagnostics.value = FileTransferDiagnostics(
                operationId = operationId,
                transportSessionId = session.diagnostics.value.sessionId,
                remoteIdentity = remoteIdentity,
                listFilename = file.displayFilename,
                requestFilename = requestFilename,
                requestFilenameByteLength = filenameBytes.size,
                requestFrameLength = FILE_DOWNLOAD_FRAME_FIXED_BYTES + filenameBytes.size,
                expectedBytes = file.sizeBytes,
            )
            mutableFileOperationState.value = FileOperationState.Active(
                operationId = operationId,
                operation = DeviceFileOperationType.DOWNLOAD,
                stage = FileOperationStage.REQUESTING,
                remoteIdentity = remoteIdentity,
                progress = DownloadProgress(0L, file.sizeBytes),
            )

            val result = transferSession.execute(
                sink = preparedDownload.writer,
                sendRequest = {
                    session.sendFileTransferRequest { sequence ->
                        FileTransferProtocol.buildDownloadRequest(
                            sequence = sequence,
                            offset = 0L,
                            filenameBytes = filenameBytes,
                        )
                    }
                },
                sendAbort = session::sendFileTransferAbort,
                expectedBytes = file.sizeBytes,
                onProgress = { progress ->
                    mutableFileOperationState.value = FileOperationState.Active(
                        operationId = operationId,
                        operation = DeviceFileOperationType.DOWNLOAD,
                        stage = FileOperationStage.TRANSFERRING,
                        remoteIdentity = remoteIdentity,
                        progress = progress,
                    )
                    mutableFileTransferDiagnostics.value =
                        mutableFileTransferDiagnostics.value.copy(
                            receivedBytes = progress.receivedBytes,
                        )
                },
            )

            when (result) {
                is FileTransferExecutionResult.Completed -> {
                    mutableFileOperationState.value = FileOperationState.Active(
                        operationId = operationId,
                        operation = DeviceFileOperationType.DOWNLOAD,
                        stage = FileOperationStage.VERIFYING,
                        remoteIdentity = remoteIdentity,
                        progress = DownloadProgress(
                            result.value.receivedBytes,
                            file.sizeBytes,
                        ),
                    )
                    mutableFileTransferDiagnostics.value =
                        mutableFileTransferDiagnostics.value.copy(
                            requestSequence = result.value.requestSequence,
                            actualTransferFilename = result.value.actualFilename,
                            startSource = result.value.startSource,
                            lastDataSource = result.value.lastDataSource,
                            endSource = result.value.endSource,
                            dataFrameCount = result.value.dataFrameCount,
                            receivedBytes = result.value.receivedBytes,
                            firstDataPrefixHex = result.value.firstDataPrefix.toDiagnosticHex(),
                            remoteStatusCode = result.value.remoteStatusCode,
                        )

                    mutableFileOperationState.value = FileOperationState.Active(
                        operationId = operationId,
                        operation = DeviceFileOperationType.DOWNLOAD,
                        stage = FileOperationStage.COMMITTING,
                        remoteIdentity = remoteIdentity,
                        progress = DownloadProgress(
                            result.value.receivedBytes,
                            file.sizeBytes,
                        ),
                    )
                    try {
                        val commit = localRecordingStore.commit(
                            prepared = preparedDownload,
                            actualTransferFilename = result.value.actualFilename,
                        )
                        mutableFileTransferDiagnostics.value =
                            mutableFileTransferDiagnostics.value.copy(
                                firstDataPrefixHex = commit.firstDataPrefix.toDiagnosticHex(),
                                detectedContainer = commit.artifact.container,
                                lastError = null,
                            )
                        mutableFileOperationState.value = FileOperationState.Completed(
                            operationId = operationId,
                            operation = DeviceFileOperationType.DOWNLOAD,
                            remoteIdentity = remoteIdentity,
                        )
                    } catch (error: FileTransferSinkException) {
                        localRecordingStore.abort(preparedDownload)
                        val operationError = error.operationError
                        mutableFileTransferDiagnostics.value =
                            mutableFileTransferDiagnostics.value.copy(lastError = operationError)
                        mutableFileOperationState.value = FileOperationState.Failed(
                            operationId = operationId,
                            operation = DeviceFileOperationType.DOWNLOAD,
                            remoteIdentity = remoteIdentity,
                            error = operationError,
                        )
                    }
                }

                is FileTransferExecutionResult.Failed -> {
                    localRecordingStore.abort(prepared)
                    mutableFileTransferDiagnostics.value =
                        mutableFileTransferDiagnostics.value.copy(lastError = result.error)
                    mutableFileOperationState.value = FileOperationState.Failed(
                        operationId = operationId,
                        operation = DeviceFileOperationType.DOWNLOAD,
                        remoteIdentity = remoteIdentity,
                        error = result.error,
                    )
                }

                is FileTransferExecutionResult.Cancelled -> {
                    localRecordingStore.abort(prepared)
                    mutableFileTransferDiagnostics.value =
                        mutableFileTransferDiagnostics.value.copy(lastError = result.error)
                    mutableFileOperationState.value = FileOperationState.Cancelled(
                        operationId = operationId,
                        operation = DeviceFileOperationType.DOWNLOAD,
                        remoteIdentity = remoteIdentity,
                        reason = result.error,
                    )
                }
            }
        } finally {
            transfer?.let { session.unregisterFileTransferConsumer(it) }
            transfer?.close()
            if (activeFileTransferSession === transfer) {
                activeFileTransferSession = null
                activeFileTransferOperationId = null
            }
            fileOperationCoordinator.finish(operationId)
        }
    }

    override suspend fun cancelDeviceFileDownload() {
        activeFileTransferSession?.requestCancel(TransferCancelReason.USER)
    }

    override suspend fun deleteLocalRecording(localId: String): LocalDeleteResult =
        localRecordingStore.delete(localId)

    private fun failDownloadPrecondition(
        operationId: Long,
        remoteIdentity: String,
        error: FileOperationError,
    ) {
        mutableFileTransferDiagnostics.value =
            mutableFileTransferDiagnostics.value.copy(
                operationId = operationId,
                remoteIdentity = remoteIdentity,
                lastError = error,
            )
        mutableFileOperationState.value = FileOperationState.Failed(
            operationId = operationId,
            operation = DeviceFileOperationType.DOWNLOAD,
            remoteIdentity = remoteIdentity,
            error = error,
        )
    }

    private fun ByteArray.toDiagnosticHex(): String =
        take(FILE_DIAGNOSTIC_PREFIX_BYTES).joinToString(" ") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }

    override suspend fun startRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Idle ||
            fileOperationCoordinator.activeOperation() != null
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.STARTING,
            expectedStatus = RecordingStatus.Recording,
            action = session::startRecording,
        )
    }

    override suspend fun pauseRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Recording
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.PAUSING,
            expectedStatus = RecordingStatus.Paused,
            action = session::pauseRecording,
        )
    }

    override suspend fun resumeRecording() {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.status != RecordingStatus.Paused
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.RESUMING,
            expectedStatus = RecordingStatus.Recording,
            action = session::resumeRecording,
        )
    }

    override suspend fun saveRecording() {
        val current = mutableRecordingState.value.status
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            (current != RecordingStatus.Recording && current != RecordingStatus.Paused)
        ) {
            return
        }
        performRecordingCommand(
            commandState = RecordingCommandState.SAVING,
            expectedStatus = RecordingStatus.Idle,
            action = session::saveRecording,
        )
    }

    override suspend fun syncRecordingState() {
        syncRecordingState(RecordingSyncReason.MANUAL_REFRESH)
    }

    override suspend fun setRecordingGain(gain: RecordingGain) {
        if (
            session.state.value !is DeviceConnectionState.Ready ||
            mutableRecordingState.value.freshness != RecordingFreshness.FRESH
        ) {
            return
        }
        val raw = when (gain) {
            RecordingGain.Low -> ProtocolConstants.RecordingGainValue.LOW
            RecordingGain.Medium -> ProtocolConstants.RecordingGainValue.MEDIUM
            RecordingGain.High -> ProtocolConstants.RecordingGainValue.HIGH
            is RecordingGain.UnknownRaw -> return
        }

        stopRecordingPoller()
        reduceRecordingState(
            RecordingStateEvent.CommandStarted(
                RecordingCommandState.SETTING_GAIN,
                nowMs(),
            ),
        )

        val outcome = session.setRecordingGain(raw)
        val error = commandError(outcome, expectedSuccessCode = 0)
        reduceRecordingState(
            RecordingStateEvent.CommandFinished(
                error = error,
                timestampMs = nowMs(),
            ),
        )

        val gainOutcome = session.readRecordingGain()
        when (gainOutcome) {
            is RecordingRequestOutcome.Success -> {
                reduceRecordingState(
                    RecordingStateEvent.GainReceived(
                        gainOutcome.value,
                        nowMs(),
                    ),
                )
                reduceRecordingState(
                    RecordingStateEvent.SyncCompleted(nowMs()),
                    syncReason = RecordingSyncReason.APP_COMMAND,
                )
            }
            else -> {
                val gainError = requestError(gainOutcome, "GET_GAIN")
                if (gainError != null) {
                    reduceRecordingState(
                        RecordingStateEvent.OperationError(gainError, nowMs()),
                    )
                }
            }
        }

        if (error != null) {
            reduceRecordingState(RecordingStateEvent.OperationError(error, nowMs()))
        }
        updateRecordingPoller()
    }

    private suspend fun performRecordingCommand(
        commandState: RecordingCommandState,
        expectedStatus: RecordingStatus,
        action: suspend () -> Boolean,
    ) {
        stopRecordingPoller()
        reduceRecordingState(
            RecordingStateEvent.CommandStarted(commandState, nowMs()),
        )

        val written = action()
        val error =
            if (written) null
            else RecordingError(
                RecordingErrorCode.WRITE_FAILED,
                "card recording action write failed",
            )
        reduceRecordingState(
            RecordingStateEvent.CommandFinished(error, nowMs()),
        )

        if (written) {
            when (commandState) {
                RecordingCommandState.PAUSING -> {
                    pauseSemanticLatched = true
                    reduceRecordingState(
                        RecordingStateEvent.StateReceived(
                            RecordingStatus.Paused,
                            nowMs(),
                        ),
                        syncReason = RecordingSyncReason.APP_COMMAND,
                    )
                }
                RecordingCommandState.RESUMING -> {
                    pauseSemanticLatched = false
                    reduceRecordingState(
                        RecordingStateEvent.StateReceived(
                            RecordingStatus.Recording,
                            nowMs(),
                        ),
                        syncReason = RecordingSyncReason.APP_COMMAND,
                    )
                }
                RecordingCommandState.STARTING,
                RecordingCommandState.SAVING,
                -> pauseSemanticLatched = false
                else -> Unit
            }
            delay(RECORDING_ACTION_SETTLE_MS)
        }
        syncRecordingState(
            reason = RecordingSyncReason.APP_COMMAND,
            expectedStatus = if (written) expectedStatus else null,
        )

        if (error != null) {
            reduceRecordingState(RecordingStateEvent.OperationError(error, nowMs()))
        }
    }

    private suspend fun syncRecordingState(
        reason: RecordingSyncReason,
        expectedStatus: RecordingStatus? = null,
    ) {
        stopRecordingPoller()
        recordingSyncMutex.withLock {
            if (session.state.value !is DeviceConnectionState.Ready) {
                markRecordingDisconnected()
                return
            }

            reduceRecordingState(
                RecordingStateEvent.SyncStarted(nowMs()),
                syncReason = reason,
            )

            var status: RecordingStatus? = null
            val maxAttempts =
                if (expectedStatus == null) {
                    1
                } else {
                    RecordingStateConvergencePolicy.MAX_ATTEMPTS
                }

            for (attempt in 1..maxAttempts) {
                when (val stateOutcome = session.readRecordingState()) {
                    is RecordingRequestOutcome.Success -> {
                        val reportedStatus = stateOutcome.value
                        when (reportedStatus) {
                            RecordingStatus.Idle -> pauseSemanticLatched = false
                            RecordingStatus.Paused -> pauseSemanticLatched = true
                            else -> Unit
                        }
                        status = RecordingStateEvidencePolicy.resolveReportedStatus(
                            reported = reportedStatus,
                            pauseSemanticLatched = pauseSemanticLatched,
                        )
                        reduceRecordingState(
                            RecordingStateEvent.StateReceived(status, nowMs()),
                            syncReason = reason,
                        )
                        if (
                            expectedStatus == null ||
                            RecordingStateConvergencePolicy.isSatisfied(
                                actual = status,
                                expected = expectedStatus,
                            )
                        ) {
                            break
                        }
                    }

                    else -> {
                        val error = requestError(stateOutcome, "GET_STATE")
                            ?: RecordingError(
                                RecordingErrorCode.SYNC_FAILED,
                                "GET_STATE failed",
                            )
                        reduceRecordingState(
                            RecordingStateEvent.SyncFailed(error, nowMs()),
                            syncReason = reason,
                        )
                        stopRecordingPoller()
                        return
                    }
                }

                if (
                    expectedStatus != null &&
                    RecordingStateConvergencePolicy.shouldRetry(
                        attempt = attempt,
                        actual = status,
                        expected = expectedStatus,
                    )
                ) {
                    delay(RecordingStateConvergencePolicy.RETRY_DELAY_MS)
                }
            }

            val resolvedStatus = status ?: run {
                val error = RecordingError(
                    RecordingErrorCode.SYNC_FAILED,
                    "GET_STATE produced no state",
                )
                reduceRecordingState(
                    RecordingStateEvent.SyncFailed(error, nowMs()),
                    syncReason = reason,
                )
                stopRecordingPoller()
                return
            }

            val convergenceError =
                if (
                    expectedStatus != null &&
                    !RecordingStateConvergencePolicy.isSatisfied(
                        actual = resolvedStatus,
                        expected = expectedStatus,
                    )
                ) {
                    RecordingError(
                        RecordingErrorCode.SYNC_FAILED,
                        "expected=" + expectedStatus +
                            " actual=" + resolvedStatus +
                            " after=" + maxAttempts + " reads",
                    )
                } else {
                    null
                }

            if (RecordingSupplementaryReadPolicy.shouldReadTime(resolvedStatus)) {
                when (val time = session.readRecordingTime()) {
                    is RecordingRequestOutcome.Success ->
                        reduceRecordingState(
                            RecordingStateEvent.TimeReceived(time.value, nowMs()),
                            syncReason = reason,
                        )
                    else -> noteOptionalReadFailure(time, "GET_TIME")
                }
            }

            if (RecordingSupplementaryReadPolicy.shouldReadFilename(resolvedStatus)) {
                when (val filename = session.readRecordingFilename()) {
                    is RecordingRequestOutcome.Success ->
                        reduceRecordingState(
                            RecordingStateEvent.FilenameReceived(filename.value, nowMs()),
                            syncReason = reason,
                        )
                    else -> noteOptionalReadFailure(filename, "GET_FILENAME")
                }
            }

            when (val gain = session.readRecordingGain()) {
                is RecordingRequestOutcome.Success ->
                    reduceRecordingState(
                        RecordingStateEvent.GainReceived(gain.value, nowMs()),
                        syncReason = reason,
                    )
                else -> noteOptionalReadFailure(gain, "GET_GAIN")
            }

            reduceRecordingState(
                RecordingStateEvent.SyncCompleted(nowMs()),
                syncReason = reason,
            )
            if (convergenceError != null) {
                reduceRecordingState(
                    RecordingStateEvent.OperationError(convergenceError, nowMs()),
                    syncReason = reason,
                )
            }
            updateRecordingPoller()
        }
    }

    private fun handleRecordingFrameEvent(event: RecordingFrameEvent) {
        when (event) {
            is RecordingFrameEvent.State -> {
                if (mutableRecordingState.value.freshness != RecordingFreshness.SYNCING) {
                    when (event.value) {
                        RecordingStatus.Idle -> pauseSemanticLatched = false
                        RecordingStatus.Paused -> pauseSemanticLatched = true
                        else -> Unit
                    }
                    val resolved = RecordingStateEvidencePolicy.resolveReportedStatus(
                        reported = event.value,
                        pauseSemanticLatched = pauseSemanticLatched,
                    )
                    reduceRecordingState(
                        RecordingStateEvent.StateReceived(resolved, nowMs()),
                    )
                    updateRecordingPoller()
                }
            }

            is RecordingFrameEvent.Time ->
                reduceRecordingState(
                    RecordingStateEvent.TimeReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.Filename ->
                reduceRecordingState(
                    RecordingStateEvent.FilenameReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.Gain ->
                reduceRecordingState(
                    RecordingStateEvent.GainReceived(event.value, nowMs()),
                )

            is RecordingFrameEvent.CommandResponse -> Unit

            is RecordingFrameEvent.Hardware -> {
                stopRecordingPoller()
                if (event.event.kind == RecordingHardwareEventKind.START) {
                    activeFileTransferSession?.requestCancel(
                        TransferCancelReason.RECORDING_PRIORITY,
                    )
                }
                when (event.event.kind) {
                    RecordingHardwareEventKind.PAUSE -> {
                        pauseSemanticLatched = true
                        reduceRecordingState(
                            RecordingStateEvent.StateReceived(
                                RecordingStatus.Paused,
                                nowMs(),
                            ),
                            syncReason = RecordingSyncReason.HARDWARE_EVENT,
                        )
                    }
                    RecordingHardwareEventKind.RESUME -> {
                        pauseSemanticLatched = false
                        reduceRecordingState(
                            RecordingStateEvent.StateReceived(
                                RecordingStatus.Recording,
                                nowMs(),
                            ),
                            syncReason = RecordingSyncReason.HARDWARE_EVENT,
                        )
                    }
                    RecordingHardwareEventKind.START,
                    RecordingHardwareEventKind.SAVE,
                    -> pauseSemanticLatched = false
                }
                reduceRecordingState(RecordingStateEvent.HardwareReceived(event.event))
                session.noteRecordingHardwareEvent(event.event)
                acknowledgeHardwareEventAndResync(event.event)
            }

            is RecordingFrameEvent.Malformed -> {
                val error = RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    "cmd=" + event.command + " " + event.reason,
                )
                reduceRecordingState(
                    RecordingStateEvent.DecodeFailed(error, nowMs()),
                )
                session.noteRecordingDecodeError(event.reason)
            }

            is RecordingFrameEvent.Unknown -> Unit
        }
    }

    private fun handleFileListFrameEvent(event: FileListFrameEvent) {
        val result = fileListSessionCoordinator.accept(
            event = event,
            transportSessionId = session.diagnostics.value.sessionId,
            nowMs = nowMs(),
        )

        when (result) {
            is FileListSessionResult.DataAccepted -> {
                if (result.snapshot.dataFrameCount == 1) {
                    fileListFirstResponseJob?.cancel()
                    fileListFirstResponseJob = null
                }
                publishFileListDiagnostics(
                    result.snapshot,
                    completionReason = null,
                    error = null,
                )
            }

            is FileListSessionResult.Completed -> {
                cancelFileListTimeouts()
                val files = RemoteDeviceFileMapper.map(
                    deviceAddress = result.snapshot.deviceAddress,
                    entries = result.snapshot.entries,
                )
                mutableDeviceFileListState.value = DeviceFileListState(
                    deviceAddress = result.snapshot.deviceAddress,
                    files = files,
                    freshness =
                        if (files.isEmpty()) FileListFreshness.EMPTY
                        else FileListFreshness.FRESH,
                    lastUpdatedTimeMs = result.snapshot.endedAtMs ?: nowMs(),
                    activeSessionId = null,
                    lastError = null,
                )
                publishFileListDiagnostics(
                    result.snapshot,
                    completionReason = FileListCompletionReason.LIST_DONE,
                    error = null,
                )
            }

            is FileListSessionResult.Failed -> {
                cancelFileListTimeouts()
                applyFileListFailure(
                    snapshot = result.snapshot,
                    error = result.error,
                    completionReason = result.completionReason,
                )
            }

            is FileListSessionResult.Ignored -> Unit
        }
    }

    private fun scheduleFileListTimeouts(fileSessionId: Long) {
        cancelFileListTimeouts()

        val current = fileListSessionCoordinator.snapshot(fileSessionId) ?: return
        if (current.dataFrameCount == 0) {
            fileListFirstResponseJob = scope.launch {
                delay(FILE_LIST_FIRST_RESPONSE_TIMEOUT_MS)
                val snapshot = fileListSessionCoordinator.snapshot(fileSessionId)
                if (snapshot != null && snapshot.dataFrameCount == 0) {
                    failActiveFileList(
                        FileListError(
                            FileListErrorCode.FIRST_RESPONSE_TIMEOUT,
                            "no TYPE=2 list frame before timeout",
                        ),
                        FileListCompletionReason.FIRST_RESPONSE_TIMEOUT,
                    )
                }
            }
        }

        fileListTotalTimeoutJob = scope.launch {
            delay(FILE_LIST_TOTAL_SESSION_TIMEOUT_MS)
            if (fileListSessionCoordinator.snapshot(fileSessionId) != null) {
                failActiveFileList(
                    FileListError(
                        FileListErrorCode.SESSION_TIMEOUT,
                        "TYPE=2 CMD=18 was not received",
                    ),
                    FileListCompletionReason.SESSION_TIMEOUT,
                )
            }
        }
    }

    private fun failActiveFileList(
        error: FileListError,
        completionReason: FileListCompletionReason,
    ) {
        val snapshot = fileListSessionCoordinator.cancel(nowMs()) ?: return
        cancelFileListTimeouts()
        applyFileListFailure(snapshot, error, completionReason)
    }

    private fun applyFileListFailure(
        snapshot: FileListSessionSnapshot,
        error: FileListError,
        completionReason: FileListCompletionReason,
    ) {
        val previous = mutableDeviceFileListState.value
        mutableDeviceFileListState.value = previous.copy(
            deviceAddress = snapshot.deviceAddress,
            freshness =
                if (previous.lastUpdatedTimeMs != null) FileListFreshness.STALE
                else FileListFreshness.FAILED,
            activeSessionId = null,
            lastError = error,
        )
        publishFileListDiagnostics(snapshot, completionReason, error)
    }

    private fun noteFileListGuardFailure(error: FileListError) {
        val previous = mutableDeviceFileListState.value
        mutableDeviceFileListState.value = previous.copy(lastError = error)
        val diagnostics = session.diagnostics.value.fileList
        session.updateFileListDiagnostics(
            diagnostics.copy(lastOperationError = error.code.name + ": " + error.detail.orEmpty()),
        )
    }

    private fun publishFileListDiagnostics(
        snapshot: FileListSessionSnapshot?,
        completionReason: FileListCompletionReason?,
        error: FileListError?,
    ) {
        if (snapshot == null) return
        val newestFile = RemoteDeviceFileMapper
            .map(snapshot.deviceAddress, snapshot.entries)
            .firstOrNull()
        session.updateFileListDiagnostics(
            FileListDiagnostics(
                sessionId = snapshot.fileSessionId,
                transportSessionId = snapshot.transportSessionId,
                requestSequence = snapshot.requestSequence,
                dataFrameCount = snapshot.dataFrameCount,
                declaredEntryCount = snapshot.declaredEntryCount,
                parsedEntryCount = snapshot.entries.size,
                lastDataNotificationSource = snapshot.lastDataNotificationSource,
                listDoneNotificationSource = snapshot.listDoneNotificationSource,
                lastDataBodySize = snapshot.lastDataBodySize,
                listDoneBodySize = snapshot.listDoneBodySize,
                lastFilenameFieldLength = snapshot.lastFilenameFieldLength,
                receivedListDone = snapshot.receivedListDone,
                newestRawFilename = newestFile?.rawFilename,
                newestResolvedFilename = newestFile?.resolvedFilename,
                newestFilenameResolution = newestFile?.filenameResolution?.name,
                newestRawTimeValue = newestFile?.rawTimeValue,
                newestSizeBytes = newestFile?.sizeBytes,
                lastMalformedReason =
                    if (error?.code == FileListErrorCode.MALFORMED_PAYLOAD) error.detail else null,
                startedAtMs = snapshot.startedAtMs,
                durationMs = (snapshot.endedAtMs ?: nowMs()) - snapshot.startedAtMs,
                completionReason = completionReason,
                lastOperationError = error?.let {
                    it.code.name + (it.detail?.let { detail -> ": " + detail } ?: "")
                },
            ),
        )
    }

    private fun markDeviceFilesDisconnected() {
        val active = fileListSessionCoordinator.cancel(nowMs())
        cancelFileListTimeouts()
        if (active != null) {
            applyFileListFailure(
                snapshot = active,
                error = FileListError(
                    FileListErrorCode.DISCONNECTED,
                    "device disconnected during file-list session",
                ),
                completionReason = FileListCompletionReason.DISCONNECTED,
            )
            return
        }
        markDeviceFilesStale()
    }

    private fun markDeviceFilesStale() {
        val previous = mutableDeviceFileListState.value
        if (previous.lastUpdatedTimeMs != null) {
            mutableDeviceFileListState.value = previous.copy(
                freshness = FileListFreshness.STALE,
                activeSessionId = null,
            )
        }
    }

    private fun cancelFileListTimeouts() {
        fileListFirstResponseJob?.cancel()
        fileListFirstResponseJob = null
        fileListTotalTimeoutJob?.cancel()
        fileListTotalTimeoutJob = null
    }

    private fun acknowledgeHardwareEventAndResync(event: RecordingHardwareEvent) {
        scope.launch {
            val acknowledged = session.acknowledgeHardwareRecordingEvent(event.kind)
            if (!acknowledged) {
                reduceRecordingState(
                    RecordingStateEvent.OperationError(
                        RecordingError(
                            RecordingErrorCode.WRITE_FAILED,
                            "hardware " + event.kind.name + " acknowledgement failed",
                        ),
                        nowMs(),
                    ),
                    syncReason = RecordingSyncReason.HARDWARE_EVENT,
                )
            }
            scheduleRecordingSync(
                reason = RecordingSyncReason.HARDWARE_EVENT,
                delayMs = HARDWARE_EVENT_RECONCILE_DELAY_MS,
                expectedStatus = RecordingStateConvergencePolicy.expectedForHardwareEvent(
                    event.kind,
                ),
            )
        }
    }

    private fun scheduleRecordingSync(
        reason: RecordingSyncReason,
        delayMs: Long,
        expectedStatus: RecordingStatus? = null,
    ) {
        recordingReconcileJob?.cancel()
        recordingReconcileJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            syncRecordingState(
                reason = reason,
                expectedStatus = expectedStatus,
            )
        }
    }

    private fun updateRecordingPoller() {
        val shouldPoll = RecordingPollingPolicy.shouldPoll(
            ready = session.state.value is DeviceConnectionState.Ready,
            foreground = foreground,
            status = mutableRecordingState.value.status,
        )

        if (!shouldPoll) {
            stopRecordingPoller()
            return
        }
        if (recordingPollJob?.isActive == true) return

        val pollJob = scope.launch {
            session.updateRecordingDiagnostics(
                mutableRecordingState.value,
                pollingActive = true,
            )
            while (
                isActive &&
                RecordingPollingPolicy.shouldPoll(
                    ready = session.state.value is DeviceConnectionState.Ready,
                    foreground = foreground,
                    status = mutableRecordingState.value.status,
                )
            ) {
                delay(RECORDING_TIME_POLL_MS)
                if (!isActive) break
                if (
                    !RecordingPollingPolicy.shouldPoll(
                        ready = session.state.value is DeviceConnectionState.Ready,
                        foreground = foreground,
                        status = mutableRecordingState.value.status,
                    )
                ) {
                    break
                }

                when (val time = session.readRecordingTime()) {
                    is RecordingRequestOutcome.Success ->
                        reduceRecordingState(
                            RecordingStateEvent.TimeReceived(time.value, nowMs()),
                            syncReason = RecordingSyncReason.PERIODIC_REFRESH,
                        )
                    else -> noteOptionalReadFailure(time, "GET_TIME")
                }
            }
        }
        recordingPollJob = pollJob
        pollJob.invokeOnCompletion {
            if (recordingPollJob === pollJob) {
                recordingPollJob = null
                session.updateRecordingDiagnostics(
                    mutableRecordingState.value,
                    pollingActive = false,
                )
            }
        }
    }

    private fun stopRecordingPoller() {
        val current = recordingPollJob
        recordingPollJob = null
        current?.cancel()
        session.updateRecordingDiagnostics(
            mutableRecordingState.value,
            pollingActive = false,
        )
    }

    private fun markRecordingDisconnected() {
        if (mutableRecordingState.value.freshness == RecordingFreshness.NOT_SYNCED) return
        reduceRecordingState(RecordingStateEvent.Disconnected(nowMs()))
    }

    private fun reduceRecordingState(
        event: RecordingStateEvent,
        syncReason: RecordingSyncReason? = null,
    ) {
        mutableRecordingState.value =
            RecordingStateReducer.reduce(mutableRecordingState.value, event)
        session.updateRecordingDiagnostics(
            mutableRecordingState.value,
            reason = syncReason,
        )
    }

    private fun <T> noteOptionalReadFailure(
        outcome: RecordingRequestOutcome<T>,
        operation: String,
    ) {
        val error = requestError(outcome, operation) ?: return
        session.noteRecordingAuxiliaryReadFailure(
            operation = operation,
            detail = error.code.name +
                (error.detail?.let { ": " + it } ?: ""),
        )
    }

    private fun commandError(
        outcome: RecordingRequestOutcome<RecordingCommandResult>,
        expectedSuccessCode: Int,
    ): RecordingError? =
        when (outcome) {
            is RecordingRequestOutcome.Success ->
                if (outcome.value.rawCode == expectedSuccessCode) {
                    null
                } else {
                    RecordingError(
                        RecordingErrorCode.UNKNOWN_RESULT_CODE,
                        "raw=" + outcome.value.rawCode +
                            " expected=" + expectedSuccessCode,
                    )
                }

            is RecordingRequestOutcome.Malformed ->
                RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    outcome.reason,
                )

            RecordingRequestOutcome.WriteFailed ->
                RecordingError(RecordingErrorCode.WRITE_FAILED)

            RecordingRequestOutcome.ResponseTimedOut ->
                RecordingError(RecordingErrorCode.RESPONSE_TIMEOUT)

            RecordingRequestOutcome.Cancelled ->
                RecordingError(RecordingErrorCode.REQUEST_CANCELLED)
        }

    private fun <T> requestError(
        outcome: RecordingRequestOutcome<T>,
        operation: String,
    ): RecordingError? =
        when (outcome) {
            is RecordingRequestOutcome.Success -> null
            is RecordingRequestOutcome.Malformed ->
                RecordingError(
                    RecordingErrorCode.MALFORMED_PAYLOAD,
                    operation + ": " + outcome.reason,
                )
            RecordingRequestOutcome.WriteFailed ->
                RecordingError(
                    RecordingErrorCode.WRITE_FAILED,
                    operation,
                )
            RecordingRequestOutcome.ResponseTimedOut ->
                RecordingError(
                    RecordingErrorCode.RESPONSE_TIMEOUT,
                    operation,
                )
            RecordingRequestOutcome.Cancelled ->
                RecordingError(
                    RecordingErrorCode.REQUEST_CANCELLED,
                    operation,
                )
        }

    private fun maybeAutoConnectRememberedDevice() {
        val target = lastAddress
        if (
            !RememberedDeviceAutoConnectPolicy.shouldAttempt(
                foreground = foreground,
                userDisconnectedThisProcess = userDisconnectedThisProcess,
                rememberedAddress = target,
                currentState = session.state.value,
            )
        ) return
        if (initialEnvironmentState() !is DeviceConnectionState.Idle) return

        scanner.stop()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        session.setReconnectAttempt(0)
        target ?: return
        connectInternal(target)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) {
            mutableConnectionState.value = environment
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            mutableConnectionState.value = DeviceConnectionState.Unavailable
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            mutableConnectionState.value = DeviceConnectionState.Error(
                BleError(BleErrorCode.CONNECT_FAILED, error.message),
            )
            return
        } catch (security: SecurityException) {
            mutableConnectionState.value = DeviceConnectionState.PermissionRequired(
                BlePermissionPolicy.requiredPermissions(),
            )
            return
        }

        session.connect(device)
    }

    private fun scheduleReconnect(address: String?) {
        val target = address ?: return
        if (!foreground || userDisconnectedThisProcess) return

        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) return

        val nextAttempt = reconnectAttempt + 1
        val waitMs = ReconnectPolicy.delayForAttempt(nextAttempt)
        if (waitMs == null) {
            reconnectJob?.cancel()
            reconnectJob = null
            mutableConnectionState.value = DeviceConnectionState.Disconnected(
                target,
                DisconnectReason.REMOTE,
            )
            return
        }
        reconnectAttempt = nextAttempt
        session.setReconnectAttempt(nextAttempt)
        mutableConnectionState.value = DeviceConnectionState.ReconnectWaiting(
            address = target,
            attempt = nextAttempt,
            delayMs = waitMs,
        )

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            reconnectJob = null
            if (
                foreground &&
                reconnectAttempt == nextAttempt &&
                initialEnvironmentState() is DeviceConnectionState.Idle
            ) {
                connectInternal(target)
            }
        }
    }

    private fun refreshEnvironment() {
        val environment = initialEnvironmentState()
        if (environment !is DeviceConnectionState.Idle) {
            mutableConnectionState.value = environment
            return
        }

        val sessionState = session.state.value
        mutableConnectionState.value =
            if (
                sessionState is DeviceConnectionState.Idle ||
                sessionState is DeviceConnectionState.Disconnected ||
                sessionState is DeviceConnectionState.Error ||
                sessionState is DeviceConnectionState.BluetoothOff
            ) {
                DeviceConnectionState.Idle
            } else {
                sessionState
            }
    }

    private fun initialEnvironmentState(): DeviceConnectionState {
        val hasBle = applicationContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE,
        )
        if (!hasBle || bluetoothManager?.adapter == null) {
            return DeviceConnectionState.Unavailable
        }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            return DeviceConnectionState.PermissionRequired(missing)
        }

        if (!isBluetoothEnabled()) {
            return DeviceConnectionState.BluetoothOff
        }

        return DeviceConnectionState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean =
        try {
            bluetoothManager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(
                bluetoothReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    override fun close() {
        reconnectJob?.cancel()
        refreshJob?.cancel()
        recordingReconcileJob?.cancel()
        cancelFileListTimeouts()
        fileListSessionCoordinator.cancel(nowMs())
        activeFileTransferSession?.requestCancel(TransferCancelReason.BACKGROUND)
        stopRecordingPoller()
        scanner.stop()
        session.close()

        if (receiverRegistered) {
            try {
                applicationContext.unregisterReceiver(bluetoothReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            receiverRegistered = false
        }

        scope.cancel()
    }

    private companion object {
        const val RECORDING_TIME_POLL_MS = 1_000L
        const val RECORDING_ACTION_SETTLE_MS = 120L
        const val HARDWARE_EVENT_RECONCILE_DELAY_MS = 120L
        const val AUTO_CONNECT_AFTER_BLUETOOTH_ON_MS = 300L
        const val FILE_LIST_FIRST_RESPONSE_TIMEOUT_MS = 8_000L
        const val FILE_LIST_TOTAL_SESSION_TIMEOUT_MS = 20_000L
        const val FILE_DOWNLOAD_FRAME_FIXED_BYTES = 12
        const val FILE_DIAGNOSTIC_PREFIX_BYTES = 32
    }
}
