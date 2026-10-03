package io.github.ioannes78.voica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.protocol.RecordingGain
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeviceActionMessage {
    SYNC_SENT,
    SYNC_FAILED,
}

sealed interface RemoteDeleteBatchState {
    data object Idle : RemoteDeleteBatchState

    data class Running(
        val total: Int,
        val completed: Int,
        val currentIdentity: String,
    ) : RemoteDeleteBatchState

    data class Completed(
        val total: Int,
        val succeeded: Int,
        val failedIdentities: List<String>,
    ) : RemoteDeleteBatchState

    data class StoppedUnknown(
        val total: Int,
        val completed: Int,
        val currentIdentity: String,
        val succeeded: Int,
        val failedIdentities: List<String>,
    ) : RemoteDeleteBatchState
}

class DeviceViewModel(
    private val repository: DeviceRepository,
    private val recordingLibraryRepository: RecordingLibraryRepository,
    private val canonicalAudioCoordinator: CanonicalAudioCoordinator,
) : ViewModel() {
    val scanState = repository.scanState
    val connectionState = repository.connectionState
    val deviceInfo = repository.deviceInfo
    val recordingState = repository.recordingState
    val deviceFileListState = repository.deviceFileListState
    val fileOperationState = repository.fileOperationState
    val fileTransferDiagnostics = repository.fileTransferDiagnostics
    val remoteDeleteDiagnostics = repository.remoteDeleteDiagnostics
    val rangeProbeDiagnostics = repository.rangeProbeDiagnostics
    val localRecordings = repository.localRecordings
    val libraryRecordings = recordingLibraryRepository.recordings
    val diagnostics = repository.diagnostics

    private val canonicalJobs = mutableMapOf<String, Job>()

    private val mutableMissingPermissions =
        MutableStateFlow(repository.missingPermissions())
    val missingPermissions: StateFlow<Set<String>> =
        mutableMissingPermissions.asStateFlow()

    private val mutableActionMessage = MutableStateFlow<DeviceActionMessage?>(null)
    val actionMessage: StateFlow<DeviceActionMessage?> = mutableActionMessage.asStateFlow()

    private val mutableRemoteDeleteBatchState =
        MutableStateFlow<RemoteDeleteBatchState>(RemoteDeleteBatchState.Idle)
    val remoteDeleteBatchState: StateFlow<RemoteDeleteBatchState> =
        mutableRemoteDeleteBatchState.asStateFlow()
    private var remoteDeleteBatchJob: Job? = null

    fun refreshPermissions() {
        mutableMissingPermissions.value = repository.missingPermissions()
        repository.onPermissionsChanged()
    }

    fun startScan() {
        refreshPermissions()
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connect(address: String) {
        repository.connect(address)
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun refreshDeviceInfo() {
        viewModelScope.launch {
            repository.refreshDeviceInfo()
        }
    }

    fun syncTime() {
        viewModelScope.launch {
            mutableActionMessage.value =
                if (repository.syncTime()) {
                    DeviceActionMessage.SYNC_SENT
                } else {
                    DeviceActionMessage.SYNC_FAILED
                }
        }
    }

    fun consumeActionMessage() {
        mutableActionMessage.value = null
    }

    fun startRecording() {
        viewModelScope.launch { repository.startRecording() }
    }

    fun pauseRecording() {
        viewModelScope.launch { repository.pauseRecording() }
    }

    fun resumeRecording() {
        viewModelScope.launch { repository.resumeRecording() }
    }

    fun saveRecording() {
        viewModelScope.launch { repository.saveRecording() }
    }

    fun refreshRecordingState() {
        viewModelScope.launch { repository.syncRecordingState() }
    }

    fun setRecordingGain(gain: RecordingGain) {
        viewModelScope.launch { repository.setRecordingGain(gain) }
    }

    fun refreshDeviceFiles() {
        viewModelScope.launch { repository.refreshDeviceFiles() }
    }

    fun downloadDeviceFile(file: RemoteDeviceFile, format: DeviceAudioFormat) {
        viewModelScope.launch { repository.downloadDeviceFile(file, format) }
    }

    fun cancelDeviceFileDownload() {
        viewModelScope.launch { repository.cancelDeviceFileDownload() }
    }

    fun deleteRemoteRecording(file: RemoteDeviceFile) {
        viewModelScope.launch { repository.deleteRemoteRecording(file) }
    }

    fun deleteRemoteRecordings(files: List<RemoteDeviceFile>) {
        if (files.isEmpty() || remoteDeleteBatchJob?.isActive == true) return
        remoteDeleteBatchJob =
            viewModelScope.launch {
                val ordered = files.distinctBy { it.identity }
                var succeeded = 0
                val failed = mutableListOf<String>()

                for ((index, file) in ordered.withIndex()) {
                    mutableRemoteDeleteBatchState.value =
                        RemoteDeleteBatchState.Running(
                            total = ordered.size,
                            completed = index,
                            currentIdentity = file.identity,
                        )

                    repository.deleteRemoteRecording(file)

                    when (val state = repository.fileOperationState.value) {
                        is FileOperationState.Completed -> {
                            if (
                                state.operation == DeviceFileOperationType.DELETE_REMOTE &&
                                state.remoteIdentity == file.identity
                            ) {
                                succeeded += 1
                            } else {
                                failed += file.identity
                            }
                        }

                        is FileOperationState.OutcomeUnknown -> {
                            mutableRemoteDeleteBatchState.value =
                                RemoteDeleteBatchState.StoppedUnknown(
                                    total = ordered.size,
                                    completed = index,
                                    currentIdentity = file.identity,
                                    succeeded = succeeded,
                                    failedIdentities = failed.toList(),
                                )
                            return@launch
                        }

                        is FileOperationState.Failed,
                        is FileOperationState.Cancelled,
                        is FileOperationState.Active,
                        FileOperationState.Idle,
                        -> {
                            failed += file.identity
                        }
                    }
                }

                mutableRemoteDeleteBatchState.value =
                    RemoteDeleteBatchState.Completed(
                        total = ordered.size,
                        succeeded = succeeded,
                        failedIdentities = failed.toList(),
                    )
            }
    }

    fun dismissRemoteDeleteBatchResult() {
        if (mutableRemoteDeleteBatchState.value !is RemoteDeleteBatchState.Running) {
            mutableRemoteDeleteBatchState.value = RemoteDeleteBatchState.Idle
        }
    }

    fun runRangeProbe(file: RemoteDeviceFile) {
        viewModelScope.launch { repository.runRangeProbe(file) }
    }

    fun renameLocalRecording(recordingId: String, displayName: String) {
        viewModelScope.launch {
            recordingLibraryRepository.rename(recordingId, displayName)
        }
    }

    fun deleteLibraryRecording(recordingId: String) {
        viewModelScope.launch {
            recordingLibraryRepository.deleteLocalRecording(recordingId)
        }
    }

    fun generateCanonicalAudio(recordingId: String) {
        if (canonicalJobs[recordingId]?.isActive == true) return
        canonicalJobs[recordingId] = viewModelScope.launch {
            try {
                canonicalAudioCoordinator.generate(recordingId)
            } finally {
                canonicalJobs.remove(recordingId)
            }
        }
    }

    fun cancelCanonicalAudio(recordingId: String) {
        canonicalAudioCoordinator.cancel(recordingId)
        canonicalJobs.remove(recordingId)?.cancel()
    }

    class Factory(
        private val repository: DeviceRepository,
        private val recordingLibraryRepository: RecordingLibraryRepository,
        private val canonicalAudioCoordinator: CanonicalAudioCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DeviceViewModel(
                repository,
                recordingLibraryRepository,
                canonicalAudioCoordinator,
            ) as T
    }
}
