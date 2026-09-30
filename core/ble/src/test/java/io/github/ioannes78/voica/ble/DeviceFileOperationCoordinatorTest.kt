package io.github.ioannes78.voica.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFileOperationCoordinatorTest {
    @Test
    fun fileOperationsAreExclusive() {
        val coordinator = DeviceFileOperationCoordinator()
        val download = coordinator.tryStart(DeviceFileOperationType.DOWNLOAD)

        assertNotNull(download)
        assertNull(coordinator.tryStart(DeviceFileOperationType.DELETE_REMOTE))
        assertEquals(download, coordinator.activeOperation())
    }

    @Test
    fun refreshRequestsCoalesceWhileOperationIsActive() {
        val coordinator = DeviceFileOperationCoordinator()
        val download = coordinator.tryStart(DeviceFileOperationType.DOWNLOAD)!!

        assertTrue(
            coordinator.requestRefresh(RefreshReason.RECORDING_FINALIZED) is
                RefreshRequestDecision.Queued,
        )
        assertTrue(
            coordinator.requestRefresh(RefreshReason.USER_REQUESTED) is
                RefreshRequestDecision.Coalesced,
        )
        assertTrue(coordinator.hasPendingRefresh())

        val next = coordinator.finish(download.operationId)
        assertNotNull(next)
        assertEquals(DeviceFileOperationType.REFRESH, next!!.operation.type)
        assertEquals(RefreshReason.RECORDING_FINALIZED, next.reason)
        assertFalse(coordinator.hasPendingRefresh())
    }

    @Test
    fun refreshStartsImmediatelyWhenIdleAndDuplicatesCoalesce() {
        val coordinator = DeviceFileOperationCoordinator()
        val first = coordinator.requestRefresh(RefreshReason.INITIAL_CONNECTION)

        assertTrue(first is RefreshRequestDecision.Started)
        val duplicate = coordinator.requestRefresh(RefreshReason.RECONNECTED)
        assertTrue(duplicate is RefreshRequestDecision.Coalesced)
    }
}
