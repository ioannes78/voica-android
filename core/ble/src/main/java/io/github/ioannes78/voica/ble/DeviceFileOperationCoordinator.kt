package io.github.ioannes78.voica.ble

data class ActiveDeviceFileOperation(
    val operationId: Long,
    val type: DeviceFileOperationType,
)

sealed interface RefreshRequestDecision {
    data class Started(
        val operation: ActiveDeviceFileOperation,
        val reason: RefreshReason,
    ) : RefreshRequestDecision

    data class Queued(val reason: RefreshReason) : RefreshRequestDecision
    data class Coalesced(val reason: RefreshReason) : RefreshRequestDecision
}

class DeviceFileOperationCoordinator {
    private var nextOperationId = 1L
    private var active: ActiveDeviceFileOperation? = null
    private var pendingRefreshReason: RefreshReason? = null

    @Synchronized
    fun activeOperation(): ActiveDeviceFileOperation? = active

    @Synchronized
    fun tryStart(type: DeviceFileOperationType): ActiveDeviceFileOperation? {
        if (active != null) return null
        return newOperation(type).also { active = it }
    }

    @Synchronized
    fun requestRefresh(reason: RefreshReason): RefreshRequestDecision {
        val current = active
        if (current == null) {
            val started = newOperation(DeviceFileOperationType.REFRESH)
            active = started
            return RefreshRequestDecision.Started(started, reason)
        }

        if (current.type == DeviceFileOperationType.REFRESH) {
            return RefreshRequestDecision.Coalesced(reason)
        }

        if (pendingRefreshReason == null) {
            pendingRefreshReason = reason
            return RefreshRequestDecision.Queued(reason)
        }

        return RefreshRequestDecision.Coalesced(pendingRefreshReason ?: reason)
    }

    @Synchronized
    fun finish(operationId: Long): RefreshRequestDecision.Started? {
        val current = active ?: return null
        if (current.operationId != operationId) return null

        active = null
        val pending = pendingRefreshReason ?: return null
        pendingRefreshReason = null
        val started = newOperation(DeviceFileOperationType.REFRESH)
        active = started
        return RefreshRequestDecision.Started(started, pending)
    }

    @Synchronized
    fun cancelActive(operationId: Long): RefreshRequestDecision.Started? =
        finish(operationId)

    @Synchronized
    fun reset() {
        active = null
        pendingRefreshReason = null
    }

    @Synchronized
    fun hasPendingRefresh(): Boolean = pendingRefreshReason != null

    private fun newOperation(type: DeviceFileOperationType): ActiveDeviceFileOperation =
        ActiveDeviceFileOperation(
            operationId = nextOperationId++,
            type = type,
        )
}
