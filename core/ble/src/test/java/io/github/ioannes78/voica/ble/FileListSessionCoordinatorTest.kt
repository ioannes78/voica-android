package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FileListChunk
import io.github.ioannes78.voica.protocol.RawDeviceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListSessionCoordinatorTest {
    @Test
    fun aggregatesMultipleDataFramesUntilDone() {
        val coordinator = FileListSessionCoordinator()
        val sessionId = coordinator.start(transportSessionId = 4, deviceAddress = "AA", startedAtMs = 10)
        coordinator.noteRequestSequence(sessionId, 9)

        val first = coordinator.accept(
            dataEvent("a.opus", NotificationSource.AE22),
            transportSessionId = 4,
            nowMs = 20,
        ) as FileListSessionResult.DataAccepted
        assertEquals(1, first.snapshot.dataFrameCount)

        val second = coordinator.accept(
            dataEvent("b.opus", NotificationSource.AE23),
            transportSessionId = 4,
            nowMs = 30,
        ) as FileListSessionResult.DataAccepted
        assertEquals(2, second.snapshot.entries.size)

        val completed = coordinator.accept(
            FileListFrameEvent.Done(NotificationSource.AE23, 3, 0),
            transportSessionId = 4,
            nowMs = 40,
        ) as FileListSessionResult.Completed

        assertEquals(2, completed.snapshot.dataFrameCount)
        assertEquals(2, completed.snapshot.declaredEntryCount)
        assertTrue(completed.snapshot.receivedListDone)
        assertEquals(9, completed.snapshot.requestSequence)
        assertNull(coordinator.snapshot(sessionId))
    }

    @Test
    fun doneWithoutDataProducesValidEmptyCompletion() {
        val coordinator = FileListSessionCoordinator()
        coordinator.start(transportSessionId = 1, deviceAddress = "AA", startedAtMs = 0)

        val completed = coordinator.accept(
            FileListFrameEvent.Done(NotificationSource.AE22, 1, 0),
            transportSessionId = 1,
            nowMs = 1,
        ) as FileListSessionResult.Completed

        assertTrue(completed.snapshot.entries.isEmpty())
        assertTrue(completed.snapshot.receivedListDone)
    }

    @Test
    fun malformedDataFailsSessionAndCannotBecomeFreshLater() {
        val coordinator = FileListSessionCoordinator()
        val sessionId = coordinator.start(transportSessionId = 1, deviceAddress = "AA", startedAtMs = 0)

        val failed = coordinator.accept(
            FileListFrameEvent.Malformed(
                source = NotificationSource.AE22,
                sequence = 1,
                reason = "bad payload",
                bodySize = 3,
            ),
            transportSessionId = 1,
            nowMs = 10,
        ) as FileListSessionResult.Failed

        assertEquals(FileListErrorCode.MALFORMED_PAYLOAD, failed.error.code)
        assertNull(coordinator.snapshot(sessionId))

        val lateDone = coordinator.accept(
            FileListFrameEvent.Done(NotificationSource.AE22, 2, 0),
            transportSessionId = 1,
            nowMs = 20,
        )
        assertTrue(lateDone is FileListSessionResult.Ignored)
    }

    @Test
    fun transportMismatchIsIgnored() {
        val coordinator = FileListSessionCoordinator()
        coordinator.start(transportSessionId = 7, deviceAddress = "AA", startedAtMs = 0)

        val result = coordinator.accept(
            dataEvent("a.opus", NotificationSource.AE22),
            transportSessionId = 8,
            nowMs = 10,
        )

        assertTrue(result is FileListSessionResult.Ignored)
    }

    private fun dataEvent(
        name: String,
        source: NotificationSource,
    ): FileListFrameEvent.Data {
        val nameBytes = ByteArray(20)
        name.encodeToByteArray().copyInto(nameBytes)
        val entry = RawDeviceFileEntry(
            rawTimeValue = 1,
            sizeBytes = 2,
            rawFilename = name,
            rawFilenameBytes = nameBytes,
            filenameFieldLength = 20,
            rawEntryBytes = ByteArray(28),
        )
        return FileListFrameEvent.Data(
            source = source,
            sequence = 1,
            chunk = FileListChunk(
                declaredCount = 1,
                filenameFieldLength = 20,
                entries = listOf(entry),
                bodySize = 32,
            ),
        )
    }
}
