package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFilenameResolverTest {
    @Test
    fun keepsStandardFullOpusName() {
        val result = DeviceFilenameResolver.resolve("note20260929-120000.opus")
        assertEquals(FilenameResolution.DeviceFullName, result.resolution)
        assertEquals("note20260929-120000.opus", result.resolvedFilename)
    }

    @Test
    fun keepsStandardFullWavNameWithoutGeneratingVariants() {
        val result = DeviceFilenameResolver.resolve("note20260929-120000.wav")
        assertEquals(FilenameResolution.DeviceFullName, result.resolution)
        assertEquals("note20260929-120000.wav", result.resolvedFilename)
    }

    @Test
    fun recoversOnlyStandardTrailingDotNameAsOpus() {
        val result = DeviceFilenameResolver.resolve("note20260929-120000.")
        assertEquals(FilenameResolution.RecoveredStandardOpusName, result.resolution)
        assertEquals("note20260929-120000.opus", result.resolvedFilename)

        val unknown = DeviceFilenameResolver.resolve("meeting.")
        assertEquals(FilenameResolution.Unresolved, unknown.resolution)
        assertNull(unknown.resolvedFilename)
    }

    @Test
    fun keepsOrdinaryShortCompleteName() {
        val result = DeviceFilenameResolver.resolve("meeting.opus")
        assertEquals(FilenameResolution.RawCompleteName, result.resolution)
        assertEquals("meeting.opus", result.resolvedFilename)
    }

    @Test
    fun rejectsBlankOrControlCharacterNames() {
        assertEquals(
            FilenameResolution.Malformed,
            DeviceFilenameResolver.resolve("").resolution,
        )
        assertEquals(
            FilenameResolution.Malformed,
            DeviceFilenameResolver.resolve("bad\nname").resolution,
        )
    }
}
