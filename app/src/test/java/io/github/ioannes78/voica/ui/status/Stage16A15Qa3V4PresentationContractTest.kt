package io.github.ioannes78.voica.ui.status

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A15Qa3V4PresentationContractTest {
    @Test
    fun `V4 home removes fake recorder waveform and keeps realtime truth`() {
        val panel = source("ui/device/HomeActivityPanel.kt")
        assertFalse(panel.contains("DecorativeWaveform"))
        assertTrue(panel.contains("录音设备：QS668 / CB08"))
        assertTrue(panel.contains("RealtimeAudioActivityWaveform"))
        assertTrue(panel.contains("modelLabel"))
        assertTrue(panel.contains("MaterialTheme.colorScheme.error"))
    }

    @Test
    fun `V4 device status exposes file count and recent content`() {
        val status = source("ui/device/CompactHomeDeviceStatus.kt")
        val screen = source("ui/device/DeviceProductScreen.kt")
        assertTrue(status.contains("录音文件"))
        assertTrue(status.contains("recordingFileCount"))
        assertTrue(screen.contains("HomeRecentContent"))
        assertFalse(screen.contains("DeviceFilesEntry("))
    }

    @Test
    fun `V4 gain and recorder realtime stop are presentation navigation only`() {
        val gain = source("ui/device/HomeRecordingGainControl.kt")
        val controller = source("ui/realtime/RealtimeTranscriptionUiController.kt")
        assertTrue(gain.contains("pendingTarget"))
        assertTrue(gain.contains("gain == target"))
        assertTrue(controller.contains("suppressNextRecorderRealtimePlaybackOpen"))
    }

    private fun source(relativePath: String): String {
        val userDir = File(System.getProperty("user.dir"))
        val moduleRelative = File(userDir, "src/main/java/io/github/ioannes78/voica/$relativePath")
        val file = if (moduleRelative.exists() || userDir.name == "app") moduleRelative else File(userDir, "app/src/main/java/io/github/ioannes78/voica/$relativePath")
        return file.readText()
    }
}
