package io.github.ioannes78.voica.ui.device

import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRow
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.protocol.FilenameResolution
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecentContentTest {
    @Test
    fun remoteOnlyRecordingIsIncludedAndLinkedRemoteIsNotDuplicated() {
        val downloaded =
            recording(
                id = "recording-a",
                remoteIdentity = "remote-a",
                recordedAt = "2026-10-16T10:00:00",
            )
        val rows = listOf(libraryRow("recording-a", hasCompletedTranscription = true))
        val remotes =
            listOf(
                remote("remote-a", "note20261016-100000.opus", LocalDateTime.of(2026, 10, 16, 10, 0)),
                remote("remote-b", "note20261016-110000.opus", LocalDateTime.of(2026, 10, 16, 11, 0)),
            )

        val items =
            buildHomeRecentContentItems(
                recordings = listOf(downloaded),
                deviceFiles = remotes,
                libraryRows = rows,
                maxItems = 5,
            )

        assertEquals(2, items.size)
        assertEquals("remote-b", items.first().remoteIdentity)
        assertTrue(items.first().recording == null)

        val linked = requireNotNull(items.firstOrNull { it.recording?.id == "recording-a" })
        assertEquals("remote-a", linked.remote?.identity)
        assertTrue(linked.hasCompletedTranscription)
        assertEquals(1, items.count { it.remoteIdentity == "remote-a" })
    }

    @Test
    fun completedTranscriptTruthDoesNotLeakToUnrelatedRecording() {
        val first = recording("recording-a", null, "2026-10-16T12:00:00")
        val second = recording("recording-b", null, "2026-10-16T11:00:00")
        val rows = listOf(libraryRow("recording-a", hasCompletedTranscription = true))

        val items =
            buildHomeRecentContentItems(
                recordings = listOf(first, second),
                deviceFiles = emptyList(),
                libraryRows = rows,
                maxItems = 5,
            )

        assertTrue(requireNotNull(items.firstOrNull { it.recording?.id == "recording-a" }).hasCompletedTranscription)
        assertFalse(requireNotNull(items.firstOrNull { it.recording?.id == "recording-b" }).hasCompletedTranscription)
    }

    @Test
    fun maxItemsKeepsNewestRecordingTime() {
        val items =
            buildHomeRecentContentItems(
                recordings = listOf(
                    recording("older", null, "2026-10-16T09:00:00"),
                    recording("newer", null, "2026-10-16T13:00:00"),
                ),
                deviceFiles = emptyList(),
                libraryRows = emptyList(),
                maxItems = 1,
            )

        assertEquals(listOf("newer"), items.mapNotNull { it.recording?.id })
    }

    private fun recording(
        id: String,
        remoteIdentity: String?,
        recordedAt: String,
    ): RecordingLibraryItem =
        RecordingLibraryItem(
            id = id,
            sourceRemoteIdentity = remoteIdentity,
            sourceDeviceAddress = remoteIdentity?.let { "AA:BB:CC:DD:EE:FF" },
            originalFilename = "$id.opus",
            displayName = id,
            recordedAtLocalIso = recordedAt,
            deviceReportedDurationMs = 60_000L,
            downloadedAtMs = 1L,
            state = "ACTIVE",
            assets = emptyList(),
            derivations = emptyList(),
            sourceType = if (remoteIdentity == null) RecordingSourceType.PHONE_MICROPHONE else RecordingSourceType.DEVICE_DOWNLOAD,
            mediaDurationMs = 60_000L,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

    private fun libraryRow(
        id: String,
        hasCompletedTranscription: Boolean,
    ): RecordingLibraryRow =
        RecordingLibraryRow(
            id = id,
            sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
            originalFilename = "$id.opus",
            displayName = id,
            recordedAtLocalIso = "2026-10-16T10:00:00",
            deviceReportedDurationMs = 60_000L,
            mediaDurationMs = 60_000L,
            downloadedAtMs = 1L,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            isFavorite = false,
            folderId = null,
            folderName = null,
            physicalAudioBytes = 1L,
            hasCompletedTranscription = hasCompletedTranscription,
            hasCompletedSummary = false,
            tagNames = emptyList(),
        )

    private fun remote(
        identity: String,
        filename: String,
        recordedAt: LocalDateTime,
    ): RemoteDeviceFile =
        RemoteDeviceFile(
            identity = identity,
            identityProvisional = false,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            rawTimeValue = 0L,
            durationSeconds = 60L,
            sizeBytes = 1024L,
            rawFilename = filename,
            resolvedFilename = filename,
            filenameResolution = FilenameResolution.DeviceFullName,
            filenameFieldLength = filename.length,
            recordedAt = recordedAt,
            deviceOrder = 0,
        )
}
