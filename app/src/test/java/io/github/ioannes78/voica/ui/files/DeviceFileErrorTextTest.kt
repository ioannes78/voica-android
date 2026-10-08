package io.github.ioannes78.voica.ui.files

import io.github.ioannes78.voica.ble.FileOperationErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFileErrorTextTest {
    @Test
    fun everyFileOperationErrorHasUserFacingText() {
        FileOperationErrorCode.entries.forEach { code ->
            val message = code.toUserMessage()
            assertTrue("missing user message for $code", message.isNotBlank())
            assertFalse("raw enum leaked for $code", message.contains(code.name))
        }
    }

    @Test
    fun disconnectedDownloadUsesReadableChineseMessage() {
        assertEquals(
            "录音卡连接已断开",
            FileOperationErrorCode.BLE_DISCONNECTED.toUserMessage(),
        )
    }
}
