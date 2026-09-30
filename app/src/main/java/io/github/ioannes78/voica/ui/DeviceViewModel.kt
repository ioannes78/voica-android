package io.github.ioannes78.voica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.protocol.RecordingGain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeviceActionMessage {
    SYNC_SENT,
    SYNC_FAILED,
}

class DeviceViewModel(
    private val repository: DeviceRepository,
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
    val diagnostics = repository.diagnostics

    private val mutableMissingPermissions =
        MutableStateFlow(repository.missingPermissions())
    val missingPermissions: StateFlow<Set<String>> =
        mutableMissingPermissions.asStateFlow()

    private val mutableActionMessage = MutableStateFlow<DeviceActionMessage?>(null)
    val actionMessage: StateFlow<DeviceActionMessage?> = mutableActionMessage.asStateFlow()

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

    fun downloadDeviceFile(file: RemoteDeviceFile) {
        viewModelScope.launch { repository.downloadDeviceFile(file) }
    }

    fun cancelDeviceFileDownload() {
        viewModelScope.launch { repository.cancelDeviceFileDownload() }
    }

    fun deleteRemoteRecording(file: RemoteDeviceFile) {
        viewModelScope.launch { repository.deleteRemoteRecording(file) }
    }

    fun runRangeProbe(file: RemoteDeviceFile) {
        viewModelScope.launch { repository.runRangeProbe(file) }
    }

    fun deleteLocalRecording(localId: String) {
        viewModelScope.launch { repository.deleteLocalRecording(localId) }
    }

    class Factory(
        private val repository: DeviceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DeviceViewModel(repository) as T
    }
}
