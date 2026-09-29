package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FilenameResolution
import io.github.ioannes78.voica.protocol.RawDeviceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceFileMapperTest {
    @Test
    fun resolvesStandardTrailingDotAndSortsNewestFirst() {
        val files = RemoteDeviceFileMapper.map(
            deviceAddress = "AA",
            entries = listOf(
                entry("note20260928-120000.", 1, 10),
                entry("note20260929-120000.opus", 2, 20, 24),
            ),
        )

        assertEquals("note20260929-120000.opus", files[0].displayFilename)
        assertEquals("note20260928-120000.opus", files[1].displayFilename)
        assertEquals(
            FilenameResolution.RecoveredStandardOpusName,
            files[1].filenameResolution,
        )
        assertTrue(files.all { it.durationSeconds == null })
    }

    @Test
    fun doesNotGenerateWavVariantOrGuessUnknownTrailingDot() {
        val files = RemoteDeviceFileMapper.map(
            deviceAddress = "AA",
            entries = listOf(entry("meeting.", 1, 10)),
        )

        assertEquals(1, files.size)
        assertEquals("meeting.", files.single().displayFilename)
        assertTrue(files.single().identityProvisional)
    }

    @Test
    fun duplicateFramesDoNotDuplicateSameRemoteIdentity() {
        val item = entry("note20260929-120000.opus", 1, 10, 24)
        val files = RemoteDeviceFileMapper.map("AA", listOf(item, item))
        assertEquals(1, files.size)
    }

    private fun entry(
        name: String,
        time: Long,
        size: Long,
        fieldLength: Int = 20,
    ): RawDeviceFileEntry {
        val bytes = ByteArray(fieldLength)
        name.encodeToByteArray().copyInto(bytes)
        return RawDeviceFileEntry(
            rawTimeValue = time,
            sizeBytes = size,
            rawFilename = name,
            rawFilenameBytes = bytes,
            filenameFieldLength = fieldLength,
            rawEntryBytes = ByteArray(8 + fieldLength),
        )
    }
}
