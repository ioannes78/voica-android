package io.github.ioannes78.voica.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.StorageManagementCoordinator
import io.github.ioannes78.voica.StorageManagementSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageManagementUiState(
    val snapshot: StorageManagementSnapshot? = null,
    val loading: Boolean = false,
    val operationRunning: Boolean = false,
    val message: String? = null,
)

class StorageManagementViewModel(
    private val coordinator: StorageManagementCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageManagementUiState())
    val state: StateFlow<StorageManagementUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.loading || mutableState.value.operationRunning) return
        loadSnapshot()
    }

    fun cleanupTemporaryFiles() {
        runOperation {
            coordinator.cleanupSafeTemporaryFiles()
        }
    }

    fun cleanupCanonical() {
        runOperation {
            coordinator.cleanupReclaimableCanonical()
        }
    }

    fun removeDownloadedModel(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        runOperation {
            coordinator.removeDownloadedModel(modelId, version, revision)
        }
    }

    fun consumeMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun loadSnapshot() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            val result = runCatching { coordinator.snapshot() }
            mutableState.value =
                result.fold(
                    onSuccess = { snapshot ->
                        mutableState.value.copy(
                            snapshot = snapshot,
                            loading = false,
                        )
                    },
                    onFailure = { error ->
                        mutableState.value.copy(
                            loading = false,
                            message = error.message ?: "无法读取存储空间",
                        )
                    },
                )
        }
    }

    private fun runOperation(
        action: suspend () -> io.github.ioannes78.voica.StorageCleanupOutcome,
    ) {
        if (mutableState.value.operationRunning) return
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(
                    operationRunning = true,
                    message = null,
                )
            val result = runCatching { action() }
            val refreshed = runCatching { coordinator.snapshot() }.getOrNull()
            mutableState.value =
                result.fold(
                    onSuccess = { outcome ->
                        mutableState.value.copy(
                            snapshot = refreshed ?: mutableState.value.snapshot,
                            operationRunning = false,
                            loading = false,
                            message =
                                outcome.detail +
                                    if (outcome.reclaimedBytes > 0L) {
                                        "，释放 " + formatStorageBytes(outcome.reclaimedBytes)
                                    } else {
                                        ""
                                    },
                        )
                    },
                    onFailure = { error ->
                        mutableState.value.copy(
                            snapshot = refreshed ?: mutableState.value.snapshot,
                            operationRunning = false,
                            loading = false,
                            message = error.message ?: "存储操作失败",
                        )
                    },
                )
        }
    }

    class Factory(
        private val coordinator: StorageManagementCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StorageManagementViewModel(coordinator) as T
    }
}

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
