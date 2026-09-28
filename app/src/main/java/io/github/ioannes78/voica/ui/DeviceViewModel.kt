package io.github.ioannes78.voica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.ble.DeviceRepository
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
    val diagnostics = repository.diagnostics

    private val mutableMissingPermissions =
        MutableStateFlow(repository.missingPermissions())
    val missingPermissions: StateFlow<Set<String>> =
        mutableMissingPermissions.asStateFlow()

    private val mutableActionMessage = MutableStateFlow<DeviceActionMessage?>(null)
    val actionMessage: StateFlow<DeviceActionMessage?> = mutableActionMessage.asStateFlow()

    fun refreshPermissions() {
        mutableMissingPermissions.value = repository.missingPermissions()
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

    class Factory(
        private val repository: DeviceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DeviceViewModel(repository) as T
    }
}
