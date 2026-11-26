package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.ai.*
import org.junit.Assert.*
import org.junit.Test

class OfflineQaPresentationTest {
    @Test fun canonicalTimeFormattingDoesNotWrapHours() {
        assertEquals("00:00:00", qaTime(0))
        assertEquals("00:01:01", qaTime(61 * 16000L))
        assertEquals("27:00:00", qaTime(27 * 3600 * 16000L))
    }
    @Test fun everyFailureHasUserFacingMessage() {
        assertTrue(OfflineQaFailureCode.entries.all { offlineQaFailureMessage(it).isNotBlank() })
        assertNotEquals(offlineQaFailureMessage(OfflineQaFailureCode.SOURCE_CHANGED),
            offlineQaFailureMessage(OfflineQaFailureCode.RUNTIME_BUSY))
    }
}
