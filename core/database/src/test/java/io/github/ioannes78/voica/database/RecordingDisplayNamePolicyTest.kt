package io.github.ioannes78.voica.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDisplayNamePolicyTest {
    @Test
    fun standardDeviceNamesLoseOnlyTheirFormatExtension() {
        assertEquals(
            "note20260908-231420",
            RecordingDisplayNamePolicy.defaultDisplayName(
                "note20260908-231420.opus",
            ),
        )
        assertEquals(
            "note20260908-231420",
            RecordingDisplayNamePolicy.defaultDisplayName(
                "note20260908-231420.wav",
            ),
        )
    }

    @Test
    fun arbitraryNamesKeepTheirExtension() {
        assertEquals(
            "客户会议.opus",
            RecordingDisplayNamePolicy.defaultDisplayName("客户会议.opus"),
        )
    }

    @Test
    fun existingNameNormalizesOnlyWhenStillEqualToOriginalDeviceName() {
        assertTrue(
            RecordingDisplayNamePolicy.shouldNormalizeExisting(
                displayName = "note20260908-231420.opus",
                originalFilename = "note20260908-231420.opus",
            ),
        )
        assertFalse(
            RecordingDisplayNamePolicy.shouldNormalizeExisting(
                displayName = "客户会议.opus",
                originalFilename = "note20260908-231420.opus",
            ),
        )
    }
}
