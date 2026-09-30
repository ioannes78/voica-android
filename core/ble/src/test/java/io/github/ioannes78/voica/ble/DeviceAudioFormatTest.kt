package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FilenameResolution
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceAudioFormatTest {
    @Test
    fun standardRecoveredRecordingProjectsOpusAndWavNames() {
        val file = standardFile(
            resolved = "note20260930-083059.opus",
            resolution = FilenameResolution.RecoveredStandardOpusName,
        )

        assertEquals("note20260930-083059.opus", file.downloadFilename(DeviceAudioFormat.OPUS))
        assertEquals("note20260930-083059.wav", file.downloadFilename(DeviceAudioFormat.WAV))
    }

    @Test
    fun unknownCompleteNameDoesNotInventSiblingFormat() {
        val file = standardFile(
            resolved = "meeting.opus",
            resolution = FilenameResolution.RawCompleteName,
        )

        assertEquals("meeting.opus", file.downloadFilename(DeviceAudioFormat.OPUS))
        assertNull(file.downloadFilename(DeviceAudioFormat.WAV))
    }

    private fun standardFile(
        resolved: String,
        resolution: FilenameResolution,
    ) = RemoteDeviceFile(
        identity = "AA|$resolved|80|16",
        identityProvisional = false,
        deviceAddress = "AA",
        rawTimeValue = 16,
        durationSeconds = 16,
        sizeBytes = 80,
        rawFilename = "note20260930-083059.",
        resolvedFilename = resolved,
        filenameResolution = resolution,
        filenameFieldLength = 20,
        recordedAt = LocalDateTime.of(2026, 9, 30, 8, 30, 59),
        deviceOrder = 0,
    )
}
